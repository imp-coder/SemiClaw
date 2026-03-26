package com.ai.assistance.operit.services

import android.content.Context
import com.ai.assistance.operit.data.model.FeishuConfig
import com.ai.assistance.operit.data.preferences.FeishuPreferences
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * 飞书服务管理器
 *
 * 管理飞书 WebSocket 连接和消息处理
 */
class FeishuServiceManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "FeishuServiceManager"

        @Volatile
        private var INSTANCE: FeishuServiceManager? = null

        fun getInstance(context: Context): FeishuServiceManager {
            return INSTANCE ?: synchronized(this) {
                val instance = FeishuServiceManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    private val feishuPreferences = FeishuPreferences.getInstance(context)
    private val feishuClient = FeishuClient(context)
    private val webSocketService = FeishuWebSocketService.getInstance(context)

    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    // 消息处理器
    private var messageHandler: (suspend (FeishuIncomingMessage) -> String?)? = null

    /**
     * 设置消息处理器
     *
     * @param handler 收到消息时的处理函数，返回要回复的内容，返回 null 则不回复
     */
    fun setMessageHandler(handler: suspend (FeishuIncomingMessage) -> String?) {
        messageHandler = handler
    }

    /**
     * 启动服务
     */
    suspend fun start(): Boolean {
        AppLogger.d(TAG, "start() 被调用")
        if (isRunning) {
            AppLogger.d(TAG, "飞书服务已在运行")
            return true
        }

        val config = feishuPreferences.getFeishuConfig()
        val enabled = feishuPreferences.feishuEnabledFlow.first()

        AppLogger.d(TAG, "飞书配置: enabled=$enabled, appId=${config.getEffectiveAppId().take(8)}***")

        if (!enabled) {
            AppLogger.w(TAG, "飞书集成未启用，请在设置中打开飞书集成开关并保存")
            return false
        }

        // 只通过 channel 监听消息，不再设置回调（避免重复处理）
        AppLogger.d(TAG, "正在连接飞书 WebSocket...")
        // 连接 WebSocket
        val connected = webSocketService.connect(config)
        if (connected) {
            isRunning = true
            AppLogger.d(TAG, "飞书服务启动成功")

            // 启动消息监听协程
            startMessageListener()
        } else {
            AppLogger.e(TAG, "飞书 WebSocket 连接失败")
        }

        return connected
    }

    /**
     * 停止服务
     */
    fun stop() {
        webSocketService.disconnect()
        isRunning = false
        AppLogger.d(TAG, "飞书服务已停止")
    }

    /**
     * 是否正在运行
     */
    fun isServiceRunning(): Boolean = isRunning && webSocketService.isWebSocketConnected()

    /**
     * 启动消息监听
     */
    private fun startMessageListener() {
        serviceScope.launch {
            val channel = webSocketService.getMessageChannel()
            try {
                for (message in channel) {
                    handleMessage(message)
                }
            } catch (e: CancellationException) {
                // 正常取消
            } catch (e: Exception) {
                AppLogger.e(TAG, "消息监听异常", e)
            }
        }
    }

    /**
     * 处理收到的消息
     */
    private fun handleMessage(message: FeishuIncomingMessage) {
        AppLogger.d(TAG, "========== 开始处理飞书消息 ==========")
        AppLogger.d(TAG, "chatId=${message.chatId}, text=${message.getTextContent().take(50)}")

        serviceScope.launch {
            try {
                // 检查消息处理器是否已设置
                if (messageHandler == null) {
                    AppLogger.w(TAG, "消息处理器未设置！请确保悬浮窗服务已启动。消息将被忽略。")
                    AppLogger.w(TAG, "解决方法：打开应用悬浮窗功能")
                    return@launch
                }

                AppLogger.d(TAG, "调用消息处理器...")

                // 调用消息处理器
                val replyContent = messageHandler?.invoke(message)

                AppLogger.d(TAG, "消息处理器返回: ${replyContent?.take(50) ?: "null"}")

                // 如果有回复内容，发送回复
                if (!replyContent.isNullOrBlank() && !message.chatId.isNullOrBlank()) {
                    AppLogger.d(TAG, "准备发送回复到飞书...")

                    val config = feishuPreferences.getFeishuConfig()
                    val result = feishuClient.sendMessage(
                        config = config,
                        receiveId = message.chatId,
                        receiveIdType = "chat_id",
                        msgType = "text",
                        content = replyContent
                    )

                    if (result != null) {
                        AppLogger.d(TAG, "消息回复成功: $result")
                    } else {
                        AppLogger.e(TAG, "消息回复失败")
                    }
                } else {
                    AppLogger.w(TAG, "回复内容为空或 chatId 无效，不发送回复")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "处理消息异常", e)
            }
        }
    }

    /**
     * 发送消息到指定聊天
     */
    suspend fun sendMessage(chatId: String, content: String, msgType: String = "text"): String? {
        val config = feishuPreferences.getFeishuConfig()
        return feishuClient.sendMessage(
            config = config,
            receiveId = chatId,
            receiveIdType = "chat_id",
            msgType = msgType,
            content = content
        )
    }

    /**
     * 发送表情回复
     */
    suspend fun sendEmoji(chatId: String, emojiType: String = "SMILE"): Boolean {
        // 发送表情消息
        val content = JSONObject()
            .put("emoji_type", emojiType)
            .toString()

        val config = feishuPreferences.getFeishuConfig()
        val result = feishuClient.sendMessage(
            config = config,
            receiveId = chatId,
            receiveIdType = "chat_id",
            msgType = "emoji",
            content = content
        )

        return result != null
    }

    /**
     * 发送图片消息
     *
     * @param chatId 聊天 ID
     * @param imageData 图片字节数据
     * @return 消息 ID，失败返回 null
     */
    suspend fun sendImage(chatId: String, imageData: ByteArray): String? {
        val config = feishuPreferences.getFeishuConfig()
        return feishuClient.uploadAndSendImage(
            config = config,
            receiveId = chatId,
            imageData = imageData,
            receiveIdType = "chat_id"
        )
    }
}