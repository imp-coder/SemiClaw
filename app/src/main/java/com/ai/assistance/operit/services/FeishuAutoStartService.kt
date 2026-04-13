package com.ai.assistance.operit.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.PromptFunctionType
import com.ai.assistance.operit.data.preferences.FeishuPreferences
import com.ai.assistance.operit.services.core.ChatSelectionMode
import com.ai.assistance.operit.ui.main.MainActivity
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.OperitPaths
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * 飞书自动启动服务
 *
 * 在应用启动时自动启动飞书 WebSocket 连接，无需用户手动开启
 */
class FeishuAutoStartService : Service() {

    companion object {
        private const val TAG = "FeishuAutoStartService"
        private const val NOTIFICATION_CHANNEL_ID = "feishu_auto_start_channel"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        private var isRunning = false

        fun isServiceRunning(): Boolean = isRunning

        /**
         * 启动服务
         */
        fun start(context: Context) {
            val intent = Intent(context, FeishuAutoStartService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 停止服务
         */
        fun stop(context: Context) {
            val intent = Intent(context, FeishuAutoStartService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var chatCore: ChatServiceCore
    private lateinit var feishuPreferences: FeishuPreferences
    private val binder = LocalBinder()

    // 已处理的消息ID列表（用于去重）
    private val processedMessageIds = mutableListOf<String>()

    inner class LocalBinder : Binder() {
        fun getService(): FeishuAutoStartService = this@FeishuAutoStartService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        AppLogger.d(TAG, "飞书自动启动服务 onCreate")
        isRunning = true

        // 创建通知渠道
        createNotificationChannel()

        // 启动前台服务通知
        startForeground(NOTIFICATION_ID, createNotification())

        // 初始化聊天核心
        chatCore = ChatServiceCore(
            context = applicationContext,
            coroutineScope = serviceScope,
            selectionMode = ChatSelectionMode.FOLLOW_GLOBAL
        )

        feishuPreferences = FeishuPreferences.getInstance(applicationContext)

        // 初始化飞书服务
        initFeishuService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.d(TAG, "飞书自动启动服务 onStartCommand")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.d(TAG, "飞书自动启动服务 onDestroy")
        isRunning = false

        // 停止飞书服务
        try {
            FeishuServiceManager.getInstance(applicationContext).stop()
        } catch (e: Exception) {
            AppLogger.e(TAG, "停止飞书服务失败", e)
        }

        serviceScope.cancel()
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.feishu_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.feishu_service_channel_description)
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建前台服务通知
     */
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.feishu_service_notification_title))
            .setContentText(getString(R.string.feishu_service_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * 初始化飞书服务
     */
    private fun initFeishuService() {
        AppLogger.d(TAG, "开始初始化飞书服务...")
        serviceScope.launch {
            try {
                // 自动启用飞书开关（如果配置已存在）
                val config = feishuPreferences.getFeishuConfig()
                if (config.isConfigured()) {
                    AppLogger.d(TAG, "飞书配置已存在，自动启用飞书集成")
                    feishuPreferences.saveFeishuEnabled(true)
                }

                val feishuService = FeishuServiceManager.getInstance(applicationContext)

                // 设置消息处理器
                feishuService.setMessageHandler { message ->
                    handleFeishuMessage(message)
                }

                AppLogger.d(TAG, "正在启动飞书 WebSocket 连接...")
                // 启动服务
                val started = feishuService.start()
                if (started) {
                    AppLogger.d(TAG, "飞书服务启动成功，WebSocket 已连接")
                } else {
                    AppLogger.w(TAG, "飞书服务未启动（请检查飞书配置）")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "初始化飞书服务失败", e)
            }
        }
    }

    /**
     * 处理飞书消息
     */
    private suspend fun handleFeishuMessage(message: FeishuIncomingMessage): String? {
        val feishuChatId = message.chatId ?: return null

        // ========== 消息去重 ==========
        val messageId = message.messageId ?: ""
        if (messageId.isNotEmpty() && processedMessageIds.contains(messageId)) {
            AppLogger.d(TAG, "消息已处理过，跳过重复消息: messageId=$messageId")
            return null
        }
        // 记录已处理的消息ID（保留最近100条）
        if (messageId.isNotEmpty()) {
            processedMessageIds.add(messageId)
            if (processedMessageIds.size > 100) {
                processedMessageIds.removeAt(0)
            }
        }

        // 获取文本内容（支持普通文本和富文本）
        val userMessage = if (message.msgType == "post") {
            message.getPostTextContent()
        } else {
            message.getTextContent()
        }

        AppLogger.d(TAG, "========== 开始处理飞书消息 ==========")
        AppLogger.d(TAG, "feishuChatId=$feishuChatId, msgType=${message.msgType}")
        AppLogger.d(TAG, "userMessage=${userMessage.take(100)}")

        val feishuService = FeishuServiceManager.getInstance(applicationContext)

        // ========== 处理图片消息 ==========
        val hasImage = message.isImageMessage()
        val imageKey = if (hasImage) message.getImageKey() else null

        AppLogger.d(TAG, "hasImage=$hasImage, imageKey=$imageKey")

        if (!imageKey.isNullOrBlank()) {
            AppLogger.d(TAG, "检测到图片，imageKey: $imageKey, messageId: ${message.messageId}")

            // 检查是否是设置壁纸命令
            val isWallpaperCommand = userMessage.contains("壁纸") ||
                    userMessage.contains("wallpaper") ||
                    userMessage.contains("背景")

            if (isWallpaperCommand) {
                feishuService.sendMessage(feishuChatId, "正在设置壁纸...")

                try {
                    val feishuClient = FeishuClient(applicationContext)
                    val config = feishuPreferences.getFeishuConfig()

                    val result = com.ai.assistance.operit.util.WallpaperUtil.setWallpaperFromFeishu(
                        applicationContext,
                        feishuClient,
                        config,
                        imageKey,
                        message.messageId
                    )

                    if (result.isSuccess) {
                        feishuService.sendMessage(feishuChatId, "壁纸设置成功！")
                    } else {
                        val error = result.exceptionOrNull()?.message ?: "未知错误"
                        feishuService.sendMessage(feishuChatId, "壁纸设置失败: $error")
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "设置壁纸异常", e)
                    feishuService.sendMessage(feishuChatId, "壁纸设置失败: ${e.message}")
                }

                return null
            } else {
                feishuService.sendMessage(feishuChatId, "收到图片。如需设置为壁纸，请发送图片时附带说明\"设置壁纸\"。")
                return null
            }
        }

        // ========== 处理文本消息 ==========
        if (userMessage.isBlank()) return null

        // ========== 车内检测命令 ==========
        val isCarMonitorCommand = userMessage.contains("检测车内") ||
                userMessage.contains("车内检测") ||
                userMessage.contains("监控车内") ||
                userMessage.contains("开始车内监控")

        if (isCarMonitorCommand) {
            AppLogger.d(TAG, "检测到车内检测命令")

            // 先取消之前的消息处理（包括AI对话）
            try {
                chatCore.cancelCurrentMessage()
            } catch (e: Exception) {
                AppLogger.w(TAG, "取消之前消息处理失败", e)
            }

            val monitorService = CarInteriorMonitorService.getInstance(applicationContext)

            // 先停止之前的监控（startMonitoring 内部也会调用，但这里先调用确保完全停止）
            monitorService.stopMonitoring()

            // 启动新的监控
            serviceScope.launch {
                val started = monitorService.startMonitoring(feishuChatId)
                if (!started) {
                    // 只有在启动失败时才需要提示（startMonitoring 内部会发送具体错误消息）
                    AppLogger.d(TAG, "车内检测启动失败或已在运行中")
                }
            }

            return null
        }

        // ========== 其他消息处理 ==========

        // 先停止车内检测（如果正在运行）
        try {
            CarInteriorMonitorService.getInstance(applicationContext).stopMonitoring()
        } catch (e: Exception) {
            AppLogger.w(TAG, "停止车内检测失败", e)
        }

        // 发送确认
        feishuService.sendMessage(feishuChatId, "收到")

        // 先取消之前的消息处理
        try {
            chatCore.cancelCurrentMessage()
        } catch (e: Exception) {
            AppLogger.w(TAG, "取消之前消息失败", e)
        }

        // 检查是否需要截图 - 扩展更多触发词
        val needScreenshot = userMessage.contains("截图") ||
                userMessage.contains("截屏") ||
                userMessage.contains("截个屏") ||
                userMessage.contains("截个图") ||
                userMessage.contains("截一屏") ||
                userMessage.contains("截一张") ||
                userMessage.contains("screenshot") ||
                userMessage.contains("screen capture") ||
                userMessage.contains("capture screen") ||
                (userMessage.contains("截") && userMessage.contains("图")) ||
                (userMessage.contains("截") && userMessage.contains("屏")) ||
                (userMessage.contains("看") && userMessage.contains("屏幕")) ||
                (userMessage.contains("看") && userMessage.contains("画面")) ||
                (userMessage.contains("看") && userMessage.contains("当前")) ||
                userMessage.contains("屏幕内容") ||
                userMessage.contains("当前屏幕") ||
                userMessage.contains("当前画面") ||
                userMessage.contains("手机屏幕") ||
                userMessage.contains("手机画面") ||
                userMessage.contains("快照") ||
                userMessage.contains("抓图") ||
                userMessage.contains("截取屏幕") ||
                userMessage.contains("截取画面")

        val screenshotDir = OperitPaths.cleanOnExitDir()
        val existingScreenshots = if (screenshotDir.exists()) {
            screenshotDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        } else {
            emptySet()
        }

        try {
            // 等待聊天就绪
            if (chatCore.currentChatId.value == null) {
                AppLogger.d(TAG, "等待聊天会话创建...")
                delay(500)
            }

            val initialMsgCount = chatCore.chatHistory.value.size

            if (needScreenshot) {
                feishuService.sendMessage(feishuChatId, "准备执行...")
            }

            // 发送消息到 AI
            chatCore.sendUserMessage(
                promptFunctionType = PromptFunctionType.CHAT,
                messageTextOverride = userMessage
            )

            // 等待用户消息被添加
            var userMsgAdded = false
            for (i in 0 until 20) {
                val currentCount = chatCore.chatHistory.value.size
                if (currentCount > initialMsgCount) {
                    userMsgAdded = true
                    break
                }
                delay(100)
            }

            val newInitialCount = chatCore.chatHistory.value.size

            // 监听 AI 回复并发送到飞书
            var lastAiContent = ""
            var screenshotSent = false
            val maxWaitTime = 600000L
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < maxWaitTime) {
                delay(300)

                val currentMessages = chatCore.chatHistory.value
                val currentMsgCount = currentMessages.size
                val isProcessing = chatCore.isLoading.value

                // 检查是否有新的截图
                if (needScreenshot && !screenshotSent) {
                    val newScreenshots = if (screenshotDir.exists()) {
                        screenshotDir.listFiles()
                            ?.filter { it.name !in existingScreenshots && it.name.endsWith(".png") }
                            ?.sortedByDescending { it.lastModified() }
                            ?: emptyList()
                    } else {
                        emptyList()
                    }

                    if (newScreenshots.isNotEmpty()) {
                        val screenshotFile = newScreenshots.first()

                        val screenshotJob = serviceScope.launch {
                            try {
                                feishuService.sendMessage(feishuChatId, "正在发送截图...")
                                delay(500)

                                val imageBytes = screenshotFile.readBytes()
                                val result = feishuService.sendImage(feishuChatId, imageBytes)

                                if (result != null) {
                                    feishuService.sendMessage(feishuChatId, "截图已发送")
                                } else {
                                    feishuService.sendMessage(feishuChatId, "截图发送失败")
                                }
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "发送截图异常", e)
                                feishuService.sendMessage(feishuChatId, "截图发送失败: ${e.message}")
                            }
                        }

                        withTimeoutOrNull(30000) {
                            screenshotJob.join()
                        }
                        screenshotSent = true
                    }
                }

                // 检查是否有新的 AI 回复
                if (currentMsgCount > newInitialCount) {
                    val newMessages = currentMessages.drop(newInitialCount)
                    val lastAiMessage = newMessages.lastOrNull { it.sender == "assistant" || it.sender == "ai" }

                    if (lastAiMessage != null && lastAiMessage.content.isNotBlank()) {
                        val content = lastAiMessage.content
                        if (!content.trim().startsWith(" ByteArrayInputStream")) {
                            lastAiContent = content
                        }
                    }
                }

                // 如果处理已完成，发送最终结果
                if (!isProcessing) {
                    var waitCount = 0
                    while (lastAiContent.isBlank() && waitCount < 20) {
                        delay(500)
                        waitCount++
                        val finalMessages = chatCore.chatHistory.value
                        if (finalMessages.size > newInitialCount) {
                            val newMessages = finalMessages.drop(newInitialCount)
                            val lastAiMessage = newMessages.lastOrNull { it.sender == "assistant" || it.sender == "ai" }
                            if (lastAiMessage != null && lastAiMessage.content.isNotBlank()) {
                                lastAiContent = lastAiMessage.content
                                break
                            }
                        }
                    }

                    // 处理回复内容
                    if (lastAiContent.isNotBlank()) {
                        val cleanContent = cleanFeishuResponseText(lastAiContent)
                        if (cleanContent.isNotBlank()) {
                            // 有有效的清理后内容，直接发送
                            feishuService.sendMessage(feishuChatId, cleanContent)
                        } else {
                            // 清理后为空白，检查是否有工具调用成功的迹象
                            if (lastAiContent.contains("status=\"success\"") ||
                                lastAiContent.contains("\"success\":true") ||
                                lastAiContent.contains("截图成功") ||
                                lastAiContent.contains("执行成功")) {
                                // 工具执行成功，不发送错误消息，仅记录
                                AppLogger.d(TAG, "工具执行成功，AI 响应内容清理后为空白，不发送额外消息")
                            } else if (lastAiContent.contains("status=\"error\"") ||
                                       lastAiContent.contains("\"success\":false") ||
                                       lastAiContent.contains("执行失败")) {
                                feishuService.sendMessage(feishuChatId, "操作执行失败，请稍后重试")
                            } else {
                                // 无法判断状态，发送简短成功提示
                                AppLogger.d(TAG, "无法判断工具执行状态，发送简短提示")
                                feishuService.sendMessage(feishuChatId, "操作已完成")
                            }
                        }
                    } else {
                        // AI 没有返回任何内容，发送错误提示
                        AppLogger.w(TAG, "AI 未返回任何内容")
                        feishuService.sendMessage(feishuChatId, "抱歉，我暂时无法处理您的请求。请稍后重试或检查 AI 配置是否正确。")
                    }
                    break
                }
            }

            // 超时检查 - 如果循环结束但仍在处理中
            if (System.currentTimeMillis() - startTime >= maxWaitTime && lastAiContent.isBlank()) {
                AppLogger.w(TAG, "消息处理超时")
                feishuService.sendMessage(feishuChatId, "处理超时，请稍后重试。如果问题持续，请检查网络连接或 AI 配置。")
            }

            AppLogger.d(TAG, "========== 飞书消息处理结束 ==========")

        } catch (e: Exception) {
            AppLogger.e(TAG, "处理飞书消息失败", e)
            feishuService.sendMessage(feishuChatId, "处理失败：${e.message}")
        }

        return null
    }

    /**
     * 清理飞书响应文本
     */
    private fun cleanFeishuResponseText(text: String): String {
        var result = text

        // 清理工具调用相关标签
        result = result.replace(Regex(" ByteArrayInputStream.*?ByteArrayInputStream>", RegexOption.DOT_MATCHES_ALL), "")
        result = result.replace(Regex("<status[^>]*>.*?</status>", RegexOption.DOT_MATCHES_ALL), "")
        result = result.replace(Regex("<args>.*?</args>", RegexOption.DOT_MATCHES_ALL), "")
        result = result.replace(Regex("<tool_use>.*?</tool_use>", RegexOption.DOT_MATCHES_ALL), "")
        result = result.replace(Regex("<function_calls>.*?</function_calls>", RegexOption.DOT_MATCHES_ALL), "")
        result = result.replace(Regex("<tool[^>]*>.*?</tool>", RegexOption.DOT_MATCHES_ALL), "")
        result = result.replace(Regex("<tool_result[^>]*>.*?</tool_result[^>]*>", RegexOption.DOT_MATCHES_ALL), "")
        result = result.replace(Regex("<tool[^>]*>"), "")
        result = result.replace(Regex("<tool_result[^>]*>"), "")
        result = result.replace(Regex("</tool>"), "")
        result = result.replace(Regex("</tool_result[^>]*>"), "")
        result = result.replace(Regex("</?think>"), "")
        result = result.replace(Regex("</?status[^>]*>"), "")
        result = result.replace(Regex("```xml\\s*<.*?>\\s*```", RegexOption.DOT_MATCHES_ALL), "")
        result = result.replace(Regex("```\\s*```"), "")

        // 清理思考过程中的冗余内容
        result = result.replace(Regex("用户想截屏.*?构造工具调用.*?执行调用.*?好的，为您截取当前屏幕。", RegexOption.DOT_MATCHES_ALL), "")
        result = result.replace(Regex("思考步骤：.*?检查是否有参数.*?", RegexOption.DOT_MATCHES_ALL), "")
        result = result.replace(Regex("操作步骤：.*?告知用户.*?", RegexOption.DOT_MATCHES_ALL), "")
        result = result.replace(Regex("我可以直接读取.*?系统会自动显示图片。", RegexOption.DOT_MATCHES_ALL), "")

        // 清理 JSON 格式的工具结果
        result = result.replace(Regex("\\{\"success\":true.*?\\}", RegexOption.DOT_MATCHES_ALL), "")

        // 清理多余空白
        result = result.replace(Regex("\n{3,}"), "\n\n")

        return result.trim()
    }
}