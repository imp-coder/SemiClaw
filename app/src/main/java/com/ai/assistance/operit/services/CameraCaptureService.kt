package com.ai.assistance.operit.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.OperitPaths
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * A custom LifecycleOwner that always stays in STARTED state
 * This allows camera to work even when app is in background
 * Uses createUnsafe() to avoid main thread enforcement
 */
class AlwaysActiveLifecycleOwner : LifecycleOwner {
    // Use createUnsafe() to allow setting state from any thread
    private val lifecycleRegistry = LifecycleRegistry.createUnsafe(this)

    init {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    override val lifecycle: Lifecycle = lifecycleRegistry

    fun markStarted() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    fun markDestroyed() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}

/**
 * CameraX based camera capture service
 *
 * Provides simple camera capture functionality for taking photos
 * Uses a custom LifecycleOwner to keep camera active even when app is in background
 */
class CameraCaptureService private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CameraCaptureService"

        @Volatile
        private var INSTANCE: CameraCaptureService? = null

        fun getInstance(context: Context): CameraCaptureService {
            return INSTANCE ?: synchronized(this) {
                val instance = CameraCaptureService(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var cameraExecutor: ExecutorService? = null
    private var isInitialized = false
    private var currentLensFacing: Int = CameraSelector.LENS_FACING_BACK

    // Custom lifecycle owner that stays active even when app is in background
    private var lifecycleOwner: AlwaysActiveLifecycleOwner? = null

    /**
     * Check if camera permission is granted
     */
    fun hasCameraPermission(): Boolean {
        val result = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        AppLogger.d(TAG, "Camera permission granted: $result")
        return result
    }

    /**
     * Initialize the camera
     *
     * @param lensFacing Camera lens direction (default: back camera)
     * @return true if initialization succeeded
     */
    suspend fun initialize(
        lensFacing: Int = CameraSelector.LENS_FACING_BACK
    ): Boolean {
        AppLogger.d(TAG, "initialize() called, isInitialized=$isInitialized, lensFacing=$lensFacing")

        if (isInitialized) {
            AppLogger.d(TAG, "Camera already initialized")
            return true
        }

        if (!hasCameraPermission()) {
            AppLogger.e(TAG, "Camera permission not granted")
            return false
        }

        currentLensFacing = lensFacing

        // Create a custom lifecycle owner that always stays in STARTED state
        // This allows camera to work even when app is in background
        lifecycleOwner = AlwaysActiveLifecycleOwner()
        AppLogger.d(TAG, "Created AlwaysActiveLifecycleOwner for camera")

        return suspendCancellableCoroutine { continuation ->
            try {
                // Create executor
                cameraExecutor = Executors.newSingleThreadExecutor()
                AppLogger.d(TAG, "Camera executor created")

                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                AppLogger.d(TAG, "Getting camera provider future...")

                cameraProviderFuture.addListener({
                    try {
                        cameraProvider = cameraProviderFuture.get()
                        AppLogger.d(TAG, "Camera provider obtained: $cameraProvider")

                        // Create image capture use case first
                        // Using 640x480 for faster upload and AI processing
                        imageCapture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .setTargetResolution(android.util.Size(640, 480))
                            .setTargetRotation(android.view.Surface.ROTATION_0)
                            .build()
                        AppLogger.d(TAG, "ImageCapture created with target resolution 640x480")

                        // Unbind all use cases before binding
                        cameraProvider?.unbindAll()
                        AppLogger.d(TAG, "Unbound all previous use cases")

                        // Bind use cases to lifecycle
                        val owner = lifecycleOwner
                        if (owner == null) {
                            AppLogger.e(TAG, "lifecycleOwner is null, cannot bind camera")
                            continuation.resume(false)
                            return@addListener
                        }

                        // Try multiple camera selection strategies for external cameras
                        // Strategy 1: Use specified lensFacing (back/front)
                        var cameraSelector = CameraSelector.Builder()
                            .requireLensFacing(lensFacing)
                            .build()

                        var boundSuccessfully = false
                        try {
                            cameraProvider?.bindToLifecycle(
                                owner,
                                cameraSelector,
                                imageCapture
                            )
                            boundSuccessfully = true
                            AppLogger.d(TAG, "Camera bound successfully with LensFacing=$lensFacing")
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Failed to bind with LensFacing=$lensFacing: ${e.message}")

                            // Strategy 2: Try to find any available camera (for external cameras with null lensFacing)
                            try {
                                // Get all available cameras and use the first one
                                val availableCameras = cameraProvider?.availableCameraInfos ?: emptyList()
                                AppLogger.d(TAG, "Available cameras: ${availableCameras.size}")

                                if (availableCameras.isNotEmpty()) {
                                    // Use first available camera (external cameras usually appear first)
                                    val firstCameraId = availableCameras.first().cameraSelector
                                    AppLogger.d(TAG, "Using first available camera selector")

                                    cameraProvider?.bindToLifecycle(
                                        owner,
                                        firstCameraId,
                                        imageCapture
                                    )
                                    boundSuccessfully = true
                                    AppLogger.d(TAG, "Camera bound successfully using first available camera")
                                }
                            } catch (e2: Exception) {
                                AppLogger.e(TAG, "Failed to bind with first available camera: ${e2.message}")
                            }
                        }

                        if (boundSuccessfully) {
                            isInitialized = true
                            AppLogger.d(TAG, "Camera initialized successfully!")
                            continuation.resume(true)
                        } else {
                            AppLogger.e(TAG, "Failed to bind any camera")
                            continuation.resume(false)
                        }

                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed to initialize camera in listener", e)
                        continuation.resume(false)
                    }
                }, ContextCompat.getMainExecutor(context))

                continuation.invokeOnCancellation {
                    AppLogger.d(TAG, "Initialization cancelled")
                    cameraExecutor?.shutdown()
                    cameraExecutor = null
                }

            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to get camera provider", e)
                continuation.resume(false)
            }
        }
    }

    /**
     * Take a photo and save to file
     *
     * @param timeoutMs Timeout in milliseconds (default 30 seconds)
     * @return The file path of the captured image, or null if failed
     */
    suspend fun takePicture(timeoutMs: Long = 30000): String? {
        val takePictureStartTime = System.currentTimeMillis()
        AppLogger.d(TAG, "【摄像计时】takePicture() called, isInitialized=$isInitialized, timeout=$timeoutMs")

        if (!isInitialized) {
            AppLogger.e(TAG, "Camera not initialized, cannot take picture")
            return null
        }

        if (imageCapture == null) {
            AppLogger.e(TAG, "ImageCapture is null, cannot take picture")
            return null
        }

        val executor = cameraExecutor
        if (executor == null) {
            AppLogger.e(TAG, "Camera executor is null, cannot take picture")
            return null
        }

        // Ensure output directory exists
        val outputDir = OperitPaths.cleanOnExitDir()
        if (!outputDir.exists()) {
            outputDir.mkdirs()
            AppLogger.d(TAG, "Created output directory: ${outputDir.absolutePath}")
        }

        val fileName = "camera_${System.currentTimeMillis()}.jpg"
        val outputFile = File(outputDir, fileName)
        val capture = imageCapture!!

        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                try {
                    val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
                    AppLogger.d(TAG, "【摄像计时】开始拍照 capture.takePicture...")

                    capture.takePicture(
                        outputOptions,
                        executor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                val captureEndTime = System.currentTimeMillis()
                                AppLogger.d(TAG, "【摄像计时】拍照完成耗时: ${captureEndTime - takePictureStartTime}ms")

                                val savedUri = output.savedUri
                                val path = savedUri?.path ?: outputFile.absolutePath

                                // Check file size
                                val file = File(path)
                                val fileSize = if (file.exists()) file.length() else 0
                                AppLogger.d(TAG, "【摄像计时】照片大小: $fileSize bytes")

                                // Rotate image if needed (for back camera, usually need to rotate 90 degrees)
                                val rotateStartTime = System.currentTimeMillis()
                                try {
                                    if (fileSize > 0) {
                                        rotateImageIfNeeded(path)
                                    }
                                } catch (e: Exception) {
                                    AppLogger.w(TAG, "Failed to rotate image", e)
                                }
                                AppLogger.d(TAG, "【摄像计时】旋转图片耗时: ${System.currentTimeMillis() - rotateStartTime}ms")

                                // NOTE: Removed rebindCamera() - it causes issues on car head unit cameras
                                // (Camera access permission lost mid-operation)
                                // The rebind was originally for Pixel devices but breaks car cameras

                                val totalTime = System.currentTimeMillis() - takePictureStartTime
                                AppLogger.d(TAG, "【摄像计时】takePicture总耗时: $totalTime ms")

                                continuation.resume(path)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                AppLogger.e(TAG, "【摄像计时】Photo capture failed: ${exception.message}", exception)
                                // Do NOT rebind camera on error - it causes issues on car head units
                                continuation.resume(null)
                            }
                        }
                    )
                } catch (e: Exception) {
                    AppLogger.e(TAG, "【摄像计时】Exception during takePicture", e)
                    continuation.resume(null)
                }
            }
        }.also { result ->
            if (result == null) {
                AppLogger.e(TAG, "【摄像计时】takePicture 超时 ($timeoutMs ms) 或失败")
            }
        }
    }

    /**
     * Rebind camera to reset state (fixes Pixel camera driver issues with rapid captures)
     */
    private fun rebindCamera() {
        try {
            // Must run on main thread for CameraX operations
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            mainHandler.post {
                val rebindStartTime = System.currentTimeMillis()
                try {
                    val owner = lifecycleOwner
                    if (owner == null) {
                        AppLogger.e(TAG, "lifecycleOwner is null, cannot rebind camera")
                        return@post
                    }
                    val cameraSelector = CameraSelector.Builder()
                        .requireLensFacing(currentLensFacing)
                        .build()

                    // Unbind all use cases
                    cameraProvider?.unbindAll()

                    // Create new ImageCapture instance
                    // Using 640x480 for faster upload and AI processing
                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetResolution(android.util.Size(640, 480))
                        .setTargetRotation(android.view.Surface.ROTATION_0)
                        .build()

                    // Rebind to lifecycle
                    cameraProvider?.bindToLifecycle(
                        owner,
                        cameraSelector,
                        imageCapture
                    )

                    AppLogger.d(TAG, "【摄像计时】Camera rebound successfully, 耗时: ${System.currentTimeMillis() - rebindStartTime}ms")
                } catch (e: Exception) {
                    AppLogger.e(TAG, "【摄像计时】Failed to rebind camera on main thread", e)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "【摄像计时】Failed to post rebind camera task", e)
        }
    }

    /**
     * Rotate image to correct orientation
     */
    private fun rotateImageIfNeeded(imagePath: String) {
        val file = File(imagePath)
        if (!file.exists()) {
            AppLogger.w(TAG, "Image file does not exist for rotation: $imagePath")
            return
        }

        val bitmap = BitmapFactory.decodeFile(imagePath)
        if (bitmap == null) {
            AppLogger.w(TAG, "Failed to decode bitmap from: $imagePath")
            return
        }

        AppLogger.d(TAG, "Original bitmap size: ${bitmap.width}x${bitmap.height}")

        // For back camera, rotate 90 degrees to get correct orientation
        val matrix = Matrix()
        matrix.postRotate(90f)

        val rotatedBitmap = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )

        AppLogger.d(TAG, "Rotated bitmap size: ${rotatedBitmap.width}x${rotatedBitmap.height}")

        // Save rotated image
        file.outputStream().use { output ->
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        }

        // Recycle bitmaps
        if (rotatedBitmap != bitmap) {
            bitmap.recycle()
        }
        rotatedBitmap.recycle()

        AppLogger.d(TAG, "Image rotated and saved: $imagePath")
    }

    /**
     * Release camera resources
     */
    fun release() {
        AppLogger.d(TAG, "release() called, isInitialized=$isInitialized")

        // 立即同步设置状态，防止后续调用误判
        isInitialized = false

        try {
            // CameraX operations must run on main thread
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            mainHandler.post {
                try {
                    // Unbind all use cases first
                    cameraProvider?.unbindAll()
                    AppLogger.d(TAG, "Unbound all use cases")

                    // Destroy lifecycle owner
                    lifecycleOwner?.markDestroyed()
                    lifecycleOwner = null

                    // Clear references
                    cameraProvider = null
                    imageCapture = null
                    isInitialized = false

                    AppLogger.d(TAG, "Camera resources fully released")
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error releasing camera resources on main thread", e)
                }
            }

            // Shutdown executor (can run on any thread)
            cameraExecutor?.shutdown()
            cameraExecutor?.awaitTermination(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
            cameraExecutor = null
            AppLogger.d(TAG, "Executor shutdown completed")

        } catch (e: Exception) {
            AppLogger.e(TAG, "Error releasing camera resources", e)
        }
    }

    /**
     * Check if camera is initialized and ready
     */
    fun isReady(): Boolean = isInitialized && imageCapture != null
}