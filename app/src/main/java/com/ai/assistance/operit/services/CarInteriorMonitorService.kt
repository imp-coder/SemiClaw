package com.ai.assistance.operit.services

import android.content.Context
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.api.chat.enhance.MultiServiceManager
import com.ai.assistance.operit.core.tools.ToolProgressNotifier
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.*

class CarInteriorMonitorService private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CarInteriorMonitor"
        private const val CAPTURE_INTERVAL_MS = 0L
        // 监控持续时间：设为0表示只检测一次
        private const val MONITORING_DURATION_MS = 0L
        // AI识图超时时间，超时后跳过本次检测继续下一次
        private const val AI_TIMEOUT_MS = 60 * 1000L

        private const val DETECTION_PROMPT = """检测图片中是否有以下物品：
- 人（乘客、司机等）
- 动物（宠物如猫、狗等）
- 手机
- 钱包
- 钥匙
- 耳机（有线耳机、蓝牙耳机等）
- 背包（书包、手提包等）

严格按要求回复，不要有任何多余文字：
- 发现手机：回复"车内发现手机，请检查下"
- 发现人：回复"车内发现人，请检查下"
- 发现动物：回复"车内发现[具体动物名]，请检查下"
- 发现钱包：回复"车内发现钱包，请检查下"
- 发现钥匙：回复"车内发现钥匙，请检查下"
- 发现耳机：回复"车内发现耳机，请检查下"
- 发现背包：回复"车内发现背包，请检查下"
- 发现多个物品：回复"车内发现[物品1]、[物品2]，请检查下"
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
            val errorMsg = "❌ 无法启动车内检测：缺少摄像头权限，请在设置中授权。"
            feishuService.sendMessage(chatId, errorMsg)
            ToolProgressNotifier.notifyError(context, "car_detect", errorMsg)
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
            val errorMsg = "❌ 无法启动车内检测：识图功能未配置。请在设置中为「图像识别」功能配置支持图片理解的模型。"
            feishuService.sendMessage(chatId, errorMsg)
            ToolProgressNotifier.notifyError(context, "car_detect", errorMsg)
            return false
        }

        val initialized = cameraService.initialize()
        if (!initialized) {
            AppLogger.e(TAG, "Failed to initialize camera")
            val errorMsg = "❌ 无法启动车内检测：摄像头初始化失败。"
            feishuService.sendMessage(chatId, errorMsg)
            ToolProgressNotifier.notifyError(context, "car_detect", errorMsg)
            return false
        }

        ToolProgressNotifier.notifyStart(context, "car_detect", "🚗 车内检测已启动，发现异常会自动告警")
        feishuService.sendMessage(chatId, "🚗 车内检测已启动，发现异常会自动告警")

        isMonitoring = true
        currentChatId = chatId

        monitorJob = serviceScope.launch {
            try {
                runMonitoringLoop(chatId)
            } catch (e: CancellationException) {
                AppLogger.d(TAG, "Monitoring cancelled")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Monitoring error", e)
                val errorMsg = "❌ 车内检测发生错误：${e.message}"
                ToolProgressNotifier.notifyError(context, "car_detect", errorMsg)
                feishuService.sendMessage(chatId, errorMsg)
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
        var totalFailures = 0  // 总失败次数
        var totalSuccess = 0   // 总成功次数
        val MAX_CONSECUTIVE_FAILURES = 3
        val findings = mutableListOf<String>()

        // 使用 do-while 确保至少执行一次检测
        do {

            // 检查是否被其他任务停止
            if (!isMonitoring || !cameraService.isReady()) {
                AppLogger.d(TAG, "监控已被停止或摄像头已释放，退出循环")
                return
            }

            captureCount++
            ToolProgressNotifier.notifyInProgress(context, "car_detect", "🔍 正在进行第${captureCount}次检测...")
            feishuService.sendMessage(chatId, "🔍 正在进行第${captureCount}次检测...")

            var imagePath: String? = null
            try {
                // 显示拍照状态
                ToolProgressNotifier.notifyInProgress(context, "car_detect", "📸 正在拍照获取图像...")
                feishuService.sendMessage(chatId, "📸 正在拍照获取图像...")
                imagePath = cameraService.takePicture()
                if (imagePath == null) {
                    consecutiveFailures++
                    totalFailures++
                    val errorMsg = "❌ 第${captureCount}次检测失败：拍照失败（连续${consecutiveFailures}次）"
                    ToolProgressNotifier.notifyInProgress(context, "car_detect", errorMsg)
                    feishuService.sendMessage(chatId, errorMsg)

                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        AppLogger.e(TAG, "连续${consecutiveFailures}次拍照失败，停止监控")
                        val errorMsg = "❌ 车内检测已中止\n📸 连续${consecutiveFailures}次拍照失败\n💡 建议检查摄像头或重启应用"
                        ToolProgressNotifier.notifyError(context, "car_detect", errorMsg)
                        feishuService.sendMessage(chatId, errorMsg)
                        return
                    }
                    delay(CAPTURE_INTERVAL_MS)
                    continue
                }

                // 成功拍照，重置失败计数
                consecutiveFailures = 0
                totalSuccess++

                // 保存图片数据，用于发送到飞书（无论结果如何都发送）
                val imageFile = java.io.File(imagePath)
                val imageData = if (imageFile.exists()) imageFile.readBytes() else null

                // 显示分析状态
                ToolProgressNotifier.notifyInProgress(context, "car_detect", "🤖 正在调用AI模型分析图像...")
                feishuService.sendMessage(chatId, "🤖 正在调用AI模型分析图像...")
                ToolProgressNotifier.notifyInProgress(context, "car_detect", "📊 正在进行物体识别...")
                feishuService.sendMessage(chatId, "📊 正在进行物体识别...")

                val result = analyzeImage(imagePath)

                // 处理AI超时情况
                if (result == "TIMEOUT") {
                    totalFailures++
                    AppLogger.w(TAG, "第${captureCount}次检测AI响应超时(${AI_TIMEOUT_MS/1000}s)")
                    val timeoutMsg = "⏱️ 第${captureCount}次检测：网络超时，跳过本次检测"
                    ToolProgressNotifier.notifyInProgress(context, "car_detect", timeoutMsg)
                    feishuService.sendMessage(chatId, timeoutMsg)
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
                        val resultMsg = "⚠️ 第${captureCount}次检测结果：$cleanResult"
                        ToolProgressNotifier.notifyInProgress(context, "car_detect", resultMsg)
                        feishuService.sendMessage(chatId, resultMsg)
                    } else {
                        val normalMsg = "✅ 第${captureCount}次检测结果：正常"
                        ToolProgressNotifier.notifyInProgress(context, "car_detect", normalMsg)
                        feishuService.sendMessage(chatId, normalMsg)
                    }
                }
            } catch (e: Exception) {
                consecutiveFailures++
                totalFailures++
                AppLogger.e(TAG, "Error in capture/analyze cycle", e)
                val errorMsg = "❌ 第${captureCount}次检测发生错误：${e.message}（连续${consecutiveFailures}次）"
                ToolProgressNotifier.notifyInProgress(context, "car_detect", errorMsg)
                feishuService.sendMessage(chatId, errorMsg)

                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    AppLogger.e(TAG, "连续${consecutiveFailures}次检测错误，停止监控")
                    val abortMsg = "❌ 车内检测已中止\n📸 连续${consecutiveFailures}次检测错误\n💡 建议检查摄像头或重启应用"
                    ToolProgressNotifier.notifyError(context, "car_detect", abortMsg)
                    feishuService.sendMessage(chatId, abortMsg)
                    return
                }
            } finally {
                imagePath?.let { try { java.io.File(it).delete() } catch (_: Exception) {} }
            }
            delay(CAPTURE_INTERVAL_MS)

            // 如果 MONITORING_DURATION_MS = 0，只检测一次就退出
            if (MONITORING_DURATION_MS == 0L) break

        } while (currentCoroutineContext().isActive &&
                 isMonitoring &&
                 cameraService.isReady() &&
                 System.currentTimeMillis() - startTime < MONITORING_DURATION_MS)

        if (currentCoroutineContext().isActive) {
            // 构建汇总消息
            val summaryBuilder = StringBuilder()
            summaryBuilder.append("📋 车内检测完成\n")
            summaryBuilder.append("📸 共尝试检测 $captureCount 次\n")
            summaryBuilder.append("✅ 成功检测 $totalSuccess 次\n")
            if (totalFailures > 0) {
                summaryBuilder.append("❌ 失败检测 $totalFailures 次\n")
            }

            if (totalSuccess == 0) {
                // 全部失败
                summaryBuilder.append("⚠️ 所有检测均失败，无法完成车内检测\n")
                summaryBuilder.append("💡 建议检查摄像头权限或重启应用")
            } else if (findings.isEmpty()) {
                // 有成功检测但没发现问题
                summaryBuilder.append("🔍 检测结果：一切正常")
            } else {
                // 发现异常
                summaryBuilder.append("🚨 发现异常：\n")
                summaryBuilder.append(findings.mapIndexed { i, f -> "${i+1}. $f" }.joinToString("\n"))
            }

            val summaryMsg = summaryBuilder.toString()
            if (totalSuccess > 0 && findings.isEmpty()) {
                ToolProgressNotifier.notifySuccess(context, "car_detect", summaryMsg)
            } else if (totalSuccess == 0) {
                ToolProgressNotifier.notifyError(context, "car_detect", summaryMsg)
            } else {
                ToolProgressNotifier.notifyInProgress(context, "car_detect", summaryMsg)
            }
            feishuService.sendMessage(chatId, summaryMsg)
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
            currentChatId?.let {
                serviceScope.launch {
                    val stopMsg = "⏹️ 车内检测已停止"
                    ToolProgressNotifier.notifyInProgress(context, "car_detect", stopMsg)
                    feishuService.sendMessage(it, stopMsg)
                }
            }
        }
        currentChatId = null
    }

    fun isMonitoring(): Boolean = isMonitoring && monitorJob?.isActive == true
    fun getCurrentChatId(): String? = currentChatId
}