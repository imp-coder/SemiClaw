package com.ai.assistance.operit.services

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.api.chat.enhance.MultiServiceManager
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Car Interior Monitor Service
 *
 * Monitors car interior by periodically capturing photos and detecting
 * animals, people, or phones using AI image recognition.
 *
 * Features:
 * - Periodic photo capture (every 3 seconds)
 * - AI-powered image recognition
 * - Feishu alert notifications
 * - 1-minute auto-stop
 * - New command interruption support
 */
class CarInteriorMonitorService private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CarInteriorMonitor"

        // Detection interval in milliseconds
        private const val CAPTURE_INTERVAL_MS = 3000L

        // Monitoring duration in milliseconds (1 minute)
        private const val MONITORING_DURATION_MS = 1 * 60 * 1000L

        // Detection prompt for AI - strict format
        private const val DETECTION_PROMPT = """检测图片中是否有动物、人、手机。

严格按要求回复，不要有任何多余文字：
- 发现手机：回复"车内发现手机，请检查下"
- 发现人：回复"车内发现人，请检查下"
- 发现动物：回复"车内发现[具体动物名]，请检查下"
- 什么都没发现：回复"无"

只回复上面指定的内容，不要解释，不要分析过程。"""

        @Volatile
        private var INSTANCE: CarInteriorMonitorService? = null

        fun getInstance(context: Context): CarInteriorMonitorService {
            return INSTANCE ?: synchronized(this) {
                val instance = CarInteriorMonitorService(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var monitorJob: Job? = null
    private var isMonitoring = false
    private var currentChatId: String? = null
    private var lifecycleOwner: LifecycleOwner? = null

    private val cameraService: CameraCaptureService by lazy {
        CameraCaptureService.getInstance(context)
    }

    private val feishuService: FeishuServiceManager by lazy {
        FeishuServiceManager.getInstance(context)
    }

    /**
     * Start monitoring
     *
     * @param chatId The Feishu chat ID to send alerts to
     * @param owner The lifecycle owner for camera operations (required)
     * @return true if monitoring started successfully
     */
    suspend fun startMonitoring(
        chatId: String,
        owner: LifecycleOwner
    ): Boolean {
        AppLogger.d(TAG, "startMonitoring called, chatId=$chatId")

        // Stop any existing monitoring
        stopMonitoring()

        // Store lifecycle owner
        lifecycleOwner = owner

        // Check camera permission
        if (!cameraService.hasCameraPermission()) {
            AppLogger.e(TAG, "Camera permission not granted")
            feishuService.sendMessage(chatId, "❌ 无法启动车内检测：缺少摄像头权限，请在设置中授权。")
            return false
        }

        // Check if image recognition is configured
        val hasImageRecognition = try {
            val multiServiceManager = MultiServiceManager(context)
            multiServiceManager.hasImageRecognitionConfigured()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check image recognition config", e)
            false
        }

        if (!hasImageRecognition) {
            AppLogger.e(TAG, "Image recognition not configured")
            feishuService.sendMessage(
                chatId,
                "❌ 无法启动车内检测：识图功能未配置。\n\n" +
                "请在设置中配置识图模型：\n" +
                "1. 打开应用设置\n" +
                "2. 找到「功能模型配置」\n" +
                "3. 为「图像识别」功能选择支持图片理解的模型\n" +
                "4. 确保模型启用「直接图片处理」选项"
            )
            return false
        }

        // Initialize camera
        val initialized = cameraService.initialize(owner)
        if (!initialized) {
            AppLogger.e(TAG, "Failed to initialize camera")
            feishuService.sendMessage(chatId, "❌ 无法启动车内检测：摄像头初始化失败。")
            return false
        }

        isMonitoring = true
        currentChatId = chatId

        // Send start notification
        feishuService.sendMessage(chatId, "🚗 车内检测已启动，持续1分钟，发现异常会自动告警")

        // Start monitoring loop
        monitorJob = serviceScope.launch {
            try {
                runMonitoringLoop(chatId)
            } catch (e: CancellationException) {
                AppLogger.d(TAG, "Monitoring cancelled")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Monitoring error", e)
                feishuService.sendMessage(chatId, "❌ 车内检测发生错误：${e.message}")
            } finally {
                isMonitoring = false
                cameraService.release()
            }
        }

        return true
    }

    /**
     * Run the monitoring loop
     */
    private suspend fun runMonitoringLoop(chatId: String) {
        val startTime = System.currentTimeMillis()
        var captureCount = 0
        val findings = mutableListOf<String>() // Record all findings

        AppLogger.d(TAG, "Starting monitoring loop")

        while (currentCoroutineContext().isActive && System.currentTimeMillis() - startTime < MONITORING_DURATION_MS) {
            captureCount++

            // Send progress message before detection
            feishuService.sendMessage(chatId, "🔍 正在进行第${captureCount}次检测...")

            var imagePath: String? = null
            try {
                // Take photo
                AppLogger.d(TAG, "Taking photo #$captureCount")
                imagePath = cameraService.takePicture()

                if (imagePath == null) {
                    AppLogger.w(TAG, "Failed to capture photo #$captureCount")
                    feishuService.sendMessage(chatId, "❌ 第${captureCount}次检测失败：拍照失败")
                    delay(CAPTURE_INTERVAL_MS)
                    continue
                }

                AppLogger.d(TAG, "Photo captured: $imagePath")

                // Analyze image with AI
                AppLogger.d(TAG, "Analyzing image: $imagePath")
                val result = analyzeImage(imagePath)
                AppLogger.d(TAG, "Analysis result: $result")

                // Extract actual response (remove thinking process)
                val cleanResult = extractActualResponse(result)
                AppLogger.d(TAG, "Clean result: $cleanResult")

                // Check if AI found something - look for positive indicator "车内发现"
                val foundSomething = cleanResult.contains("车内发现")

                if (foundSomething) {
                    // Found something - send photo and alert to Feishu
                    findings.add(cleanResult)

                    try {
                        val imageFile = java.io.File(imagePath)
                        if (imageFile.exists()) {
                            val imageData = imageFile.readBytes()
                            // Send photo
                            feishuService.sendImage(chatId, imageData)
                            // Send alert (use clean result)
                            feishuService.sendMessage(chatId, "⚠️ 第${captureCount}次检测结果：$cleanResult")
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Error sending alert to Feishu", e)
                    }
                } else {
                    // Normal - send result message
                    feishuService.sendMessage(chatId, "✅ 第${captureCount}次检测结果：正常")
                }

            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in capture/analyze cycle", e)
                feishuService.sendMessage(chatId, "❌ 第${captureCount}次检测发生错误：${e.message}")
            } finally {
                // Clean up temp image
                imagePath?.let { path ->
                    try {
                        java.io.File(path).delete()
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Failed to delete temp image", e)
                    }
                }
            }

            // Wait for next capture
            delay(CAPTURE_INTERVAL_MS)
        }

        // Monitoring completed - send summary
        val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000
        AppLogger.d(TAG, "Monitoring completed: ${captureCount} captures, ${findings.size} findings")

        if (currentCoroutineContext().isActive) {
            if (findings.isEmpty()) {
                feishuService.sendMessage(
                    chatId,
                    "✅ 车内检测完成\n" +
                    "📸 共检测 $captureCount 次\n" +
                    "🔍 一切正常，未发现异常"
                )
            } else {
                val findingsSummary = findings.mapIndexed { i, f -> "${i + 1}. $f" }.joinToString("\n")
                feishuService.sendMessage(
                    chatId,
                    "⚠️ 车内检测完成\n" +
                    "📸 共检测 $captureCount 次\n" +
                    "🚨 发现异常：\n$findingsSummary"
                )
            }
        }

        isMonitoring = false
    }

    /**
     * Analyze image using AI service
     */
    private suspend fun analyzeImage(imagePath: String): String? {
        return try {
            AppLogger.d(TAG, "analyzeImage: Starting analysis for $imagePath")

            // Check if file exists
            val file = java.io.File(imagePath)
            if (!file.exists()) {
                AppLogger.e(TAG, "analyzeImage: Image file does not exist: $imagePath")
                return null
            }

            val fileSize = file.length()
            AppLogger.d(TAG, "analyzeImage: Image file size: $fileSize bytes")

            if (fileSize == 0L) {
                AppLogger.e(TAG, "analyzeImage: Image file is empty")
                return null
            }

            val aiService = EnhancedAIService.getInstance(context)
            val result = aiService.analyzeImageWithIntent(imagePath, DETECTION_PROMPT)

            AppLogger.d(TAG, "analyzeImage: Result length: ${result?.length ?: 0}")
            AppLogger.d(TAG, "analyzeImage: Result preview: ${result?.take(200)}")

            result
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to analyze image", e)
            null
        }
    }

    /**
     * Extract actual response from AI result
     * Remove thinking process and extra content aggressively
     */
    private fun extractActualResponse(result: String?): String {
        if (result.isNullOrBlank()) return ""

        var cleanResult: String = result

        // Remove common thinking/reasoning tags
        // DeepSeek-R1 uses specific tags
        val thinkingPatterns = listOf(
            Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL),
            Regex("【.*?】", RegexOption.DOT_MATCHES_ALL),
            Regex("```[\\s\\S]*?```"),
            Regex("分析[:：][\\s\\S]*?(?=车内发现|无|$)"),
            Regex("首先[\\s\\S]*?(?=车内发现|无|$)"),
            Regex("让我[\\s\\S]*?(?=车内发现|无|$)"),
            Regex("根据[\\s\\S]*?(?=车内发现|无|$)"),
            Regex("观察[\\s\\S]*?(?=车内发现|无|$)"),
            Regex("图片中[^车]*?(?=车内发现|无|$)")
        )

        for (pattern in thinkingPatterns) {
            cleanResult = pattern.replace(cleanResult, "")
        }

        // Remove lines with specific analysis keywords
        val analysisLinePatterns = listOf(
            Regex("^\\s*步骤.*$", RegexOption.MULTILINE),
            Regex("^\\s*\\d+\\.\\s*(分析|观察|检查).*$", RegexOption.MULTILINE),
            Regex("^\\s*-\\s*(分析|观察|检查).*$", RegexOption.MULTILINE),
            Regex("^\\s*结论[:：].*$", RegexOption.MULTILINE),
            Regex("^━━+.*$", RegexOption.MULTILINE),
            Regex("^\\s*请及时检查.*$", RegexOption.MULTILINE),
            Regex("^\\s*检测时间.*$", RegexOption.MULTILINE)
        )

        for (pattern in analysisLinePatterns) {
            cleanResult = pattern.replace(cleanResult, "")
        }

        // Trim whitespace
        cleanResult = cleanResult.trim()

        // Get all non-empty lines
        val lines = cleanResult.lines().filter { it.isNotBlank() }

        // Find exact match for expected format
        for (line in lines) {
            val trimmed = line.trim()
            // Exact match for "无"
            if (trimmed == "无") {
                return "无"
            }
            // Exact match for detection format
            if (trimmed.startsWith("车内发现") && trimmed.endsWith("请检查下")) {
                return trimmed
            }
        }

        // If no exact match, try to find the detection statement
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.contains("车内发现") && trimmed.contains("请检查下")) {
                // Extract just the core statement
                val match = Regex("车内发现[^，,。]*请检查下").find(trimmed)
                if (match != null) {
                    return match.value
                }
                // If pattern doesn't match, try simpler extraction
                val startIdx = trimmed.indexOf("车内发现")
                val endIdx = trimmed.indexOf("请检查下") + 4
                if (startIdx >= 0 && endIdx > startIdx) {
                    return trimmed.substring(startIdx, endIdx)
                }
            }
        }

        // If nothing found, return empty to avoid sending garbage
        return ""
    }

    /**
     * Stop monitoring
     */
    fun stopMonitoring() {
        AppLogger.d(TAG, "stopMonitoring called")

        monitorJob?.cancel()
        monitorJob = null
        isMonitoring = false

        // Send stop notification if there was an active monitoring
        currentChatId?.let { chatId ->
            serviceScope.launch {
                feishuService.sendMessage(chatId, "⏹️ 车内检测已停止")
            }
        }
        currentChatId = null
        lifecycleOwner = null
    }

    /**
     * Check if monitoring is active
     */
    fun isMonitoring(): Boolean = isMonitoring && monitorJob?.isActive == true

    /**
     * Get current monitoring chat ID
     */
    fun getCurrentChatId(): String? = currentChatId
}