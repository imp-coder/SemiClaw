package com.ai.assistance.operit.services

import android.content.Context
import com.ai.assistance.operit.data.model.FeishuConfig
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 飞书 WebSocket 长连接服务
 *
 * 用于接收飞书消息事件
 */
class FeishuWebSocketService(private val context: Context) {

    companion object {
        private const val TAG = "FeishuWebSocket"
        // 飞书 WebSocket 配置 API - POST 获取连接地址
        private const val WS_CONFIG_URL = "https://open.feishu.cn/callback/ws/endpoint"

        // 单例
        @Volatile
        private var INSTANCE: FeishuWebSocketService? = null

        fun getInstance(context: Context): FeishuWebSocketService {
            return INSTANCE ?: synchronized(this) {
                val instance = FeishuWebSocketService(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    private val feishuClient = FeishuClient(context)

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var pingIntervalSeconds: Int = 120  // 默认 120 秒心跳间隔
    private var serviceId: Int = 0  // 从 WebSocket URL 解析的 service_id

    // 消息回调
    private var onMessageReceived: ((FeishuIncomingMessage) -> Unit)? = null

    // 消息通道
    private val messageChannel = Channel<FeishuIncomingMessage>(Channel.UNLIMITED)

    // 消息有效期：只处理5分钟内的新消息，超过5分钟的旧消息直接丢弃
    private val messageMaxAgeMs = 5 * 60 * 1000L

    private val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // WebSocket 不设置读取超时
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.SECONDS) // 禁用自动 ping，手动处理心跳
            .build()
    }

    /**
     * 设置消息接收回调
     */
    fun setMessageCallback(callback: (FeishuIncomingMessage) -> Unit) {
        onMessageReceived = callback
    }

    /**
     * 获取消息通道
     */
    fun getMessageChannel(): Channel<FeishuIncomingMessage> = messageChannel

    /**
     * 发送消息到通道（不记录，只检查消息年龄）
     */
    private fun sendMessageToChannel(message: FeishuIncomingMessage) {
        val currentTime = System.currentTimeMillis()

        // 过滤机器人自己发送的消息
        if (message.senderType == "app") {
            AppLogger.d(TAG, "忽略机器人发送的消息: messageId=${message.messageId}")
            return
        }

        // 使用 messageId 作为日志标识
        val uniqueKey = message.messageId?.takeIf { it.isNotBlank() } ?: run {
            "${message.chatId}_${message.content?.take(50) ?: ""}"
        }

        // 只检查消息创建时间，超过5分钟的旧消息直接丢弃
        message.createTime?.let { msgTime ->
            // 飞书时间戳是秒级的，需要转换为毫秒
            val msgTimeMs = if (msgTime < 10000000000L) msgTime * 1000 else msgTime
            val messageAge = currentTime - msgTimeMs
            if (messageAge > messageMaxAgeMs) {
                AppLogger.w(TAG, "消息过旧，丢弃: $uniqueKey, 消息年龄: ${messageAge / 1000}秒")
                return
            }
            AppLogger.d(TAG, "消息年龄检查通过: ${messageAge / 1000}秒, $uniqueKey")
        }

        // 发送到通道，不记录任何去重信息
        CoroutineScope(Dispatchers.IO).launch {
            messageChannel.send(message)
        }
        AppLogger.d(TAG, "消息已发送到通道: $uniqueKey")
    }

    /**
     * 连接 WebSocket
     */
    suspend fun connect(config: FeishuConfig): Boolean {
        AppLogger.d(TAG, "connect() 被调用, 当前 isConnected=$isConnected")
        if (isConnected) {
            AppLogger.d(TAG, "WebSocket 已连接")
            return true
        }

        // 获取有效的 App ID 和 App Secret
        val appId = config.getEffectiveAppId()
        val appSecret = config.getEffectiveAppSecret()
        AppLogger.d(TAG, "使用 App ID: ${appId.take(8)}***")

        // 获取 WebSocket URL
        val wsUrl = getWebSocketUrl(appId, appSecret)
        if (wsUrl == null) {
            AppLogger.e(TAG, "获取 WebSocket URL 失败")
            return false
        }
        AppLogger.d(TAG, "获取 WebSocket URL 成功: ${wsUrl.take(80)}***")

        return withContext(Dispatchers.IO) {
            try {
                AppLogger.d(TAG, "正在连接飞书 WebSocket...")

                val request = Request.Builder()
                    .url(wsUrl)
                    .build()

                webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        AppLogger.d(TAG, "WebSocket 连接已打开！")
                        isConnected = true

                        // 启动心跳
                        startHeartbeat()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        AppLogger.d(TAG, "收到文本消息: ${text.take(200)}")
                        handleMessage(text)
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        // 飞书使用二进制消息（protobuf 编码）
                        AppLogger.d(TAG, "收到二进制消息，长度: ${bytes.size}")
                        handleBinaryMessage(bytes.toByteArray())
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        AppLogger.d(TAG, "WebSocket 正在关闭: $code $reason")
                        webSocket.close(1000, null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        AppLogger.d(TAG, "WebSocket 已关闭: $code $reason")
                        isConnected = false
                        stopHeartbeat()

                        // 自动重连
                        scheduleReconnect(config)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        AppLogger.e(TAG, "WebSocket 连接失败: ${t.message}, response=${response?.code}")
                        isConnected = false
                        stopHeartbeat()

                        // 自动重连
                        scheduleReconnect(config)
                    }
                })

                true
            } catch (e: Exception) {
                AppLogger.e(TAG, "连接 WebSocket 异常", e)
                false
            }
        }
    }

    /**
     * 获取 WebSocket 连接 URL
     */
    private suspend fun getWebSocketUrl(appId: String, appSecret: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .build()

                // 按照 Lark SDK 的格式发送请求
                val requestBody = JSONObject()
                    .put("AppID", appId)
                    .put("AppSecret", appSecret)
                    .toString()

                AppLogger.d(TAG, "请求 WebSocket 配置: $WS_CONFIG_URL")

                val request = Request.Builder()
                    .url(WS_CONFIG_URL)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("locale", "zh")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    AppLogger.d(TAG, "WebSocket 配置响应: $body")

                    if (!response.isSuccessful || body == null) {
                        AppLogger.e(TAG, "获取 WebSocket 配置失败: ${response.code}")
                        return@withContext null
                    }

                    val json = JSONObject(body)
                    val code = json.optInt("code", -1)
                    if (code != 0) {
                        AppLogger.e(TAG, "WebSocket 配置错误: code=$code, msg=${json.optString("msg")}")
                        return@withContext null
                    }

                    val data = json.optJSONObject("data")
                    val wsUrl = data?.optString("URL")
                    if (wsUrl.isNullOrBlank()) {
                        AppLogger.e(TAG, "WebSocket URL 为空")
                        return@withContext null
                    }

                    // 解析 ClientConfig
                    val clientConfig = data.optJSONObject("ClientConfig")
                    val pingInterval = clientConfig?.optInt("PingInterval", 120) ?: 120
                    AppLogger.d(TAG, "获取到 WebSocket URL: $wsUrl, pingInterval=${pingInterval}s")

                    // 保存心跳间隔
                    this@FeishuWebSocketService.pingIntervalSeconds = pingInterval

                    // 从 URL 解析 service_id
                    val serviceIdMatch = Regex("service_id=(\\d+)").find(wsUrl)
                    if (serviceIdMatch != null) {
                        this@FeishuWebSocketService.serviceId = serviceIdMatch.groupValues[1].toInt()
                        AppLogger.d(TAG, "解析 serviceId: ${this@FeishuWebSocketService.serviceId}")
                    }

                    wsUrl
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "获取 WebSocket URL 异常", e)
                null
            }
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        stopHeartbeat()
        reconnectJob?.cancel()
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        isConnected = false
        AppLogger.d(TAG, "WebSocket 已断开")
    }

    /**
     * 启动心跳
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        val intervalMs = (pingIntervalSeconds * 1000L).coerceAtMost(60000L)  // 最大 60 秒
        AppLogger.d(TAG, "启动心跳，间隔: ${intervalMs}ms, serviceId=$serviceId")
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(intervalMs)
                webSocket?.let { ws ->
                    // 使用 PBBP2 协议发送二进制心跳帧
                    val pingData = Pbbp2Codec.createPingFrame(serviceId)
                    ws.send(pingData.toByteString())
                    AppLogger.d(TAG, "发送 PBBP2 心跳 ping, size=${pingData.size}")
                }
            }
        }
    }

    /**
     * 停止心跳
     */
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * 安排重连
     */
    private fun scheduleReconnect(config: FeishuConfig) {
        reconnectJob?.cancel()
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            delay(5000) // 5秒后重连
            if (isActive) {
                AppLogger.d(TAG, "尝试重新连接 WebSocket...")
                connect(config)
            }
        }
    }

    /**
     * 处理收到的消息
     */
    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            when (type) {
                "pong" -> {
                    AppLogger.d(TAG, "收到心跳 pong")
                }
                "event" -> {
                    // 处理事件
                    val event = json.optJSONObject("event")
                    val eventType = event?.optString("type")

                    when (eventType) {
                        "im.message.receive_v1" -> {
                            // 收到消息
                            parseAndDispatchMessage(event)
                        }
                        else -> {
                            AppLogger.d(TAG, "收到其他事件: $eventType")
                        }
                    }
                }
                else -> {
                    AppLogger.d(TAG, "收到未知类型消息: $type")
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析消息异常", e)
        }
    }

    /**
     * 处理二进制消息（飞书使用 PBBP2 协议）
     */
    private fun handleBinaryMessage(data: ByteArray) {
        try {
            // 解析帧
            val frame = Pbbp2Codec.decode(data)
            if (frame == null) {
                AppLogger.e(TAG, "PBBP2 解码失败")
                return
            }

            // 打印帧信息
            AppLogger.d(TAG, "PBBP2 帧: service=${frame.service}, method=${frame.method}, seqId=${frame.seqId}, headers=${frame.headers.size}, payload=${frame.payload?.size ?: 0}")

            // 打印所有 headers
            for (header in frame.headers) {
                AppLogger.d(TAG, "  Header: ${header.key} = ${header.value.take(200)}")
            }

            // 检查消息类型
            val messageType = frame.getHeaderValue(Pbbp2Codec.HEADER_KEY_TYPE)

            when (messageType) {
                Pbbp2Codec.MESSAGE_TYPE_PONG -> {
                    AppLogger.d(TAG, "收到心跳 pong")
                }
                Pbbp2Codec.MESSAGE_TYPE_EVENT -> {
                    AppLogger.d(TAG, "收到事件消息，解析 payload")
                    // payload 包含事件数据，也是 protobuf 编码
                    if (frame.payload != null && frame.payload.isNotEmpty()) {
                        parsePbbp2Payload(frame.payload)
                    } else {
                        // 如果没有 payload，尝试从 headers 解析
                        parseEventFromHeaders(frame.headers)
                    }
                }
                else -> {
                    // 如果没有 type header，检查 payload
                    if (frame.payload != null && frame.payload.isNotEmpty()) {
                        AppLogger.d(TAG, "收到带 payload 的消息，尝试解析")
                        parsePbbp2Payload(frame.payload)
                    } else {
                        AppLogger.d(TAG, "收到未知消息: method=${frame.method}, type=$messageType")
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "处理二进制消息异常", e)
        }
    }

    /**
     * 解析 PBBP2 payload（事件数据）
     */
    private fun parsePbbp2Payload(payload: ByteArray) {
        try {
            // 首先尝试解析为 JSON（飞书事件通常是 JSON 格式）
            val payloadString = String(payload, Charsets.UTF_8)

            if (payloadString.startsWith("{")) {
                AppLogger.d(TAG, "Payload 是 JSON 格式: ${payloadString.take(200)}")
                parseJsonEvent(payloadString)
                return
            }

            // 如果不是 JSON，尝试解析为嵌套的 protobuf 消息
            AppLogger.d(TAG, "Payload 不是 JSON，尝试 protobuf 解析")
            val hexPreview = payload.take(50).joinToString(" ") { "%02x".format(it) }
            AppLogger.d(TAG, "Payload hex: $hexPreview")

            val buffer = java.nio.ByteBuffer.wrap(payload)
            buffer.order(java.nio.ByteOrder.BIG_ENDIAN)

            var eventHeader: EventHeader? = null
            var eventBody: ByteArray? = null

            while (buffer.hasRemaining()) {
                try {
                    val tag = decodeVarintFromBuffer(buffer)
                    if (tag == 0) break

                    val fieldNumber = tag shr 3
                    val wireType = tag and 0x7

                    when (fieldNumber) {
                        1 -> eventHeader = parseEventHeader(buffer, wireType)
                        2 -> eventBody = decodeBytesFromBuffer(buffer, wireType)
                        else -> skipFieldFromBuffer(buffer, wireType)
                    }
                } catch (e: Exception) {
                    break
                }
            }

            if (eventHeader != null && eventHeader.eventType == "im.message.receive_v1" && eventBody != null) {
                parseMessageFromEventBody(eventBody)
            } else {
                tryParseAsMessage(payload)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析 PBBP2 payload 异常", e)
        }
    }

    /**
     * 解析 JSON 格式的事件
     */
    private fun parseJsonEvent(jsonString: String) {
        try {
            val eventJson = JSONObject(jsonString)

            // 打印完整 JSON 便于调试
            AppLogger.d(TAG, "完整 JSON: $jsonString")

            // 解析 header
            val header = eventJson.optJSONObject("header")
            val eventType = header?.optString("event_type", "")
            val eventId = header?.optString("event_id", "")

            AppLogger.d(TAG, "JSON 事件: type=$eventType, id=$eventId")

            // 解析 body 或 event（飞书事件可能用 "event" 字段）
            var body = eventJson.optJSONObject("body")
            if (body == null) {
                body = eventJson.optJSONObject("event")
            }

            if (body == null) {
                AppLogger.w(TAG, "事件没有 body 或 event 字段，尝试直接从根解析")
                // 可能消息数据直接在根级别
                parseMessageFromJsonBody(eventJson)
                return
            }

            // 处理消息事件
            if (eventType == "im.message.receive_v1") {
                parseMessageFromJsonBody(body)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析 JSON 事件失败", e)
        }
    }

    /**
     * 从 JSON body 解析消息
     */
    private fun parseMessageFromJsonBody(body: JSONObject) {
        try {
            val message = body.optJSONObject("message")
            val sender = body.optJSONObject("sender")

            if (message == null) {
                AppLogger.w(TAG, "没有消息内容")
                return
            }

            val messageId = message.optString("message_id")
            val chatId = message.optString("chat_id")
            val chatType = message.optString("chat_type")
            val msgType = message.optString("message_type")  // 注意：字段名是 message_type
            val content = message.optString("content")
            val createTime = message.optString("create_time")
            val parentMessageId = message.optString("parent_id")  // 引用消息ID

            val senderId = sender?.optJSONObject("sender_id")?.optString("open_id")

            AppLogger.d(TAG, "解析消息成功: messageId=$messageId, chatId=$chatId, msgType=$msgType, parentMessageId=$parentMessageId")
            AppLogger.d(TAG, "消息内容: $content")
            AppLogger.d(TAG, "发送者: senderId=$senderId, senderType=${sender?.optString("sender_type")}")

            // 过滤机器人自己发送的消息
            val senderType = sender?.optString("sender_type")
            if (senderType == "app") {
                AppLogger.d(TAG, "忽略机器人自己发送的消息: messageId=$messageId")
                return
            }

            if (chatId.isNotBlank()) {
                val incomingMessage = FeishuIncomingMessage(
                    messageId = messageId,
                    chatId = chatId,
                    chatType = chatType,
                    msgType = msgType,
                    content = content,
                    createTime = createTime.toLongOrNull(),
                    senderId = senderId,
                    senderType = sender?.optString("sender_type"),
                    tenantKey = sender?.optString("tenant_key"),
                    parentMessageId = parentMessageId
                )

                sendMessageToChannel(incomingMessage)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析消息失败", e)
        }
    }

    /**
     * 事件头信息
     */
    private data class EventHeader(
        val eventId: String = "",
        val eventType: String = "",
        val createTime: String = "",
        val token: String = ""
    )

    /**
     * 解析事件头
     */
    private fun parseEventHeader(buffer: java.nio.ByteBuffer, wireType: Int): EventHeader? {
        return try {
            val length = if (wireType == 2) decodeVarintFromBuffer(buffer) else return null
            if (length <= 0 || length > 10000) return null

            val headerData = ByteArray(length)
            buffer.get(headerData)

            val headerBuffer = java.nio.ByteBuffer.wrap(headerData)
            headerBuffer.order(java.nio.ByteOrder.BIG_ENDIAN)

            var eventId = ""
            var eventType = ""
            var createTime = ""
            var token = ""

            while (headerBuffer.hasRemaining()) {
                val tag = decodeVarintFromBuffer(headerBuffer)
                if (tag == 0) break

                val fieldNumber = tag shr 3
                val innerWireType = tag and 0x7

                when (fieldNumber) {
                    1 -> eventId = decodeStringFromBuffer(headerBuffer, innerWireType)
                    2 -> eventType = decodeStringFromBuffer(headerBuffer, innerWireType)
                    3 -> createTime = decodeStringFromBuffer(headerBuffer, innerWireType)
                    4 -> token = decodeStringFromBuffer(headerBuffer, innerWireType)
                    else -> skipFieldFromBuffer(headerBuffer, innerWireType)
                }
            }

            EventHeader(eventId, eventType, createTime, token)
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析事件头失败", e)
            null
        }
    }

    /**
     * 从事件体解析消息
     */
    private fun parseMessageFromEventBody(body: ByteArray) {
        try {
            AppLogger.d(TAG, "解析事件体，长度: ${body.size}")

            // 打印事件体的十六进制
            val hexPreview = body.take(50).joinToString(" ") { "%02x".format(it) }
            AppLogger.d(TAG, "事件体 hex (前50字节): $hexPreview")

            val buffer = java.nio.ByteBuffer.wrap(body)
            buffer.order(java.nio.ByteOrder.BIG_ENDIAN)

            var message: ByteArray? = null
            var sender: ByteArray? = null

            while (buffer.hasRemaining()) {
                try {
                    val tag = decodeVarintFromBuffer(buffer)
                    if (tag == 0) break

                    val fieldNumber = tag shr 3
                    val wireType = tag and 0x7

                    when (fieldNumber) {
                        1 -> message = decodeBytesFromBuffer(buffer, wireType)
                        2 -> sender = decodeBytesFromBuffer(buffer, wireType)
                        else -> skipFieldFromBuffer(buffer, wireType)
                    }
                } catch (e: Exception) {
                    break
                }
            }

            // 解析sender信息，过滤机器人发送的消息
            var senderType: String? = null
            if (sender != null && sender.isNotEmpty()) {
                senderType = parseSenderType(sender)
                if (senderType == "app") {
                    AppLogger.d(TAG, "忽略机器人发送的消息(protobuf解析)")
                    return
                }
            }

            if (message != null) {
                val msgData = parseMessageData(message)
                if (msgData != null) {
                    // 将senderType添加到消息中
                    sendMessageToChannel(msgData.copy(senderType = senderType))
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析消息事件体失败", e)
        }
    }

    /**
     * 解析消息数据
     */
    private fun parseMessageData(messageBytes: ByteArray): FeishuIncomingMessage? {
        try {
            val buffer = java.nio.ByteBuffer.wrap(messageBytes)
            buffer.order(java.nio.ByteOrder.BIG_ENDIAN)

            var messageId = ""
            var chatId = ""
            var chatType = ""
            var msgType = ""
            var content = ""
            var createTime: Long? = null

            while (buffer.hasRemaining()) {
                try {
                    val tag = decodeVarintFromBuffer(buffer)
                    if (tag == 0) break

                    val fieldNumber = tag shr 3
                    val wireType = tag and 0x7

                    when (fieldNumber) {
                        1 -> messageId = decodeStringFromBuffer(buffer, wireType)
                        2 -> chatId = decodeStringFromBuffer(buffer, wireType)
                        3 -> chatType = decodeStringFromBuffer(buffer, wireType)
                        4 -> msgType = decodeStringFromBuffer(buffer, wireType)
                        5 -> content = decodeStringFromBuffer(buffer, wireType)
                        6 -> {
                            val timeStr = decodeStringFromBuffer(buffer, wireType)
                            createTime = timeStr.toLongOrNull()
                        }
                        else -> skipFieldFromBuffer(buffer, wireType)
                    }
                } catch (e: Exception) {
                    break
                }
            }

            AppLogger.d(TAG, "解析消息: messageId=$messageId, chatId=$chatId, msgType=$msgType, content=${content.take(50)}")

            if (chatId.isNotBlank()) {
                return FeishuIncomingMessage(
                    messageId = messageId,
                    chatId = chatId,
                    chatType = chatType,
                    msgType = msgType,
                    content = content,
                    createTime = createTime
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析消息数据失败", e)
        }
        return null
    }

    /**
     * 尝试直接解析为消息（备用方法）
     */
    private fun tryParseAsMessage(payload: ByteArray) {
        val msgData = parseMessageData(payload)
        if (msgData != null) {
            sendMessageToChannel(msgData)
        }
    }

    // ========== Protobuf 解码辅助方法 ==========

    private fun decodeVarintFromBuffer(buffer: java.nio.ByteBuffer): Int {
        var result = 0
        var shift = 0

        while (buffer.hasRemaining()) {
            val b = buffer.get().toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)

            if (b and 0x80 == 0) {
                break
            }
            shift += 7

            if (shift >= 32) {
                break
            }
        }

        return result
    }

    private fun decodeStringFromBuffer(buffer: java.nio.ByteBuffer, wireType: Int): String {
        return try {
            if (wireType != 2) return ""
            val length = decodeVarintFromBuffer(buffer)
            if (length <= 0 || length > 100000) return ""
            val bytes = ByteArray(length)
            buffer.get(bytes)
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private fun decodeBytesFromBuffer(buffer: java.nio.ByteBuffer, wireType: Int): ByteArray {
        return try {
            if (wireType != 2) return ByteArray(0)
            val length = decodeVarintFromBuffer(buffer)
            if (length <= 0 || length > 1000000) return ByteArray(0)
            val bytes = ByteArray(length)
            buffer.get(bytes)
            bytes
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    private fun skipFieldFromBuffer(buffer: java.nio.ByteBuffer, wireType: Int) {
        try {
            when (wireType) {
                0 -> decodeVarintFromBuffer(buffer)
                1 -> if (buffer.remaining() >= 8) buffer.position(buffer.position() + 8)
                2 -> {
                    val length = decodeVarintFromBuffer(buffer)
                    if (length > 0 && buffer.remaining() >= length) {
                        buffer.position(buffer.position() + length)
                    }
                }
                5 -> if (buffer.remaining() >= 4) buffer.position(buffer.position() + 4)
            }
        } catch (e: Exception) {
            // 忽略
        }
    }

    /**
     * 从 sender bytes 解析 senderType
     */
    private fun parseSenderType(senderBytes: ByteArray): String? {
        try {
            val buffer = java.nio.ByteBuffer.wrap(senderBytes)
            buffer.order(java.nio.ByteOrder.BIG_ENDIAN)

            while (buffer.hasRemaining()) {
                try {
                    val tag = decodeVarintFromBuffer(buffer)
                    if (tag == 0) break

                    val fieldNumber = tag shr 3
                    val wireType = tag and 0x7

                    // sender_type 字段通常是 field 2
                    when (fieldNumber) {
                        2 -> return decodeStringFromBuffer(buffer, wireType)
                        else -> skipFieldFromBuffer(buffer, wireType)
                    }
                } catch (e: Exception) {
                    break
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析senderType失败", e)
        }
        return null
    }

    /**
     * 从 headers 解析事件
     */
    private fun parseEventFromHeaders(headers: List<Pbbp2Codec.Header>) {
        try {
            // 查找可能的事件数据
            for (header in headers) {
                // 检查是否是 JSON 格式的事件数据
                if (header.value.startsWith("{") || header.value.contains("message_id")) {
                    AppLogger.d(TAG, "找到可能的 JSON 事件: ${header.key} = ${header.value.take(200)}")

                    // 尝试解析为 JSON
                    try {
                        if (header.value.startsWith("{")) {
                            val json = JSONObject(header.value)
                            // 尝试解析消息
                            parseMessageFromJson(json)
                        }
                    } catch (e: Exception) {
                        // 不是 JSON，继续
                    }
                }

                // 检查是否包含消息相关信息
                if (header.key == "message_id" || header.key.contains("message")) {
                    AppLogger.d(TAG, "消息相关 header: ${header.key} = ${header.value}")
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "从 headers 解析事件失败", e)
        }
    }

    /**
     * 从 JSON 解析消息
     */
    private fun parseMessageFromJson(json: JSONObject) {
        try {
            // 尝试不同的 JSON 结构
            val message = json.optJSONObject("message") ?: json
            val sender = json.optJSONObject("sender")

            val incomingMessage = FeishuIncomingMessage(
                messageId = message.optString("message_id"),
                chatId = message.optString("chat_id"),
                chatType = message.optString("chat_type"),
                msgType = message.optString("msg_type"),
                content = message.optString("content"),
                createTime = message.optString("create_time")?.toLongOrNull(),
                senderId = sender?.optJSONObject("sender_id")?.optString("open_id")
                    ?: message.optString("sender_id"),
                senderType = sender?.optString("sender_type"),
                tenantKey = sender?.optString("tenant_key"),
                parentMessageId = message.optString("parent_id")
            )

            // 如果有 chatId 和 content，说明解析成功
            if (!incomingMessage.chatId.isNullOrBlank()) {
                AppLogger.d(TAG, "成功解析消息: chatId=${incomingMessage.chatId}, content=${incomingMessage.content?.take(50)}")
                sendMessageToChannel(incomingMessage)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析 JSON 消息失败", e)
        }
    }

    /**
     * 解析并分发消息
     */
    private fun parseAndDispatchMessage(event: JSONObject?) {
        if (event == null) return

        try {
            val message = event.optJSONObject("message")
            val sender = event.optJSONObject("sender")

            val incomingMessage = FeishuIncomingMessage(
                messageId = message?.optString("message_id"),
                chatId = message?.optString("chat_id"),
                chatType = message?.optString("chat_type"),
                msgType = message?.optString("msg_type"),
                content = message?.optString("content"),
                createTime = message?.optString("create_time")?.toLongOrNull(),
                senderId = sender?.optString("sender_id"),
                senderType = sender?.optString("sender_type"),
                tenantKey = sender?.optString("tenant_key"),
                parentMessageId = message?.optString("parent_id")
            )

            AppLogger.d(TAG, "解析消息: chatId=${incomingMessage.chatId}, content=${incomingMessage.content?.take(50)}")
            sendMessageToChannel(incomingMessage)
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析消息失败", e)
        }
    }

    /**
     * 是否已连接
     */
    fun isWebSocketConnected(): Boolean = isConnected
}

/**
 * 飞书接收消息数据类
 */
data class FeishuIncomingMessage(
    val messageId: String? = null,
    val chatId: String? = null,
    val chatType: String? = null,  // p2p, group, etc.
    val msgType: String? = null,
    val content: String? = null,
    val createTime: Long? = null,
    val senderId: String? = null,
    val senderType: String? = null,
    val tenantKey: String? = null,
    val parentMessageId: String? = null  // 引用消息的ID
) {
    /**
     * 获取文本消息内容
     */
    fun getTextContent(): String {
        return try {
            if (msgType == "text" && !content.isNullOrBlank()) {
                val contentJson = JSONObject(content)
                contentJson.optString("text", "")
            } else {
                content ?: ""
            }
        } catch (e: Exception) {
            content ?: ""
        }
    }

    /**
     * 获取图片消息的 image_key
     * 飞书图片消息的 content 格式:
     * - 普通图片: {"image_key": "xxx"}
     * - 富文本(post): {"title":"","content":[[{"tag":"img","image_key":"xxx",...}],...]}
     */
    fun getImageKey(): String? {
        // 先打印调试信息
        AppLogger.d("FeishuIncomingMessage", "getImageKey called: msgType=$msgType, content=${content?.take(500)}")

        if (content.isNullOrBlank()) {
            AppLogger.w("FeishuIncomingMessage", "Content is null or blank")
            return null
        }

        return try {
            val contentJson = JSONObject(content)

            // 打印所有字段
            val keys = contentJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                AppLogger.d("FeishuIncomingMessage", "  content field: $key = ${contentJson.get(key)?.toString()?.take(100)}")
            }

            // 1. 图片消息直接有 image_key
            val imageKey = contentJson.optString("image_key", "")
            if (imageKey.isNotBlank()) {
                AppLogger.d("FeishuIncomingMessage", "Found direct image_key: $imageKey")
                return imageKey
            }

            // 2. 尝试 file_key
            val fileKey = contentJson.optString("file_key", "")
            if (fileKey.isNotBlank()) {
                AppLogger.d("FeishuIncomingMessage", "Found file_key: $fileKey")
                return fileKey
            }

            // 3. 富文本消息 (post 类型): content 是嵌套数组
            // 格式: {"content":[[{"tag":"img","image_key":"xxx"}],[{"tag":"text","text":"..."}]]}
            val contentArray = contentJson.optJSONArray("content")
            if (contentArray != null && contentArray.length() > 0) {
                // 遍历外层数组
                for (i in 0 until contentArray.length()) {
                    val innerArray = contentArray.optJSONArray(i)
                    if (innerArray != null) {
                        // 遍历内层数组
                        for (j in 0 until innerArray.length()) {
                            val element = innerArray.optJSONObject(j)
                            if (element != null) {
                                val tag = element.optString("tag", "")
                                AppLogger.d("FeishuIncomingMessage", "Found element with tag: $tag")

                                // 查找图片元素
                                if (tag == "img" || tag == "image") {
                                    val imgKey = element.optString("image_key", "")
                                    if (imgKey.isNotBlank()) {
                                        AppLogger.d("FeishuIncomingMessage", "Found image_key in post content: $imgKey")
                                        return imgKey
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. 尝试从 rich_text 中查找
            val richText = contentJson.optString("rich_text", "")
            if (richText.isNotBlank()) {
                val imageKeyPattern = Regex("\"image_key\"\\s*:\\s*\"([^\"]+)\"")
                val match = imageKeyPattern.find(richText)
                if (match != null) {
                    val extractedKey = match.groupValues[1]
                    AppLogger.d("FeishuIncomingMessage", "Extracted image_key from rich_text: $extractedKey")
                    return extractedKey
                }
            }

            // 5. 尝试 media_id
            val mediaId = contentJson.optString("media_id", "")
            if (mediaId.isNotBlank()) {
                AppLogger.d("FeishuIncomingMessage", "Found media_id: $mediaId")
                return mediaId
            }

            AppLogger.w("FeishuIncomingMessage", "No image_key found in content")
            null
        } catch (e: Exception) {
            AppLogger.e("FeishuIncomingMessage", "getImageKey error: ${e.message}")
            null
        }
    }

    /**
     * 获取富文本消息中的文本内容
     */
    fun getPostTextContent(): String {
        if (content.isNullOrBlank()) return ""

        return try {
            val contentJson = JSONObject(content)
            val contentArray = contentJson.optJSONArray("content")
            if (contentArray != null) {
                val textBuilder = StringBuilder()
                for (i in 0 until contentArray.length()) {
                    val innerArray = contentArray.optJSONArray(i)
                    if (innerArray != null) {
                        for (j in 0 until innerArray.length()) {
                            val element = innerArray.optJSONObject(j)
                            if (element != null) {
                                val tag = element.optString("tag", "")
                                if (tag == "text") {
                                    textBuilder.append(element.optString("text", ""))
                                }
                            }
                        }
                    }
                }
                textBuilder.toString()
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 判断是否是图片消息
     */
    fun isImageMessage(): Boolean {
        // 飞书图片消息类型可能是 image、post，或者包含图片内容
        if (msgType == "image") {
            return true
        }
        // post 类型需要检查是否包含图片
        if (msgType == "post" && !content.isNullOrBlank()) {
            return content.contains("\"tag\":\"img\"") ||
                   content.contains("\"tag\":\"image\"") ||
                   content.contains("image_key")
        }
        // 其他情况检查 content 中是否包含 image_key
        if (!content.isNullOrBlank()) {
            return content.contains("image_key") || content.contains("file_key")
        }
        return false
    }
}