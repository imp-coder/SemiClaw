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
import androidx.lifecycle.LifecycleOwner
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.OperitPaths
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * CameraX based camera capture service
 *
 * Provides simple camera capture functionality for taking photos
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
     * @param lifecycleOwner The lifecycle owner for camera operations
     * @param lensFacing Camera lens direction (default: back camera)
     * @return true if initialization succeeded
     */
    suspend fun initialize(
        lifecycleOwner: LifecycleOwner,
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

                        // Select camera
                        val cameraSelector = CameraSelector.Builder()
                            .requireLensFacing(lensFacing)
                            .build()

                        // Check if camera exists
                        val hasCamera = try {
                            cameraProvider?.hasCamera(cameraSelector) ?: false
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Failed to check camera availability", e)
                            true // Assume camera exists and try to bind anyway
                        }
                        AppLogger.d(TAG, "Has camera (lensFacing=$lensFacing): $hasCamera")

                        // Create image capture use case
                        imageCapture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .setTargetRotation(android.view.Surface.ROTATION_0)
                            .build()
                        AppLogger.d(TAG, "ImageCapture created")

                        // Unbind all use cases before binding
                        cameraProvider?.unbindAll()
                        AppLogger.d(TAG, "Unbound all previous use cases")

                        // Bind use cases to camera
                        cameraProvider?.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            imageCapture
                        )
                        AppLogger.d(TAG, "Camera bound to lifecycle")

                        isInitialized = true
                        AppLogger.d(TAG, "Camera initialized successfully!")
                        continuation.resume(true)

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
     * @return The file path of the captured image, or null if failed
     */
    suspend fun takePicture(): String? {
        AppLogger.d(TAG, "takePicture() called, isInitialized=$isInitialized, imageCapture=$imageCapture")

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
        AppLogger.d(TAG, "Output file: ${outputFile.absolutePath}")

        val capture = imageCapture!!

        return suspendCancellableCoroutine { continuation ->
            try {
                val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
                AppLogger.d(TAG, "Starting photo capture...")

                capture.takePicture(
                    outputOptions,
                    executor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            val savedUri = output.savedUri
                            val path = savedUri?.path ?: outputFile.absolutePath

                            AppLogger.d(TAG, "Photo saved successfully: $path")

                            // Check file size
                            val file = File(path)
                            val fileSize = if (file.exists()) file.length() else 0
                            AppLogger.d(TAG, "Photo file size: $fileSize bytes")

                            // Rotate image if needed (for back camera, usually need to rotate 90 degrees)
                            try {
                                if (fileSize > 0) {
                                    rotateImageIfNeeded(path)
                                }
                            } catch (e: Exception) {
                                AppLogger.w(TAG, "Failed to rotate image", e)
                            }

                            continuation.resume(path)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            AppLogger.e(TAG, "Photo capture failed: ${exception.message}", exception)
                            continuation.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Exception during takePicture", e)
                continuation.resume(null)
            }
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
        AppLogger.d(TAG, "release() called")
        try {
            cameraProvider?.unbindAll()
            cameraProvider = null
            imageCapture = null
            cameraExecutor?.shutdown()
            cameraExecutor = null
            isInitialized = false
            AppLogger.d(TAG, "Camera resources released")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error releasing camera resources", e)
        }
    }

    /**
     * Check if camera is initialized and ready
     */
    fun isReady(): Boolean = isInitialized && imageCapture != null
}