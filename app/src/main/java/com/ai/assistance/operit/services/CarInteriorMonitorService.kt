package com.ai.assistance.operit.services

import android.content.Context
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.api.chat.enhance.MultiServiceManager
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.*

class CarInteriorMonitorService private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CarInteriorMonitor"
        private const val CAPTURE_INTERVAL_MS = 0L
        private const val MONITORING_DURATION_MS = 1 * 60 * 1000L
        // AI识图超时时间，超时后跳过本次检测继续下一次
        private const val AI_TIMEOUT_MS = 60 * 1000L

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

    private val cameraService: CameraCaptureService by lazy { CameraCaptureService.getInstance(context) }
    private val feishuService: FeishuServiceManager by lazy { FeishuServiceManager.getInstance(context) }
    private var currentMultiServiceManager: MultiServiceManager? = null

    suspend fun startMonitoring(chatId: String): Boolean {
        AppLogger.d(TAG, "startMonitoring called, chatId=$chatId")
        stopMonitoring()

        if (!cameraService.hasCameraPermission()) {
            AppLogger.e(TAG, "Camera permission not granted")
            feishuService.sendMessage(chatId, "❌ 无法启动车内检测：缺少摄像头权限，请在设置中授权。")
            return false
        }

        val hasImageRecognition = try {
            MultiServiceManager(context).hasImageRecognitionConfigured()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check image recognition config", e)
            false
        }

        if (!hasImageRecognition) {
            AppLogger.e(TAG, "Image recognition not configured")
            feishuService.sendMessage(chatId, "❌ 无法启动车内检测：识图功能未配置。请在设置中为「图像识别」功能配置支持图片理解的模型。")
            return false
        }

        val initialized = cameraService.initialize()
        if (!initialized) {
            AppLogger.e(TAG, "Failed to initialize camera")
            feishuService.sendMessage(chatId, "❌ 无法启动车内检测：摄像头初始化失败。")
            return false
        }

        // 预热 AI 服务，避免第一次检测时创建服务实例和网络连接的延迟
        try {
            AppLogger.d(TAG, "预热 AI 识图服务...")
            val multiServiceManager = MultiServiceManager(context)
            val imageService = multiServiceManager.getServiceForFunction(FunctionType.IMAGE_RECOGNITION)
            // 保存引用，用于超时时取消请求
            currentMultiServiceManager = multiServiceManager

            // 发送一个简单的预热请求，建立网络连接池（缩短超时时间到10秒）
            AppLogger.d(TAG, "发送预热请求建立网络连接...")
            val warmupJob = serviceScope.async {
                val warmupResult = StringBuilder()
                imageService.sendMessage(
                    context = context,
                    message = "预热",
                    chatHistory = emptyList(),
                    modelParameters = emptyList(),
                    enableThinking = false
                ).collect { chunk ->
                    warmupResult.append(chunk)
                }
            }
            // 预热最多等待10秒，超时则取消并继续（不阻塞主流程）
            withTimeoutOrNull(10_000L) {
                warmupJob.await()
            }
            if (warmupJob.isActive) {
                AppLogger.d(TAG, "预热请求超时，取消并继续")
                warmupJob.cancel()
            }
            AppLogger.d(TAG, "AI 识图服务预热完成，网络连接已建立")
        } catch (e: Exception) {
            AppLogger.e(TAG, "AI 服务预热失败，将使用实时初始化", e)
        }

        isMonitoring = true
        currentChatId = chatId
        feishuService.sendMessage(chatId, "🚗 车内检测已启动，持续1分钟，发现异常会自动告警")

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

    private suspend fun runMonitoringLoop(chatId: String) {
        val startTime = System.currentTimeMillis()
        var captureCount = 0
        var consecutiveFailures = 0
        val MAX_CONSECUTIVE_FAILURES = 3
        val findings = mutableListOf<String>()

        while (currentCoroutineContext().isActive &&
               isMonitoring &&
               cameraService.isReady() &&
               System.currentTimeMillis() - startTime < MONITORING_DURATION_MS) {

            // 检查是否被其他任务停止
            if (!isMonitoring || !cameraService.isReady()) {
                AppLogger.d(TAG, "监控已被停止或摄像头已释放，退出循环")
                return
            }

            captureCount++
            feishuService.sendMessage(chatId, "🔍 正在进行第${captureCount}次检测...")

            var imagePath: String? = null
            try {
                imagePath = cameraService.takePicture()
                if (imagePath == null) {
                    consecutiveFailures++
                    feishuService.sendMessage(chatId, "❌ 第${captureCount}次检测失败：拍照失败（连续${consecutiveFailures}次）")

                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        AppLogger.e(TAG, "连续${consecutiveFailures}次拍照失败，停止监控")
                        feishuService.sendMessage(chatId, "❌ 车内检测已中止\n📸 连续${consecutiveFailures}次拍照失败\n💡 建议检查摄像头或重启应用")
                        return
                    }
                    delay(CAPTURE_INTERVAL_MS)
                    continue
                }

                // 成功拍照，重置失败计数
                consecutiveFailures = 0

                // 保存图片数据，用于发送到飞书（无论结果如何都发送）
                val imageFile = java.io.File(imagePath)
                val imageData = if (imageFile.exists()) imageFile.readBytes() else null

                val result = analyzeImage(imagePath)

                // 处理AI超时情况
                if (result == "TIMEOUT") {
                    AppLogger.w(TAG, "第${captureCount}次检测AI响应超时(${AI_TIMEOUT_MS/1000}s)")
                    feishuService.sendMessage(chatId, "⏱️ 第${captureCount}次检测：网络超时，跳过本次检测")
                    // 超时时也发送图片
                    if (imageData != null) {
                        feishuService.sendImage(chatId, imageData)
                    }
                } else {
                    val cleanResult = extractActualResponse(result)

                    // 无论结果如何，都发送图片到飞书
                    if (imageData != null) {
                        feishuService.sendImage(chatId, imageData)
                    }

                    if (cleanResult.contains("车内发现")) {
                        findings.add(cleanResult)
                        feishuService.sendMessage(chatId, "⚠️ 第${captureCount}次检测结果：$cleanResult")
                    } else {
                        feishuService.sendMessage(chatId, "✅ 第${captureCount}次检测结果：正常")
                    }
                }
            } catch (e: Exception) {
                consecutiveFailures++
                AppLogger.e(TAG, "Error in capture/analyze cycle", e)
                feishuService.sendMessage(chatId, "❌ 第${captureCount}次检测发生错误：${e.message}（连续${consecutiveFailures}次）")

                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    AppLogger.e(TAG, "连续${consecutiveFailures}次检测错误，停止监控")
                    feishuService.sendMessage(chatId, "❌ 车内检测已中止\n📸 连续${consecutiveFailures}次检测错误\n💡 建议检查摄像头或重启应用")
                    return
                }
            } finally {
                imagePath?.let { try { java.io.File(it).delete() } catch (_: Exception) {} }
            }
            delay(CAPTURE_INTERVAL_MS)
        }

        if (currentCoroutineContext().isActive) {
            if (findings.isEmpty()) {
                feishuService.sendMessage(chatId, "✅ 车内检测完成\n📸 共检测 $captureCount 次\n🔍 一切正常")
            } else {
                feishuService.sendMessage(chatId, "⚠️ 车内检测完成\n📸 共检测 $captureCount 次\n🚨 发现异常：\n${findings.mapIndexed { i, f -> "${i+1}. $f" }.joinToString("\n")}")
            }
        }
        isMonitoring = false
    }

    private suspend fun analyzeImage(imagePath: String): String? {
        return try {
            val file = java.io.File(imagePath)
            if (!file.exists() || file.length() == 0L) return null

            // 使用 async 配合超时，超时后真正取消请求
            val analysisJob = serviceScope.async {
                EnhancedAIService.getInstance(context).analyzeImageWithIntent(imagePath, DETECTION_PROMPT)
            }

            // 等待结果，最多20秒
            val result = withTimeoutOrNull(AI_TIMEOUT_MS) {
                analysisJob.await()
            }

            if (result == null) {
                // 超时，取消协程并停止AI请求
                AppLogger.w(TAG, "AI分析超时，取消请求")
                analysisJob.cancel()
                // 取消正在进行的流式请求
                currentMultiServiceManager?.cancelAllStreaming()
                "TIMEOUT"
            } else {
                result
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to analyze image", e)
            null
        }
    }

    private fun extractActualResponse(result: String?): String {
        if (result.isNullOrBlank()) return ""
        var cleanResult: String = result

        // Remove thinking tags and analysis content
        val patterns = listOf(
            Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL),
            Regex("【.*?】", RegexOption.DOT_MATCHES_ALL),
            Regex("```[\\s\\S]*?```"),
            Regex("分析[:：][\\s\\S]*?(?=车内发现|无|$)"),
            Regex("首先[\\s\\S]*?(?=车内发现|无|$)"),
            Regex("让我[\\s\\S]*?(?=车内发现|无|$)"),
            Regex("根据[\\s\\S]*?(?=车内发现|无|$)")
        )
        for (p in patterns) cleanResult = p.replace(cleanResult, "")

        val linePatterns = listOf(
            Regex("^\\s*步骤.*$", RegexOption.MULTILINE),
            Regex("^\\s*\\d+\\.\\s*(分析|观察).*$", RegexOption.MULTILINE),
            Regex("^\\s*结论[:：].*$", RegexOption.MULTILINE)
        )
        for (p in linePatterns) cleanResult = p.replace(cleanResult, "")

        cleanResult = cleanResult.trim()
        for (line in cleanResult.lines().filter { it.isNotBlank() }) {
            val t = line.trim()
            if (t == "无") return "无"
            if (t.startsWith("车内发现") && t.contains("请检查下")) return t
        }
        return ""
    }

    fun stopMonitoring() {
        // 只有正在监控时才发送停止消息
        val wasMonitoring = isMonitoring
        monitorJob?.cancel()
        monitorJob = null
        isMonitoring = false
        // 取消正在进行的AI请求
        serviceScope.launch {
            currentMultiServiceManager?.cancelAllStreaming()
        }
        currentMultiServiceManager = null
        // 只有之前在监控才发送停止通知
        if (wasMonitoring) {
            currentChatId?.let { serviceScope.launch { feishuService.sendMessage(it, "⏹️ 车内检测已停止") } }
        }
        currentChatId = null
    }

    fun isMonitoring(): Boolean = isMonitoring && monitorJob?.isActive == true
    fun getCurrentChatId(): String? = currentChatId
}