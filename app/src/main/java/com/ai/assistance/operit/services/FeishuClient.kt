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

    /**
     * 下载飞书图片
     *
     * 飞书图片下载 API 文档: https://open.feishu.cn/document/server-docs/im-v1/image/download
     * 注意：飞书图片 key 格式如 img_v3_02109_xxx
     * 需要权限: im:resource:read (获取消息图片) 或 im:resource (下载资源)
     *
     * @param config 飞书配置
     * @param imageKey 图片 key（从消息中获取）
     * @param messageId 可选的消息 ID，用于获取消息资源
     * @return 图片字节数据，失败返回 null
     */
    suspend fun downloadImage(config: FeishuConfig, imageKey: String, messageId: String? = null): ByteArray? {
        if (!config.isConfigured()) {
            AppLogger.e(TAG, "飞书未配置")
            return null
        }

        val token = getTenantAccessToken(config)
        if (token == null) {
            AppLogger.e(TAG, "获取 tenant_access_token 失败")
            return null
        }

        AppLogger.d(TAG, "准备下载图片, imageKey=$imageKey, messageId=$messageId")

        return withContext(Dispatchers.IO) {
            // 方法0: 如果有 messageId，尝试使用消息资源 API
            if (!messageId.isNullOrBlank()) {
                val result0 = tryDownloadMessageResource(token, messageId, imageKey)
                if (result0 != null) {
                    AppLogger.d(TAG, "方法0(消息资源)成功")
                    return@withContext result0
                }
            }

            // 方法1: 使用 POST multipart/form-data 下载接口（推荐）
            val result1 = tryDownloadWithPost(token, imageKey)
            if (result1 != null) {
                AppLogger.d(TAG, "方法1(POST multipart)成功")
                return@withContext result1
            }

            // 方法2: 使用 POST JSON 格式（备用）
            val result2 = tryDownloadWithPostJson(token, imageKey)
            if (result2 != null) {
                AppLogger.d(TAG, "方法2(POST JSON)成功")
                return@withContext result2
            }

            // 方法3: 使用 GET 获取图片信息接口
            val result3 = tryGetImageInfo(token, imageKey)
            if (result3 != null) {
                AppLogger.d(TAG, "方法3(GET信息)成功")
                return@withContext result3
            }

            // 方法4: 尝试 GET 直接下载（带 query 参数）
            val result4 = tryDownloadWithGet(token, imageKey)
            if (result4 != null) {
                AppLogger.d(TAG, "方法4(GET下载)成功")
                return@withContext result4
            }

            // 方法5: 尝试使用文件下载 API
            val result5 = tryDownloadAsFile(token, imageKey)
            if (result5 != null) {
                AppLogger.d(TAG, "方法5(文件下载)成功")
                return@withContext result5
            }

            // 方法6: 尝试 v2 API
            val result6 = tryGetImageInfoV2(token, imageKey)
            if (result6 != null) {
                AppLogger.d(TAG, "方法6(V2信息)成功")
                return@withContext result6
            }

            AppLogger.e(TAG, "所有下载方法都失败，请检查飞书应用权限配置")
            AppLogger.e(TAG, "需要权限: im:resource:read 或 im:resource")
            null
        }
    }

    /**
     * 方法0: 通过消息资源 API 获取图片
     * API: GET /im/v1/messages/:message_id/resources/:file_key?type=image
     */
    private fun tryDownloadMessageResource(token: String, messageId: String, fileKey: String): ByteArray? {
        try {
            // 飞书获取消息资源 API
            val resourceUrl = "$BASE_URL/im/v1/messages/$messageId/resources/$fileKey?type=image"

            AppLogger.d(TAG, "[消息资源] URL: $resourceUrl")

            val request = Request.Builder()
                .url(resourceUrl)
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val code = response.code
                val contentType = response.header("Content-Type", "")
                AppLogger.d(TAG, "[消息资源] 响应码: $code, Content-Type: $contentType")

                if (response.isSuccessful) {
                    // 如果是图片数据
                    if (contentType?.startsWith("image/") == true ||
                        contentType?.startsWith("application/octet-stream") == true) {
                        val imageData = response.body?.bytes()
                        if (imageData != null && imageData.isNotEmpty()) {
                            AppLogger.d(TAG, "[消息资源] 图片下载成功，大小: ${imageData.size} bytes")
                            return imageData
                        }
                    }

                    // 如果返回 JSON
                    val body = response.body?.string().orEmpty()
                    AppLogger.d(TAG, "[消息资源] Body: ${body.take(500)}")

                    try {
                        val json = JSONObject(body)
                        if (json.optInt("code", -1) == 0) {
                            val data = json.optJSONObject("data")
                            val tmpUrl = data?.optString("tmp_url", "")
                                ?.ifBlank { data?.optString("download_url", "") }

                            if (!tmpUrl.isNullOrBlank()) {
                                AppLogger.d(TAG, "[消息资源] 获取到临时URL: $tmpUrl")
                                return downloadFromUrlSync(tmpUrl)
                            }
                        }
                    } catch (e: Exception) {
                        // 可能是直接的图片数据
                        AppLogger.d(TAG, "[消息资源] 尝试作为二进制处理")
                    }
                } else {
                    val errorBody = response.body?.string().orEmpty()
                    AppLogger.e(TAG, "[消息资源] 失败: $code, body: ${errorBody.take(300)}")
                }
            }
            return null
        } catch (e: Exception) {
            AppLogger.e(TAG, "[消息资源] 异常: ${e.message}")
            return null
        }
    }

    /**
     * 方法1: POST /im/v1/images/:image_key/download (使用 multipart/form-data)
     */
    private fun tryDownloadWithPost(token: String, imageKey: String): ByteArray? {
        try {
            // 注意：飞书 API 不需要在 URL 中加 /download，直接 POST 到 /im/v1/images/:image_key
            val downloadUrl = "$BASE_URL/im/v1/images/$imageKey"

            // 飞书 API 要求 multipart/form-data 格式
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image_type", "message")
                .build()

            AppLogger.d(TAG, "[POST下载] URL: $downloadUrl, 使用 multipart/form-data")

            val request = Request.Builder()
                .url(downloadUrl)
                .post(requestBody)
                .addHeader("Authorization", "Bearer $token")
                .build()

            httpClient.newCall(request).execute().use { response ->
                return handleDownloadResponse(response, "POST下载")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "[POST下载] 异常: ${e.message}")
            return null
        }
    }

    /**
     * 方法1b: POST 使用 JSON 格式（备用）
     */
    private fun tryDownloadWithPostJson(token: String, imageKey: String): ByteArray? {
        try {
            // 尝试直接 POST 到图片 URL
            val downloadUrl = "$BASE_URL/im/v1/images/$imageKey"

            val requestBody = JSONObject()
                .put("image_type", "message")
                .toString()

            AppLogger.d(TAG, "[POST-JSON下载] URL: $downloadUrl, Body: $requestBody")

            val request = Request.Builder()
                .url(downloadUrl)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                return handleDownloadResponse(response, "POST-JSON下载")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "[POST-JSON下载] 异常: ${e.message}")
            return null
        }
    }

    /**
     * 方法3: 使用 GET 获取图片信息接口
     */
    private fun tryGetImageInfo(token: String, imageKey: String): ByteArray? {
        try {
            // 尝试多种参数组合
            val urls = listOf(
                "$BASE_URL/im/v1/images/$imageKey",
                "$BASE_URL/im/v1/images/$imageKey?image_type=message",
                "$BASE_URL/im/v1/images/$imageKey?type=message"
            )

            for ((index, infoUrl) in urls.withIndex()) {
                AppLogger.d(TAG, "[GET信息${index + 1}] URL: $infoUrl")

                val request = Request.Builder()
                    .url(infoUrl)
                    .get()
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                try {
                    httpClient.newCall(request).execute().use { response ->
                        val code = response.code
                        val body = response.body?.string().orEmpty()
                        AppLogger.d(TAG, "[GET信息${index + 1}] 响应码: $code, Body: ${body.take(500)}")

                        if (response.isSuccessful) {
                            try {
                                val json = JSONObject(body)
                                if (json.optInt("code", -1) == 0) {
                                    val data = json.optJSONObject("data")
                                    val tmpUrl = data?.optString("tmp_url", "")
                                        ?.ifBlank { data?.optString("download_url", "") }
                                        ?.ifBlank { data?.optString("url", "") }

                                    if (!tmpUrl.isNullOrBlank()) {
                                        AppLogger.d(TAG, "[GET信息${index + 1}] 获取到临时URL: $tmpUrl")
                                        return downloadFromUrlSync(tmpUrl)
                                    }

                                    // 尝试直接从 data 中获取图片内容
                                    val imageContent = data?.optString("image", "")
                                    if (!imageContent.isNullOrBlank()) {
                                        // 可能是 base64 编码的图片
                                        try {
                                            val imageBytes = android.util.Base64.decode(imageContent, android.util.Base64.DEFAULT)
                                            if (imageBytes.isNotEmpty()) {
                                                AppLogger.d(TAG, "[GET信息${index + 1}] 获取到 base64 图片数据，大小: ${imageBytes.size}")
                                                return imageBytes
                                            }
                                        } catch (e: Exception) {
                                            AppLogger.e(TAG, "[GET信息${index + 1}] Base64 解码失败: ${e.message}")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "[GET信息${index + 1}] JSON解析失败: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "[GET信息${index + 1}] 请求异常: ${e.message}")
                }
            }
            return null
        } catch (e: Exception) {
            AppLogger.e(TAG, "[GET信息] 异常: ${e.message}")
            return null
        }
    }

    /**
     * 方法4: 尝试 GET 直接下载（带 query 参数）
     */
    private fun tryDownloadWithGet(token: String, imageKey: String): ByteArray? {
        try {
            val downloadUrl = "$BASE_URL/im/v1/images/$imageKey/download?image_type=message"

            AppLogger.d(TAG, "[GET下载] URL: $downloadUrl")

            val request = Request.Builder()
                .url(downloadUrl)
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()

            httpClient.newCall(request).execute().use { response ->
                return handleDownloadResponse(response, "GET下载")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "[GET下载] 异常: ${e.message}")
            return null
        }
    }

    /**
     * 方法5: 尝试使用文件下载 API (file_key)
     * 某些 post 消息中的图片可能需要用文件下载 API
     */
    private fun tryDownloadAsFile(token: String, imageKey: String): ByteArray? {
        try {
            // 飞书文件下载 API: GET /drive/v1/files/:file_key/download
            val downloadUrl = "$BASE_URL/drive/v1/files/$imageKey/download"

            AppLogger.d(TAG, "[文件下载] URL: $downloadUrl")

            val request = Request.Builder()
                .url(downloadUrl)
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()

            httpClient.newCall(request).execute().use { response ->
                return handleDownloadResponse(response, "文件下载")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "[文件下载] 异常: ${e.message}")
            return null
        }
    }

    /**
     * 方法6: 尝试获取图片信息并下载 (v2 API)
     */
    private fun tryGetImageInfoV2(token: String, imageKey: String): ByteArray? {
        try {
            // 飞书可能还有 v2 版本的 API
            val infoUrl = "$BASE_URL/im/v2/images/$imageKey"

            AppLogger.d(TAG, "[GET信息V2] URL: $infoUrl")

            val request = Request.Builder()
                .url(infoUrl)
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string().orEmpty()
                AppLogger.d(TAG, "[GET信息V2] 响应码: $code, Body: ${body.take(500)}")

                if (response.isSuccessful) {
                    try {
                        val json = JSONObject(body)
                        if (json.optInt("code", -1) == 0) {
                            val data = json.optJSONObject("data")
                            val tmpUrl = data?.optString("tmp_url", "")
                                ?.ifBlank { data?.optString("download_url", "") }
                                ?.ifBlank { data?.optString("url", "") }

                            if (!tmpUrl.isNullOrBlank()) {
                                AppLogger.d(TAG, "[GET信息V2] 获取到临时URL: $tmpUrl")
                                return downloadFromUrlSync(tmpUrl)
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "[GET信息V2] JSON解析失败: ${e.message}")
                    }
                }
            }
            return null
        } catch (e: Exception) {
            AppLogger.e(TAG, "[GET信息V2] 异常: ${e.message}")
            return null
        }
    }

    /**
     * 处理下载响应
     */
    private fun handleDownloadResponse(response: okhttp3.Response, methodTag: String): ByteArray? {
        val code = response.code
        val contentType = response.header("Content-Type", "")
        AppLogger.d(TAG, "[$methodTag] 响应码: $code, Content-Type: $contentType")

        if (response.isSuccessful) {
            // 检查是否是图片数据
            if (contentType?.startsWith("image/") == true ||
                contentType?.startsWith("application/octet-stream") == true ||
                contentType?.contains("application/") == true && !contentType.contains("json")) {
                val imageData = response.body?.bytes()
                if (imageData != null && imageData.isNotEmpty()) {
                    AppLogger.d(TAG, "[$methodTag] 图片下载成功，大小: ${imageData.size} bytes")
                    return imageData
                }
            }

            // 如果返回 JSON，解析获取临时 URL
            if (contentType?.contains("application/json") == true || contentType?.contains("text/") == true) {
                val jsonBody = response.body?.string().orEmpty()
                AppLogger.d(TAG, "[$methodTag] 返回 JSON: ${jsonBody.take(500)}")

                try {
                    val jsonResponse = JSONObject(jsonBody)
                    val respCode = jsonResponse.optInt("code", -1)
                    if (respCode == 0) {
                        val data = jsonResponse.optJSONObject("data")
                        val tmpUrl = data?.optString("tmp_url", "")
                            ?.ifBlank { data?.optString("download_url", "") }
                            ?.ifBlank { data?.optString("url", "") }

                        if (!tmpUrl.isNullOrBlank()) {
                            AppLogger.d(TAG, "[$methodTag] 获取临时URL: $tmpUrl")
                            return downloadFromUrlSync(tmpUrl)
                        }
                    } else {
                        val errorMsg = jsonResponse.optString("msg", "")
                        AppLogger.e(TAG, "[$methodTag] API错误: code=$respCode, msg=$errorMsg")
                        if (errorMsg.contains("permission") || respCode == 99991663) {
                            AppLogger.e(TAG, "[$methodTag] 权限不足！请开启 'im:resource:read' 权限")
                        }
                    }
                } catch (e: Exception) {
                    // 不是JSON，可能是直接的图片数据
                    val bytes = response.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty() && bytes.size > 100) {
                        AppLogger.d(TAG, "[$methodTag] 尝试作为二进制数据处理，大小: ${bytes.size}")
                        return bytes
                    }
                }
            }
        } else {
            val errorBody = response.body?.string().orEmpty()
            AppLogger.e(TAG, "[$methodTag] HTTP失败: $code, body: ${errorBody.take(300)}")
        }

        return null
    }

    /**
     * 同步从URL下载
     */
    private fun downloadFromUrlSync(url: String): ByteArray? {
        try {
            AppLogger.d(TAG, "从临时URL下载: ${url.take(100)}...")

            val downloadClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            downloadClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val data = response.body?.bytes()
                    if (data != null && data.isNotEmpty()) {
                        AppLogger.d(TAG, "临时URL下载成功，大小: ${data.size} bytes")
                        return data
                    }
                } else {
                    AppLogger.e(TAG, "临时URL下载失败: ${response.code}")
                }
            }
            return null
        } catch (e: Exception) {
            AppLogger.e(TAG, "临时URL下载异常: ${e.message}")
            return null
        }
    }

    /**
     * 从 URL 下载文件
     */
    private suspend fun downloadFromUrl(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            AppLogger.d(TAG, "从 URL 下载: ${url.take(100)}...")

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            // 使用新的客户端，不带认证头
            val downloadClient = OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.e(TAG, "URL 下载失败: ${response.code}")
                    return@withContext null
                }

                val data = response.body?.bytes()
                if (data != null && data.isNotEmpty()) {
                    AppLogger.d(TAG, "URL 下载成功，大小: ${data.size} bytes")
                }
                data
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "URL 下载异常", e)
            null
        }
    }
}