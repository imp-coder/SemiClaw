package com.ai.assistance.operit.services

import android.content.Context
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 飞书 API 客户端
 *
 * 支持的功能：
 * - 消息发送/获取
 * - 文档创建/读取
 * - 任务管理
 * - 聊天列表获取
 */
class FeishuClient(private val context: Context) {

    companion object {
        private const val TAG = "FeishuClient"
        private const val BASE_URL = "https://open.feishu.cn/open-apis"

        // API 端点
        private const val AUTH_URL = "/auth/v3/tenant_access_token/internal"
        private const val SEND_MESSAGE_URL = "/im/v1/messages"
        private const val GET_MESSAGES_URL = "/im/v1/messages"
        private const val CREATE_DOCUMENT_URL = "/docx/v1/documents"
        private const val GET_DOCUMENT_URL = "/docx/v1/documents"
        private const val CREATE_TASK_URL = "/task/v1/tasks"
        private const val GET_TASKS_URL = "/task/v1/tasks"
        private const val GET_CHATS_URL = "/im/v1/chats"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // 缓存的 token
    private var cachedToken: String? = null
    private var tokenExpireTime: Long = 0

    /**
     * 获取 tenant_access_token
     */
    suspend fun getTenantAccessToken(config: FeishuConfig): String? {
        // 检查缓存的 token 是否有效
        if (!cachedToken.isNullOrBlank() && System.currentTimeMillis() < tokenExpireTime) {
            return cachedToken
        }

        return withContext(Dispatchers.IO) {
            try {
                // 使用有效的 App ID 和 App Secret（用户配置或默认值）
                val effectiveAppId = config.getEffectiveAppId()
                val effectiveAppSecret = config.getEffectiveAppSecret()

                AppLogger.d(TAG, "使用 App ID: ${effectiveAppId.take(8)}***")

                val requestBody = JSONObject()
                    .put("app_id", effectiveAppId)
                    .put("app_secret", effectiveAppSecret)
                    .toString()

                val request = Request.Builder()
                    .url("$BASE_URL$AUTH_URL")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .addHeader("Content-Type", "application/json")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        AppLogger.e(TAG, "获取 token 失败: ${response.code} ${response.message}")
                        return@withContext null
                    }

                    val jsonResponse = JSONObject(responseBody)
                    val code = jsonResponse.optInt("code", -1)
                    if (code != 0) {
                        AppLogger.e(TAG, "获取 token 失败: ${jsonResponse.optString("msg")}")
                        return@withContext null
                    }

                    val token = jsonResponse.optString("tenant_access_token")
                    val expire = jsonResponse.optInt("expire", 7200)

                    // 缓存 token，提前 5 分钟过期
                    cachedToken = token
                    tokenExpireTime = System.currentTimeMillis() + (expire - 300) * 1000L

                    AppLogger.d(TAG, "成功获取 tenant_access_token")
                    token
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "获取 token 异常", e)
                null
            }
        }
    }

    /**
     * 发送消息
     *
     * @param config 飞书配置
     * @param receiveId 接收者 ID
     * @param receiveIdType 接收者类型 (open_id, user_id, union_id, email, chat_id)
     * @param msgType 消息类型 (text, post, image, file, etc.)
     * @param content 消息内容
     * @return 消息 ID，失败返回 null
     */
    suspend fun sendMessage(
        config: FeishuConfig,
        receiveId: String,
        receiveIdType: String = "chat_id",
        msgType: String = "text",
        content: String
    ): String? {
        if (!config.isConfigured()) {
            AppLogger.e(TAG, "飞书未配置")
            return null
        }

        val token = getTenantAccessToken(config) ?: return null

        return withContext(Dispatchers.IO) {
            try {
                // 构建消息内容
                val contentJson = when (msgType) {
                    "text" -> JSONObject().put("text", content).toString()
                    else -> content
                }

                val requestBody = JSONObject()
                    .put("receive_id", receiveId)
                    .put("msg_type", msgType)
                    .put("content", contentJson)
                    .toString()

                val url = "$BASE_URL$SEND_MESSAGE_URL?receive_id_type=$receiveIdType"

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    AppLogger.d(TAG, "发送消息响应: ${response.code} $responseBody")
                    if (!response.isSuccessful) {
                        AppLogger.e(TAG, "发送消息失败: ${response.code} $responseBody")
                        return@withContext null
                    }

                    val jsonResponse = JSONObject(responseBody)
                    val code = jsonResponse.optInt("code", -1)
                    if (code != 0) {
                        val errorMsg = jsonResponse.optString("msg")
                        AppLogger.e(TAG, "发送消息失败: code=$code msg=$errorMsg")
                        return@withContext null
                    }

                    val data = jsonResponse.optJSONObject("data")
                    val messageId = data?.optString("message_id")
                    AppLogger.d(TAG, "消息发送成功: $messageId")
                    messageId
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "发送消息异常", e)
                null
            }
        }
    }

    /**
     * 获取聊天消息列表
     *
     * @param config 飞书配置
     * @param chatId 聊天 ID
     * @param pageSize 每页数量
     * @return 消息列表
     */
    suspend fun getMessages(
        config: FeishuConfig,
        chatId: String,
        pageSize: Int = 20
    ): List<FeishuMessage> {
        if (!config.isConfigured()) {
            AppLogger.e(TAG, "飞书未配置")
            return emptyList()
        }

        val token = getTenantAccessToken(config) ?: return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL$GET_MESSAGES_URL?chat_id=$chatId&page_size=$pageSize"

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        AppLogger.e(TAG, "获取消息失败: ${response.code}")
                        return@withContext emptyList()
                    }

                    val jsonResponse = JSONObject(responseBody)
                    val code = jsonResponse.optInt("code", -1)
                    if (code != 0) {
                        AppLogger.e(TAG, "获取消息失败: ${jsonResponse.optString("msg")}")
                        return@withContext emptyList()
                    }

                    val data = jsonResponse.optJSONObject("data")
                    val items = data?.optJSONArray("items") ?: return@withContext emptyList()

                    val messages = mutableListOf<FeishuMessage>()
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i)
                        messages.add(
                            FeishuMessage(
                                messageId = item?.optString("message_id"),
                                chatId = item?.optString("chat_id"),
                                msgType = item?.optString("msg_type") ?: "text",
                                content = item?.optString("content") ?: "",
                                createTime = item?.optLong("create_time"),
                                senderId = item?.optJSONObject("sender")?.optString("id"),
                                senderName = item?.optJSONObject("sender")?.optString("name")
                            )
                        )
                    }

                    AppLogger.d(TAG, "获取到 ${messages.size} 条消息")
                    messages
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "获取消息异常", e)
                emptyList()
            }
        }
    }

    /**
     * 获取聊天列表
     *
     * @param config 飞书配置
     * @param pageSize 每页数量
     * @return 聊天列表
     */
    suspend fun getChats(
        config: FeishuConfig,
        pageSize: Int = 20
    ): List<FeishuChat> {
        if (!config.isConfigured()) {
            AppLogger.e(TAG, "飞书未配置")
            return emptyList()
        }

        val token = getTenantAccessToken(config) ?: return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL$GET_CHATS_URL?page_size=$pageSize"

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        AppLogger.e(TAG, "获取聊天列表失败: ${response.code}")
                        return@withContext emptyList()
                    }

                    val jsonResponse = JSONObject(responseBody)
                    val code = jsonResponse.optInt("code", -1)
                    if (code != 0) {
                        AppLogger.e(TAG, "获取聊天列表失败: ${jsonResponse.optString("msg")}")
                        return@withContext emptyList()
                    }

                    val data = jsonResponse.optJSONObject("data")
                    val items = data?.optJSONArray("items") ?: return@withContext emptyList()

                    val chats = mutableListOf<FeishuChat>()
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i)
                        chats.add(
                            FeishuChat(
                                chatId = item?.optString("chat_id") ?: "",
                                name = item?.optString("name") ?: "",
                                avatarUrl = item?.optString("avatar"),
                                description = item?.optString("description"),
                                isGroup = item?.optString("chat_mode") == "group"
                            )
                        )
                    }

                    AppLogger.d(TAG, "获取到 ${chats.size} 个聊天")
                    chats
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "获取聊天列表异常", e)
                emptyList()
            }
        }
    }

    /**
     * 创建飞书文档
     *
     * @param config 飞书配置
     * @param title 文档标题
     * @param content 文档内容 (富文本 JSON 格式)
     * @return 文档 ID，失败返回 null
     */
    suspend fun createDocument(
        config: FeishuConfig,
        title: String,
        content: String? = null
    ): String? {
        if (!config.isConfigured()) {
            AppLogger.e(TAG, "飞书未配置")
            return null
        }

        val token = getTenantAccessToken(config) ?: return null

        return withContext(Dispatchers.IO) {
            try {
                val requestBody = JSONObject()
                    .put("title", title)
                    .apply {
                        if (content != null) {
                            // 如果有内容，需要创建文档块
                            // 这里简化处理，实际需要调用创建块的 API
                        }
                    }
                    .toString()

                val request = Request.Builder()
                    .url("$BASE_URL$CREATE_DOCUMENT_URL")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        AppLogger.e(TAG, "创建文档失败: ${response.code} $responseBody")
                        return@withContext null
                    }

                    val jsonResponse = JSONObject(responseBody)
                    val code = jsonResponse.optInt("code", -1)
                    if (code != 0) {
                        AppLogger.e(TAG, "创建文档失败: ${jsonResponse.optString("msg")}")
                        return@withContext null
                    }

                    val data = jsonResponse.optJSONObject("data")
                    val document = data?.optJSONObject("document")
                    val documentId = document?.optString("document_id")

                    AppLogger.d(TAG, "文档创建成功: $documentId")
                    documentId
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "创建文档异常", e)
                null
            }
        }
    }

    /**
     * 创建任务
     *
     * @param config 飞书配置
     * @param name 任务名称
     * @param description 任务描述
     * @param dueTime 截止时间（时间戳，可选）
     * @return 任务 ID，失败返回 null
     */
    suspend fun createTask(
        config: FeishuConfig,
        name: String,
        description: String? = null,
        dueTime: Long? = null
    ): String? {
        if (!config.isConfigured()) {
            AppLogger.e(TAG, "飞书未配置")
            return null
        }

        val token = getTenantAccessToken(config) ?: return null

        return withContext(Dispatchers.IO) {
            try {
                val taskJson = JSONObject()
                    .put("name", name)
                    .apply {
                        if (description != null) put("description", description)
                        if (dueTime != null) put("due_time", dueTime)
                    }

                val requestBody = JSONObject()
                    .put("task", taskJson)
                    .toString()

                val request = Request.Builder()
                    .url("$BASE_URL$CREATE_TASK_URL")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        AppLogger.e(TAG, "创建任务失败: ${response.code} $responseBody")
                        return@withContext null
                    }

                    val jsonResponse = JSONObject(responseBody)
                    val code = jsonResponse.optInt("code", -1)
                    if (code != 0) {
                        AppLogger.e(TAG, "创建任务失败: ${jsonResponse.optString("msg")}")
                        return@withContext null
                    }

                    val data = jsonResponse.optJSONObject("data")
                    val task = data?.optJSONObject("task")
                    val taskId = task?.optString("id")

                    AppLogger.d(TAG, "任务创建成功: $taskId")
                    taskId
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "创建任务异常", e)
                null
            }
        }
    }

    /**
     * 获取任务列表
     *
     * @param config 飞书配置
     * @param pageSize 每页数量
     * @return 任务列表
     */
    suspend fun getTasks(
        config: FeishuConfig,
        pageSize: Int = 20
    ): List<FeishuTask> {
        if (!config.isConfigured()) {
            AppLogger.e(TAG, "飞书未配置")
            return emptyList()
        }

        val token = getTenantAccessToken(config) ?: return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL$GET_TASKS_URL?page_size=$pageSize"

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        AppLogger.e(TAG, "获取任务列表失败: ${response.code}")
                        return@withContext emptyList()
                    }

                    val jsonResponse = JSONObject(responseBody)
                    val code = jsonResponse.optInt("code", -1)
                    if (code != 0) {
                        AppLogger.e(TAG, "获取任务列表失败: ${jsonResponse.optString("msg")}")
                        return@withContext emptyList()
                    }

                    val data = jsonResponse.optJSONObject("data")
                    val items = data?.optJSONArray("items") ?: return@withContext emptyList()

                    val tasks = mutableListOf<FeishuTask>()
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i)
                        tasks.add(
                            FeishuTask(
                                taskId = item?.optString("id"),
                                name = item?.optString("name") ?: "",
                                description = item?.optString("description") ?: "",
                                status = when (item?.optInt("status")) {
                                    0 -> "todo"
                                    1 -> "in_progress"
                                    2 -> "completed"
                                    else -> "unknown"
                                },
                                createTime = item?.optString("created_time")?.toLongOrNull(),
                                dueTime = item?.optString("due_time")?.toLongOrNull(),
                                completedTime = item?.optString("completed_time")?.toLongOrNull()
                            )
                        )
                    }

                    AppLogger.d(TAG, "获取到 ${tasks.size} 个任务")
                    tasks
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "获取任务列表异常", e)
                emptyList()
            }
        }
    }

    /**
     * 清除缓存的 token
     */
    fun clearTokenCache() {
        cachedToken = null
        tokenExpireTime = 0
    }

    /**
     * 获取机器人可发送消息的用户列表（最近的私聊用户）
     *
     * @param config 飞书配置
     * @return 用户列表
     */
    suspend fun getBotUsers(
        config: FeishuConfig
    ): List<Pair<String, String>> {
        if (!config.isConfigured()) {
            return emptyList()
        }

        val token = getTenantAccessToken(config) ?: return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                // 获取最近的会话列表
                val url = "$BASE_URL/im/v1/chats?page_size=50"

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        AppLogger.e(TAG, "获取用户列表失败: ${response.code}")
                        return@withContext emptyList()
                    }

                    val jsonResponse = JSONObject(responseBody)
                    val code = jsonResponse.optInt("code", -1)
                    if (code != 0) {
                        AppLogger.e(TAG, "获取用户列表失败: ${jsonResponse.optString("msg")}")
                        return@withContext emptyList()
                    }

                    val data = jsonResponse.optJSONObject("data")
                    val items = data?.optJSONArray("items") ?: return@withContext emptyList()

                    val users = mutableListOf<Pair<String, String>>()
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i)
                        val chatId = item?.optString("chat_id") ?: ""
                        val name = item?.optString("name") ?: ""
                        val chatMode = item?.optString("chat_mode") ?: ""

                        // 只返回私聊（p2p）会话
                        if (chatMode == "p2p" && chatId.isNotEmpty()) {
                            users.add(Pair(chatId, name))
                        }
                    }

                    AppLogger.d(TAG, "获取到 ${users.size} 个私聊用户")
                    users
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "获取用户列表异常", e)
                emptyList()
            }
        }
    }

    /**
     * 测试飞书连接
     */
    suspend fun testConnection(config: FeishuConfig): Pair<Boolean, String> {
        if (!config.isConfigured()) {
            return Pair(false, "飞书未配置 App ID 和 App Secret")
        }

        val token = getTenantAccessToken(config)
        return if (token != null) {
            Pair(true, "连接成功，Token 获取正常")
        } else {
            Pair(false, "获取 Token 失败，请检查 App ID 和 App Secret")
        }
    }

    /**
     * 上传图片到飞书
     *
     * @param config 飞书配置
     * @param imageFile 图片文件
     * @return image_key，失败返回 null
     */
    suspend fun uploadImage(config: FeishuConfig, imageFile: File): String? {
        if (!config.isConfigured()) {
            AppLogger.e(TAG, "飞书未配置")
            return null
        }

        val token = getTenantAccessToken(config) ?: return null

        return withContext(Dispatchers.IO) {
            try {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("image_type", "message")
                    .addFormDataPart("image", imageFile.name, imageFile.asRequestBody("image/png".toMediaType()))
                    .build()

                val request = Request.Builder()
                    .url("$BASE_URL/im/v1/images")
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    AppLogger.d(TAG, "上传图片响应: ${response.code}")

                    if (!response.isSuccessful) {
                        AppLogger.e(TAG, "上传图片失败: ${response.code} $responseBody")
                        return@withContext null
                    }

                    val jsonResponse = JSONObject(responseBody)
                    val code = jsonResponse.optInt("code", -1)
                    if (code != 0) {
                        AppLogger.e(TAG, "上传图片失败: ${jsonResponse.optString("msg")}")
                        return@withContext null
                    }

                    val data = jsonResponse.optJSONObject("data")
                    val imageKey = data?.optString("image_key")
                    AppLogger.d(TAG, "图片上传成功: $imageKey")
                    imageKey
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "上传图片异常", e)
                null
            }
        }
    }

    /**
     * 上传图片字节数据到飞书
     *
     * @param config 飞书配置
     * @param imageData 图片字节数据
     * @return image_key，失败返回 null
     */
    suspend fun uploadImage(config: FeishuConfig, imageData: ByteArray): String? {
        AppLogger.d(TAG, "uploadImage 开始，数据大小: ${imageData.size} bytes")

        if (!config.isConfigured()) {
            AppLogger.e(TAG, "飞书未配置，请检查 App ID 和 App Secret")
            return null
        }

        val token = getTenantAccessToken(config)
        if (token == null) {
            AppLogger.e(TAG, "获取 tenant_access_token 失败")
            return null
        }
        AppLogger.d(TAG, "获取 token 成功")

        return withContext(Dispatchers.IO) {
            try {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("image_type", "message")
                    .addFormDataPart("image", "screenshot.png", imageData.toRequestBody("image/png".toMediaType()))
                    .build()

                val request = Request.Builder()
                    .url("$BASE_URL/im/v1/images")
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                AppLogger.d(TAG, "发送上传图片请求: ${request.url}")
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    AppLogger.d(TAG, "上传图片响应码: ${response.code}")
                    AppLogger.d(TAG, "上传图片响应体: ${responseBody.take(500)}")

                    if (!response.isSuccessful) {
                        AppLogger.e(TAG, "上传图片 HTTP 失败: ${response.code} $responseBody")
                        return@withContext null
                    }

                    val jsonResponse = JSONObject(responseBody)
                    val code = jsonResponse.optInt("code", -1)
                    if (code != 0) {
                        AppLogger.e(TAG, "上传图片 API 错误: code=$code, msg=${jsonResponse.optString("msg")}")
                        return@withContext null
                    }

                    val data = jsonResponse.optJSONObject("data")
                    val imageKey = data?.optString("image_key")
                    if (imageKey.isNullOrBlank()) {
                        AppLogger.e(TAG, "上传图片成功但 image_key 为空")
                        return@withContext null
                    }
                    AppLogger.d(TAG, "图片上传成功，image_key: $imageKey")
                    imageKey
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "上传图片异常: ${e.message}", e)
                null
            }
        }
    }

    /**
     * 发送图片消息
     *
     * @param config 飞书配置
     * @param receiveId 接收者 ID
     * @param receiveIdType 接收者类型
     * @param imageKey 图片 key（通过 uploadImage 获取）
     * @return 消息 ID，失败返回 null
     */
    suspend fun sendImageMessage(
        config: FeishuConfig,
        receiveId: String,
        receiveIdType: String = "chat_id",
        imageKey: String
    ): String? {
        if (!config.isConfigured()) {
            AppLogger.e(TAG, "飞书未配置")
            return null
        }

        val token = getTenantAccessToken(config) ?: return null

        return withContext(Dispatchers.IO) {
            try {
                val contentJson = JSONObject()
                    .put("image_key", imageKey)
                    .toString()

                val requestBody = JSONObject()
                    .put("receive_id", receiveId)
                    .put("msg_type", "image")
                    .put("content", contentJson)
                    .toString()

                val url = "$BASE_URL$SEND_MESSAGE_URL?receive_id_type=$receiveIdType"

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    AppLogger.d(TAG, "发送图片消息响应: ${response.code}")

                    if (!response.isSuccessful) {
                        AppLogger.e(TAG, "发送图片消息失败: ${response.code} $responseBody")
                        return@withContext null
                    }

                    val jsonResponse = JSONObject(responseBody)
                    val code = jsonResponse.optInt("code", -1)
                    if (code != 0) {
                        AppLogger.e(TAG, "发送图片消息失败: ${jsonResponse.optString("msg")}")
                        return@withContext null
                    }

                    val data = jsonResponse.optJSONObject("data")
                    val messageId = data?.optString("message_id")
                    AppLogger.d(TAG, "图片消息发送成功: $messageId")
                    messageId
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "发送图片消息异常", e)
                null
            }
        }
    }

    /**
     * 上传并发送图片（便捷方法）
     *
     * @param config 飞书配置
     * @param receiveId 接收者 ID
     * @param imageData 图片字节数据
     * @return 消息 ID，失败返回 null
     */
    suspend fun uploadAndSendImage(
        config: FeishuConfig,
        receiveId: String,
        imageData: ByteArray,
        receiveIdType: String = "chat_id"
    ): String? {
        AppLogger.d(TAG, "uploadAndSendImage 开始，图片大小: ${imageData.size} bytes")

        // 先上传图片
        AppLogger.d(TAG, "步骤1: 上传图片...")
        val imageKey = uploadImage(config, imageData)
        if (imageKey == null) {
            AppLogger.e(TAG, "上传图片失败，imageKey 为 null")
            return null
        }
        AppLogger.d(TAG, "图片上传成功，imageKey: $imageKey")

        // 发送图片消息
        AppLogger.d(TAG, "步骤2: 发送图片消息...")
        val result = sendImageMessage(config, receiveId, receiveIdType, imageKey)
        if (result == null) {
            AppLogger.e(TAG, "发送图片消息失败")
        } else {
            AppLogger.d(TAG, "图片消息发送成功，messageId: $result")
        }
        return result
    }
}