package com.ai.assistance.operit.data.model

import kotlinx.serialization.Serializable

/**
 * 飞书配置
 */
@Serializable
data class FeishuConfig(
    val appId: String = "",
    val appSecret: String = "",
    val enabled: Boolean = false,
    val tenantAccessToken: String? = null,
    val tokenExpireTime: Long = 0
) {
    companion object {
        // 默认凭证 - 直接内置到应用中
        // 如果需要修改，请替换为你自己的 App ID 和 App Secret
        const val DEFAULT_APP_ID = "cli_a94b9f0d0878dbd9"
        const val DEFAULT_APP_SECRET = "SclwXuPycs3RviXamLMLm4Bb4HBA2ofo"

        // 是否使用内置默认凭证
        const val USE_DEFAULT_CREDENTIALS = true
    }

    fun isConfigured(): Boolean {
        // 如果启用了默认凭证，始终返回 true
        if (USE_DEFAULT_CREDENTIALS) return true
        return appId.isNotBlank() && appSecret.isNotBlank()
    }

    fun getEffectiveAppId(): String {
        return if (appId.isNotBlank()) appId else DEFAULT_APP_ID
    }

    fun getEffectiveAppSecret(): String {
        return if (appSecret.isNotBlank()) appSecret else DEFAULT_APP_SECRET
    }

    fun isTokenValid(): Boolean {
        return !tenantAccessToken.isNullOrBlank() && System.currentTimeMillis() < tokenExpireTime
    }
}

/**
 * 飞书消息
 */
@Serializable
data class FeishuMessage(
    val messageId: String? = null,
    val chatId: String? = null,
    val msgType: String = "text",
    val content: String = "",
    val createTime: Long? = null,
    val senderId: String? = null,
    val senderName: String? = null
)

/**
 * 飞书文档
 */
@Serializable
data class FeishuDocument(
    val documentId: String? = null,
    val title: String = "",
    val content: String = "",
    val createTime: Long? = null,
    val editTime: Long? = null,
    val url: String? = null
)

/**
 * 飞书任务
 */
@Serializable
data class FeishuTask(
    val taskId: String? = null,
    val name: String = "",
    val description: String = "",
    val status: String = "todo",
    val createTime: Long? = null,
    val dueTime: Long? = null,
    val completedTime: Long? = null
)

/**
 * 飞书聊天
 */
@Serializable
data class FeishuChat(
    val chatId: String,
    val name: String,
    val avatarUrl: String? = null,
    val description: String? = null,
    val isGroup: Boolean = false
)

/**
 * 飞书 API 响应基类
 */
@Serializable
data class FeishuResponse<T>(
    val code: Int = 0,
    val msg: String = "",
    val data: T? = null
) {
    fun isSuccess(): Boolean = code == 0
}

/**
 * 飞书 Token 响应
 */
@Serializable
data class FeishuTokenData(
    val tenant_access_token: String = "",
    val expire: Int = 0
)

/**
 * 飞书消息响应
 */
@Serializable
data class FeishuMessageData(
    val message_id: String = ""
)

/**
 * 飞书文档响应
 */
@Serializable
data class FeishuDocumentData(
    val document: FeishuDocumentInfo? = null
)

@Serializable
data class FeishuDocumentInfo(
    val document_id: String = "",
    val title: String = "",
    val revision_id: Int = 0
)

/**
 * 飞书任务响应
 */
@Serializable
data class FeishuTaskData(
    val task: FeishuTaskInfo? = null
)

@Serializable
data class FeishuTaskInfo(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val status: Int = 0,
    val created_time: String? = null,
    val completed_time: String? = null
)

/**
 * 飞书聊天列表响应
 */
@Serializable
data class FeishuChatListData(
    val items: List<FeishuChatInfo> = emptyList(),
    val has_more: Boolean = false,
    val page_token: String? = null
)

@Serializable
data class FeishuChatInfo(
    val chat_id: String = "",
    val name: String = "",
    val avatar: String? = null,
    val description: String? = null,
    val chat_mode: String = ""
)