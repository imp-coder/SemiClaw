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
import com.ai.assistance.operit.core.tools.ToolProgressNotifier
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

        /**
         * 判断是否为车内检测命令
         * 支持多种触发词：精确匹配和模糊匹配
         *
         * 精确匹配：检测车内、车内检测、监控车内、开始车内监控
         * 模糊匹配：
         *   - "看" + (一下/看) + "车里" / "车内" / "车里面"
         *   - "检查" + "车里" / "车内"
         *   - "车里" / "车内" + "有什么" / "情况"
         *   - "车里" / "车内" + "看看" / "看一下"
         *   - "看看车里" / "看看车内" / "看看车里面"
         *   - "看下车里" / "看下车内"
         */
        fun isCarInteriorMonitorCommand(message: String): Boolean {
            val msg = message.lowercase().trim()

            // 精确匹配
            if (msg.contains("检测车内") ||
                msg.contains("车内检测") ||
                msg.contains("监控车内") ||
                msg.contains("开始车内监控") ||
                msg.contains("车内监控")) {
                return true
            }

            // 模糊匹配：看 + 车里/车内
            // "看一下车里"、"看看车内"、"看下车里"、"看一下车里面"等
            val lookAtCarPattern = Regex("(看(一下|看)?|看下)车(里|内|里面)")
            if (lookAtCarPattern.containsMatchIn(msg)) {
                return true
            }

            // 模糊匹配：车里/车内 + 看看/看一下/有什么/情况
            // "车里看看"、"车内看一下"、"车里有什么"、"车内情况"等
            val carStatusPattern = Regex("车(里|内|里面)(看看|看一下|有什么|情况|检测|检查)")
            if (carStatusPattern.containsMatchIn(msg)) {
                return true
            }

            // 模糊匹配：检查 + 车里/车内
            // "检查车里"、"检查车内"等
            if (msg.contains("检查车里") || msg.contains("检查车内") || msg.contains("检查车里面")) {
                return true
            }

            // 模糊匹配：看看车位（查看车位情况，也触发车内检测来查看）
            if (msg.contains("看看车位") || msg.contains("看一下车位") || msg.contains("车位看看")) {
                return true
            }

            return false
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var chatCore: ChatServiceCore
    private lateinit var feishuPreferences: FeishuPreferences
    private val binder = LocalBinder()

    // 已处理的消息ID列表（用于去重）
    private val processedMessageIds = mutableListOf<String>()

    // 最近接收到的图片缓存（按chatId存储，避免历史消息API延迟问题）
    private val recentImageCache = mutableMapOf<String, CachedImage>()

    // 缓存的图片信息
    data class CachedImage(
        val imageKey: String,
        val messageId: String?,
        val timestamp: Long = System.currentTimeMillis()
    )

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

        // 确保悬浮窗服务已启动（用于接收进度消息广播）
        try {
            val floatingIntent = Intent(applicationContext, FloatingChatService::class.java)
            floatingIntent.putExtra(FloatingChatService.EXTRA_BACKGROUND_MODE, true) // 后台模式，不显示悬浮窗
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(floatingIntent)
            } else {
                applicationContext.startService(floatingIntent)
            }
            AppLogger.d(TAG, "已启动悬浮窗服务")
        } catch (e: Exception) {
            AppLogger.e(TAG, "启动悬浮窗服务失败", e)
        }

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

        // ========== 过滤机器人发送的消息 ==========
        val senderType = message.senderType
        if (senderType == "app") {
            AppLogger.d(TAG, "忽略机器人发送的消息: messageId=${message.messageId}, senderType=$senderType")
            return null
        }

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
        AppLogger.d(TAG, "parentMessageId=${message.parentMessageId}")

        val feishuService = FeishuServiceManager.getInstance(applicationContext)

        // ========== 检查是否是壁纸/编辑命令 ==========
        val isWallpaperCommand = userMessage.contains("壁纸") ||
                userMessage.contains("wallpaper") ||
                userMessage.contains("背景") ||
                userMessage.contains("修改图片") ||
                userMessage.contains("编辑图片") ||
                userMessage.contains("改下图片")

        // ========== 处理图片消息 ==========
        val hasImage = message.isImageMessage()
        var imageKey = if (hasImage) message.getImageKey() else null
        var imageMessageId = message.messageId  // 图片来源的消息ID

        AppLogger.d(TAG, "hasImage=$hasImage, imageKey=$imageKey")

        // 缓存接收到的图片（用于后续命令使用）
        if (!imageKey.isNullOrBlank()) {
            recentImageCache[feishuChatId] = CachedImage(imageKey, imageMessageId)
            AppLogger.d(TAG, "已缓存图片: chatId=$feishuChatId, imageKey=$imageKey")
        }

        // 如果没有图片但命令需要图片，尝试从引用消息或历史消息中获取
        if (imageKey.isNullOrBlank() && isWallpaperCommand) {
            AppLogger.d(TAG, "当前消息没有图片，但检测到壁纸/编辑命令，尝试获取图片...")

            // 0. 首先尝试从缓存中获取最近接收到的图片（避免历史消息API延迟问题）
            val cachedImage = recentImageCache[feishuChatId]
            if (cachedImage != null && System.currentTimeMillis() - cachedImage.timestamp < 5 * 60 * 1000) {
                // 缓存5分钟内有效
                imageKey = cachedImage.imageKey
                imageMessageId = cachedImage.messageId
                AppLogger.d(TAG, "从缓存中获取到图片: imageKey=$imageKey, imageMessageId=$imageMessageId")
                ToolProgressNotifier.notifyInProgress(applicationContext, "set_wallpaper", "✅ 已获取最近发送的图片")
            }

            // 1. 尝试从引用消息中获取图片
            if (imageKey.isNullOrBlank() && !message.parentMessageId.isNullOrBlank()) {
                AppLogger.d(TAG, "检测到引用消息，尝试获取引用消息中的图片...")
                ToolProgressNotifier.notifyInProgress(applicationContext, "set_wallpaper", "🔍 正在获取引用消息中的图片...")

                try {
                    val feishuClient = FeishuClient(applicationContext)
                    val config = feishuPreferences.getFeishuConfig()
                    val parentMessage = feishuClient.getMessageById(config, message.parentMessageId)

                    AppLogger.d(TAG, "引用消息详情: msgType=${parentMessage?.msgType}, content=${parentMessage?.content?.take(100)}")

                    if (parentMessage != null) {
                        // 检查是否是图片消息
                        if (parentMessage.msgType == "image" ||
                            parentMessage.msgType?.contains("image") == true ||
                            parentMessage.content?.contains("image_key") == true) {

                            imageKey = feishuClient.extractImageKeyFromMessage(parentMessage)
                            if (!imageKey.isNullOrBlank()) {
                                imageMessageId = parentMessage.messageId  // 使用图片消息的ID
                                AppLogger.d(TAG, "从引用消息中获取到图片: imageKey=$imageKey, imageMessageId=$imageMessageId")
                                ToolProgressNotifier.notifyInProgress(applicationContext, "set_wallpaper", "✅ 已获取引用消息中的图片")
                            }
                        }

                        if (imageKey.isNullOrBlank()) {
                            AppLogger.w(TAG, "引用消息不是图片消息或无法提取图片: msgType=${parentMessage.msgType}")
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "获取引用消息失败", e)
                }
            }

            // 2. 如果引用消息也没有图片，尝试从历史消息中获取最近的图片
            if (imageKey.isNullOrBlank()) {
                AppLogger.d(TAG, "引用消息没有图片，尝试从历史消息中获取...")
                ToolProgressNotifier.notifyInProgress(applicationContext, "set_wallpaper", "🔍 正在查找历史消息中的图片...")

                try {
                    val feishuClient = FeishuClient(applicationContext)
                    val config = feishuPreferences.getFeishuConfig()
                    val historyMessages = feishuClient.getMessages(config, feishuChatId, 20)

                    AppLogger.d(TAG, "获取到 ${historyMessages.size} 条历史消息")

                    // 查找最近的图片消息
                    for (historyMsg in historyMessages) {
                        AppLogger.d(TAG, "历史消息: messageId=${historyMsg.messageId}, msgType=${historyMsg.msgType}")

                        // 检查是否是图片消息（支持多种图片类型）
                        if (historyMsg.msgType == "image" ||
                            historyMsg.msgType?.contains("image") == true ||
                            historyMsg.content?.contains("image_key") == true) {

                            imageKey = feishuClient.extractImageKeyFromMessage(historyMsg)
                            if (!imageKey.isNullOrBlank()) {
                                imageMessageId = historyMsg.messageId  // 使用图片消息的ID
                                AppLogger.d(TAG, "从历史消息中找到图片: imageKey=$imageKey, imageMessageId=$imageMessageId")
                                ToolProgressNotifier.notifyInProgress(applicationContext, "set_wallpaper", "✅ 已获取最近发送的图片")
                                break
                            }
                        }
                    }

                    if (imageKey.isNullOrBlank()) {
                        AppLogger.w(TAG, "历史消息中没有找到图片")
                        ToolProgressNotifier.notifyError(applicationContext, "set_wallpaper", "❌ 没有找到可用的图片，请先发送图片")
                        feishuService.sendMessage(feishuChatId, "❌ 没有找到可用的图片。\n请先发送一张图片，然后说\"设置壁纸\"或\"用AI修改图片\"。")
                        ToolProgressNotifier.clearFeishuService()
                        return null
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "获取历史消息失败", e)
                    ToolProgressNotifier.notifyError(applicationContext, "set_wallpaper", "❌ 获取历史消息失败")
                    ToolProgressNotifier.clearFeishuService()
                    return null
                }
            }
        }

        if (!imageKey.isNullOrBlank()) {
            AppLogger.d(TAG, "检测到图片，imageKey: $imageKey, messageId: ${message.messageId}")

            // 如果是壁纸/编辑命令
            if (isWallpaperCommand) {
                // 先显示用户发送的命令到悬浮窗
                ToolProgressNotifier.notifyUserMessage(applicationContext, userMessage)

                // 设置飞书服务到进度通知器（用于统一发送消息）
                ToolProgressNotifier.setFeishuService(feishuService, feishuChatId)

                // 提取编辑提示词（如果用户要求编辑图片）
                val editPrompt = extractEditPrompt(userMessage)

                if (editPrompt != null) {
                    ToolProgressNotifier.notifyStart(applicationContext, "set_wallpaper", "✏️ 正在使用 AI 编辑图片并设置壁纸...")
                    ToolProgressNotifier.notifyInProgress(applicationContext, "set_wallpaper", "📝 编辑提示词: $editPrompt")

                    try {
                        val feishuClient = FeishuClient(applicationContext)
                        val config = feishuPreferences.getFeishuConfig()

                        val result = com.ai.assistance.operit.util.WallpaperUtil.editAndSetWallpaperFromFeishu(
                            applicationContext,
                            feishuClient,
                            config,
                            imageKey,
                            imageMessageId,  // 使用图片消息的ID
                            editPrompt
                        )

                        if (result.isSuccess) {
                            ToolProgressNotifier.notifySuccess(applicationContext, "set_wallpaper", "✅ 图片编辑成功，壁纸已设置！")
                        } else {
                            val error = result.exceptionOrNull()?.message ?: "未知错误"
                            ToolProgressNotifier.notifyError(applicationContext, "set_wallpaper", "❌ 图片编辑或壁纸设置失败: $error")
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "编辑并设置壁纸异常", e)
                        ToolProgressNotifier.notifyError(applicationContext, "set_wallpaper", "❌ 图片编辑失败: ${e.message}")
                    }
                } else {
                    ToolProgressNotifier.notifyStart(applicationContext, "set_wallpaper", "🖼️ 正在设置壁纸...")

                    try {
                        val feishuClient = FeishuClient(applicationContext)
                        val config = feishuPreferences.getFeishuConfig()

                        val result = com.ai.assistance.operit.util.WallpaperUtil.setWallpaperFromFeishu(
                            applicationContext,
                            feishuClient,
                            config,
                            imageKey,
                            imageMessageId  // 使用图片消息的ID
                        )

                        if (result.isSuccess) {
                            ToolProgressNotifier.notifySuccess(applicationContext, "set_wallpaper", "✅ 壁纸设置成功！")
                        } else {
                            val error = result.exceptionOrNull()?.message ?: "未知错误"
                            ToolProgressNotifier.notifyError(applicationContext, "set_wallpaper", "❌ 壁纸设置失败: $error")
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "设置壁纸异常", e)
                        ToolProgressNotifier.notifyError(applicationContext, "set_wallpaper", "❌ 壁纸设置失败: ${e.message}")
                    }
                }

                // 清除飞书服务引用
                ToolProgressNotifier.clearFeishuService()
                return null
            }

            // 纯图片消息（没有文本命令）：回复用户询问想做什么
            if (userMessage.isBlank() && hasImage) {
                AppLogger.d(TAG, "收到纯图片消息，询问用户想做什么")
                ToolProgressNotifier.notifyUserMessage(applicationContext, "📷 收到图片")
                val replyMsg = "📷 收到一张图片！您可以告诉我：\n\n" +
                        "• \"设置壁纸\" - 直接设置为壁纸\n" +
                        "• \"把图中xxx去掉\" - AI修改图片后设置壁纸\n" +
                        "• \"把xxx改成xxx\" - AI修改后设置壁纸\n\n" +
                        "请告诉我您想怎么处理这张图片？"
                feishuService.sendMessage(feishuChatId, replyMsg)
                return null
            }

            // 图片非壁纸命令：继续走 AI 处理（使用视觉模型）
        }

        // ========== 处理文本消息 ==========
        if (userMessage.isBlank()) return null

        // ========== 车内检测命令 ==========
        // 匹配多种触发词：精确匹配和模糊匹配
        val isCarMonitorCommand = isCarInteriorMonitorCommand(userMessage)

        if (isCarMonitorCommand) {
            AppLogger.d(TAG, "检测到车内检测命令")

            // 显示用户命令到悬浮窗
            ToolProgressNotifier.notifyUserMessage(applicationContext, userMessage)

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

        // ========== 空调控制命令 ==========
        // 支持的命令格式：
        // - "打开空调"
        // - "空调设置温度到X度" / "空调调到X度" / "设置空调温度X度"
        // - "空调温度X度" / "温度调到X度"
        // - "切换摄氏度" / "改成摄氏度" / "温度单位改成摄氏"
        // - "切换华氏度" / "改成华氏度" / "温度单位改成华氏"
        // 温度可以是摄氏度或华氏度，会自动判断和转换
        val isACCommand = userMessage.contains("空调") ||
                userMessage.contains("温度调到") ||
                userMessage.contains("调温度") ||
                userMessage.contains("摄氏度") ||
                userMessage.contains("华氏度") ||
                userMessage.contains("温度单位")

        if (isACCommand) {
            AppLogger.d(TAG, "检测到空调控制命令: $userMessage")

            // 显示用户命令到悬浮窗
            ToolProgressNotifier.notifyUserMessage(applicationContext, userMessage)

            // 设置飞书服务用于发送进度消息
            ToolProgressNotifier.setFeishuService(feishuService, feishuChatId)

            val acService = CarACControlService.getInstance(applicationContext)

            // ========== 温度单位切换命令 ==========
            val switchToCelsius = userMessage.contains("摄氏度") ||
                    userMessage.contains("改成摄氏") ||
                    userMessage.contains("切换摄氏") ||
                    userMessage.contains("用摄氏") ||
                    userMessage.contains("温度单位摄氏")

            val switchToFahrenheit = userMessage.contains("华氏度") ||
                    userMessage.contains("改成华氏") ||
                    userMessage.contains("切换华氏") ||
                    userMessage.contains("用华氏") ||
                    userMessage.contains("温度单位华氏")

            if (switchToCelsius || switchToFahrenheit) {
                serviceScope.launch {
                    try {
                        val useCelsius = switchToCelsius
                        val unitName = if (useCelsius) "摄氏度(°C)" else "华氏度(°F)"
                        feishuService.sendMessage(feishuChatId, "🔄 正在将温度显示单位切换为$unitName...")

                        val success = acService.setTemperatureUnit(useCelsius)
                        if (success) {
                            feishuService.sendMessage(feishuChatId, "✅ 温度显示单位已切换为$unitName！")
                        } else {
                            feishuService.sendMessage(feishuChatId, "❌ 切换温度单位失败")
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "切换温度单位异常", e)
                        feishuService.sendMessage(feishuChatId, "❌ 切换温度单位出错: ${e.message}")
                    }
                    ToolProgressNotifier.clearFeishuService()
                }
                return null
            }

            // ========== 温度设置命令 ==========
            // 解析温度值（如果命令包含温度）
            val tempValue = extractTemperatureFromCommand(userMessage)
            val isTempSpecified = tempValue != null

            // 判断是否是华氏度还是摄氏度
            // 如果温度 > 50，认为是华氏度（摄氏度范围16-32，华氏度范围60-90）
            val tempC = if (tempValue != null) {
                if (tempValue > 50) {
                    // 用户输入的是华氏度，转换为摄氏度
                    AppLogger.d(TAG, "检测到华氏温度输入: $tempValue°F")
                    feishuService.sendMessage(feishuChatId, "📊 检测到华氏温度 ${tempValue}°F，转换为摄氏温度...")
                    CarACControlService.fahrenheitToCelsius(tempValue)
                } else {
                    // 用户输入的是摄氏度
                    AppLogger.d(TAG, "检测到摄氏温度输入: $tempValue°C")
                    tempValue
                }
            } else null

            serviceScope.launch {
                try {
                    if (userMessage.contains("打开空调") && !isTempSpecified) {
                        // 仅打开空调界面
                        feishuService.sendMessage(feishuChatId, "🚗 正在打开空调界面...")
                        val opened = acService.justOpenAC()
                        if (opened) {
                            feishuService.sendMessage(feishuChatId, "✅ 空调界面已打开！")
                        } else {
                            feishuService.sendMessage(feishuChatId, "❌ 打开空调界面失败")
                        }
                    } else if (tempC != null) {
                        // 设置温度（可能同时打开空调界面）
                        val fahrenheit = CarACControlService.celsiusToFahrenheit(tempC)
                        feishuService.sendMessage(feishuChatId,
                            "🚗 正在设置空调温度：${String.format("%.1f", tempC)}°C (${String.format("%.1f", fahrenheit)}°F)")

                        // 先打开空调界面，然后设置温度
                        val success = acService.openACAndSetTemp(tempC)
                        if (success) {
                            feishuService.sendMessage(feishuChatId,
                                "✅ 空调温度已设置为 ${String.format("%.1f", tempC)}°C (${String.format("%.1f", fahrenheit)}°F)")
                        } else {
                            feishuService.sendMessage(feishuChatId, "❌ 空调温度设置失败")
                        }
                    } else {
                        // 空调命令但没有温度，仅打开空调
                        feishuService.sendMessage(feishuChatId, "🚗 正在打开空调界面...")
                        val opened = acService.justOpenAC()
                        if (opened) {
                            feishuService.sendMessage(feishuChatId, "✅ 空调界面已打开！如需调节温度，请说\"空调温度XX度\"")
                        } else {
                            feishuService.sendMessage(feishuChatId, "❌ 打开空调界面失败")
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "空调控制异常", e)
                    feishuService.sendMessage(feishuChatId, "❌ 空调控制出错: ${e.message}")
                }

                ToolProgressNotifier.clearFeishuService()
            }

            return null
        }

        // ========== 其他消息处理 ==========

        // 显示用户消息到悬浮窗
        ToolProgressNotifier.notifyUserMessage(applicationContext, userMessage)

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

        val screenshotDir = OperitPaths.cleanOnExitDir()
        val existingScreenshots = if (screenshotDir.exists()) {
            screenshotDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        } else {
            emptySet()
        }

        // 判断是否有图片附件：纯文字用通用模型，有图片用视觉模型
        val hasImageAttachment = !imageKey.isNullOrBlank()
        val modelConfigId = if (hasImageAttachment) "default" else "general"

        AppLogger.d(TAG, "消息类型: ${if (hasImageAttachment) "图片消息(视觉模型)" else "纯文字(通用模型)"}")

        try {
            // 等待聊天就绪
            if (chatCore.currentChatId.value == null) {
                AppLogger.d(TAG, "等待聊天会话创建...")
                delay(500)
            }

            // 如果有图片，先下载并添加附件
            if (hasImageAttachment) {
                AppLogger.d(TAG, "下载飞书图片用于 AI 分析...")
                try {
                    val feishuClient = FeishuClient(applicationContext)
                    val config = feishuPreferences.getFeishuConfig()
                    val imageData = feishuClient.downloadImage(config, imageKey!!, message.messageId)

                    if (imageData != null) {
                        // 保存图片到临时文件
                        val tempFile = java.io.File(OperitPaths.cleanOnExitDir(), "feishu_image_${System.currentTimeMillis()}.jpg")
                        tempFile.writeBytes(imageData)

                        // 添加附件到聊天
                        chatCore.handleAttachment(tempFile.absolutePath)
                        AppLogger.d(TAG, "图片已添加为附件: ${tempFile.absolutePath}")
                    } else {
                        AppLogger.w(TAG, "下载飞书图片失败，仅发送文字消息")
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "处理飞书图片异常", e)
                }
            }

            val initialMsgCount = chatCore.chatHistory.value.size

            // 发送消息到 AI，根据是否有图片选择模型
            chatCore.sendUserMessage(
                promptFunctionType = PromptFunctionType.CHAT,
                messageTextOverride = userMessage,
                chatModelConfigIdOverride = modelConfigId
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

                // 检查是否有新的截图（AI会自动决定是否截图）
                if (!screenshotSent) {
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

    /**
     * 从用户消息中提取图片编辑提示词
     * 支持多种表达方式，例如：
     * - "把图片中汽车p掉" -> "去掉图中的汽车"
     * - "p掉图中的人" -> "去掉图中的人"
     * - "去掉右边的树" -> "去掉右边的树"
     * - "把汽车去掉" -> "去掉汽车"
     */
    private fun extractEditPrompt(userMessage: String): String? {
        val message = userMessage.lowercase()

        // 检查是否包含编辑关键词（包括删除类和修改类）
        val hasEditKeyword = message.contains("p掉") ||
                message.contains("去掉") ||
                message.contains("删除") ||
                message.contains("移除") ||
                message.contains("消除") ||
                message.contains("抹掉") ||
                message.contains("变成") ||
                message.contains("改成") ||
                message.contains("换成") ||
                message.contains("修改") ||
                message.contains("改一下") ||
                message.contains("调整") ||
                message.contains("把") && (message.contains("去掉") || message.contains("p掉") || message.contains("变成") || message.contains("改成"))

        if (!hasEditKeyword) return null

        // 提取编辑对象的模式
        val patterns = listOf(
            // "把图片中汽车p掉" -> 提取 "汽车"
            Regex("把图片中(.+?)p掉"),
            Regex("把图中(.+?)p掉"),
            Regex("把图片里的(.+?)p掉"),
            // "p掉图中的汽车" -> 提取 "汽车"
            Regex("p掉图中的(.+)"),
            Regex("p掉图片中的(.+)"),
            Regex("p掉图片里的(.+)"),
            // "去掉图中的汽车" -> 提取 "汽车"
            Regex("去掉图中的(.+)"),
            Regex("去掉图片中的(.+)"),
            Regex("去掉图片里的(.+)"),
            // "把汽车去掉" -> 提取 "汽车"
            Regex("把(.+?)去掉"),
            Regex("把(.+?)删除"),
            Regex("把(.+?)移除"),
            // "去掉汽车" -> 提取 "汽车"
            Regex("去掉(.+)"),
            Regex("删除(.+)"),
            Regex("移除(.+)"),
            Regex("消除(.+)"),
            Regex("抹掉(.+)"),
            // 颜色/属性修改类: "把图片中车颜色变成白色" -> 提取 "车颜色变成白色"
            Regex("把图片中(.+?)(变成|改成|换成)(.+)"),
            Regex("把图中(.+?)(变成|改成|换成)(.+)"),
            Regex("把图片里的(.+?)(变成|改成|换成)(.+)"),
            // "把车的颜色改成红色" -> 提取 "车的颜色改成红色"
            Regex("把(.+?)(变成|改成|换成)(.+)"),
            // "把车变成白色" -> 提取 "车变成白色"
            Regex("(.+?)(变成|改成|换成)(.+)")
        )

        for (pattern in patterns) {
            val match = pattern.find(userMessage)
            if (match != null && match.groupValues.size > 1) {
                // 根据匹配结果构建编辑提示词
                if (match.groupValues.size > 3) {
                    // 有三个以上分组：对象 + 动作 + 目标值
                    val targetObject = match.groupValues[1].trim()
                    val action = match.groupValues[2]  // 变成/改成/换成
                    val targetValue = match.groupValues[3].trim()
                    if (targetObject.isNotEmpty() && targetValue.isNotEmpty()) {
                        return "将$targetObject$action$targetValue"
                    }
                } else {
                    // 只有两个分组：整体描述
                    val target = match.groupValues[1].trim()
                    if (target.isNotEmpty()) {
                        if (message.contains("变成") || message.contains("改成") || message.contains("换成")) {
                            return target  // 直接返回完整描述如 "车颜色变成白色"
                        } else {
                            return "去掉图中的$target"
                        }
                    }
                }
            }
        }

        // 如果没有匹配到具体对象，但用户提到了编辑，返回通用提示
        if (hasEditKeyword && message.contains("壁纸")) {
            // 尝试从上下文推断
            return "按照用户要求编辑图片"
        }

        return null
    }

    /**
     * 从用户命令中提取温度值
     * 支持多种表达方式：
     * - "空调温度20度" / "空调温度20"
     * - "空调调到20度" / "空调调到20"
     * - "设置温度20度" / "温度设置20"
     * - "空调65度" (华氏度)
     * - "温度65" (华氏度)
     *
     * @return 温度数值（可能是摄氏度或华氏度），如果未找到则返回null
     */
    private fun extractTemperatureFromCommand(userMessage: String): Double? {
        // 多种模式匹配温度值
        val patterns = listOf(
            // "空调温度20度" / "空调温度20"
            Regex("空调温度([0-9]+\\.?[0-9]*)"),
            Regex("空调温度([0-9]+)度"),
            // "空调调到20度" / "空调调到20"
            Regex("空调调到([0-9]+\\.?[0-9]*)"),
            Regex("空调调到([0-9]+)度"),
            // "设置空调温度20度"
            Regex("设置空调温度([0-9]+\\.?[0-9]*)"),
            Regex("设置空调温度([0-9]+)度"),
            // "空调设置到20度"
            Regex("空调设置到([0-9]+\\.?[0-9]*)"),
            Regex("空调设置到([0-9]+)度"),
            // "温度调到20度" / "温度调到20"
            Regex("温度调到([0-9]+\\.?[0-9]*)"),
            Regex("温度调到([0-9]+)度"),
            // "温度设置20"
            Regex("温度设置([0-9]+\\.?[0-9]*)"),
            Regex("温度设置([0-9]+)度"),
            // "调节温度到20度"
            Regex("调节温度到([0-9]+\\.?[0-9]*)"),
            Regex("调节温度到([0-9]+)度"),
            // 简单数字+度: "20度"
            Regex("([0-9]+\\.?[0-9]*)度"),
            // 简单数字格式: "65"
            Regex("空调([0-9]+\\.?[0-9]*)"),
            Regex("温度([0-9]+\\.?[0-9]*)")
        )

        for (pattern in patterns) {
            val match = pattern.find(userMessage)
            if (match != null && match.groupValues.size > 1) {
                val tempStr = match.groupValues[1]
                try {
                    val temp = tempStr.toDouble()
                    if (temp > 0) {
                        AppLogger.d(TAG, "从命令中提取温度: $temp")
                        return temp
                    }
                } catch (e: NumberFormatException) {
                    AppLogger.w(TAG, "无法解析温度值: $tempStr")
                }
            }
        }

        return null
    }
}