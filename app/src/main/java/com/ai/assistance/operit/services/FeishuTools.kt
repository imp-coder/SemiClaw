package com.ai.assistance.operit.services

import android.content.Context
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.preferences.FeishuPreferences
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 飞书 AI 工具集
 *
 * 提供飞书相关的 AI 工具实现，供 ToolRegistration 注册使用
 */
class FeishuTools(private val context: Context) {

    companion object {
        private const val TAG = "FeishuTools"
    }

    private val feishuPreferences = FeishuPreferences.getInstance(context)
    private val feishuClient = FeishuClient(context)

    /**
     * 发送飞书消息
     *
     * 参数:
     * - receive_id: 接收者 ID（聊天 ID 或用户 ID）
     * - receive_id_type: 接收者类型 (chat_id, open_id, user_id, union_id, email)
     * - msg_type: 消息类型 (text, post, image, file, etc.)
     * - content: 消息内容
     */
    suspend fun sendFeishuMessage(tool: AITool): ToolResult = withContext(Dispatchers.IO) {
        try {
            val config = feishuPreferences.getFeishuConfig()
            if (!config.isConfigured()) {
                return@withContext ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = com.ai.assistance.operit.core.tools.StringResultData(""),
                    error = "飞书未配置，请先在设置中配置 App ID 和 App Secret"
                )
            }

            val receiveId = tool.parameters.find { it.name == "receive_id" }?.value
            val receiveIdType = tool.parameters.find { it.name == "receive_id_type" }?.value ?: "chat_id"
            val msgType = tool.parameters.find { it.name == "msg_type" }?.value ?: "text"
            val content = tool.parameters.find { it.name == "content" }?.value

            if (receiveId.isNullOrBlank()) {
                // 尝试使用默认聊天 ID
                val defaultChatId = feishuPreferences.getDefaultChatId()
                if (defaultChatId.isBlank()) {
                    return@withContext ToolResult(
                        toolName = tool.name,
                        success = false,
                        result = com.ai.assistance.operit.core.tools.StringResultData(""),
                        error = "缺少 receive_id 参数，且未设置默认聊天 ID"
                    )
                }
            }

            val actualReceiveId = receiveId ?: feishuPreferences.getDefaultChatId()

            if (content.isNullOrBlank()) {
                return@withContext ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = com.ai.assistance.operit.core.tools.StringResultData(""),
                    error = "缺少 content 参数"
                )
            }

            val messageId = feishuClient.sendMessage(
                config = config,
                receiveId = actualReceiveId,
                receiveIdType = receiveIdType,
                msgType = msgType,
                content = content
            )

            if (messageId != null) {
                AppLogger.d(TAG, "消息发送成功: $messageId")
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = com.ai.assistance.operit.core.tools.StringResultData(
                        "消息发送成功\n消息 ID: $messageId\n接收者: $actualReceiveId"
                    )
                )
            } else {
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = com.ai.assistance.operit.core.tools.StringResultData(""),
                    error = "消息发送失败，请检查配置和网络连接"
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "发送消息异常", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = com.ai.assistance.operit.core.tools.StringResultData(""),
                error = "发送消息异常: ${e.message}"
            )
        }
    }

    /**
     * 获取飞书聊天列表
     *
     * 参数:
     * - page_size: 每页数量（可选，默认 20）
     */
    suspend fun getFeishuChats(tool: AITool): ToolResult = withContext(Dispatchers.IO) {
        try {
            val config = feishuPreferences.getFeishuConfig()
            if (!config.isConfigured()) {
                return@withContext ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = com.ai.assistance.operit.core.tools.StringResultData(""),
                    error = "飞书未配置，请先在设置中配置 App ID 和 App Secret"
                )
            }

            val pageSize = tool.parameters.find { it.name == "page_size" }?.value?.toIntOrNull() ?: 20

            val chats = feishuClient.getChats(config, pageSize)

            if (chats.isEmpty()) {
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = com.ai.assistance.operit.core.tools.StringResultData("未获取到任何聊天")
                )
            } else {
                val resultText = buildString {
                    appendLine("获取到 ${chats.size} 个聊天:\n")
                    chats.forEachIndexed { index, chat ->
                        appendLine("${index + 1}. ${chat.name}")
                        appendLine("   ID: ${chat.chatId}")
                        appendLine("   类型: ${if (chat.isGroup) "群聊" else "私聊"}")
                        if (!chat.description.isNullOrBlank()) {
                            appendLine("   描述: ${chat.description}")
                        }
                        appendLine()
                    }
                }
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = com.ai.assistance.operit.core.tools.StringResultData(resultText)
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取聊天列表异常", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = com.ai.assistance.operit.core.tools.StringResultData(""),
                error = "获取聊天列表异常: ${e.message}"
            )
        }
    }

    /**
     * 获取飞书消息列表
     *
     * 参数:
     * - chat_id: 聊天 ID
     * - page_size: 每页数量（可选，默认 20）
     */
    suspend fun getFeishuMessages(tool: AITool): ToolResult = withContext(Dispatchers.IO) {
        try {
            val config = feishuPreferences.getFeishuConfig()
            if (!config.isConfigured()) {
                return@withContext ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = com.ai.assistance.operit.core.tools.StringResultData(""),
                    error = "飞书未配置，请先在设置中配置 App ID 和 App Secret"
                )
            }

            val chatId = tool.parameters.find { it.name == "chat_id" }?.value
            if (chatId.isNullOrBlank()) {
                return@withContext ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = com.ai.assistance.operit.core.tools.StringResultData(""),
                    error = "缺少 chat_id 参数"
                )
            }

            val pageSize = tool.parameters.find { it.name == "page_size" }?.value?.toIntOrNull() ?: 20

            val messages = feishuClient.getMessages(config, chatId, pageSize)

            if (messages.isEmpty()) {
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = com.ai.assistance.operit.core.tools.StringResultData("未获取到任何消息")
                )
            } else {
                val resultText = buildString {
                    appendLine("获取到 ${messages.size} 条消息:\n")
                    messages.forEach { message ->
                        val sender = message.senderName ?: message.senderId ?: "未知"
                        val time = message.createTime?.let {
                            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(it))
                        } ?: ""
                        appendLine("[$time] $sender:")
                        appendLine("  ${message.content.take(100)}${if (message.content.length > 100) "..." else ""}")
                        appendLine()
                    }
                }
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = com.ai.assistance.operit.core.tools.StringResultData(resultText)
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取消息列表异常", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = com.ai.assistance.operit.core.tools.StringResultData(""),
                error = "获取消息列表异常: ${e.message}"
            )
        }
    }

    /**
     * 创建飞书文档
     *
     * 参数:
     * - title: 文档标题
     * - content: 文档内容（可选）
     */
    suspend fun createFeishuDocument(tool: AITool): ToolResult = withContext(Dispatchers.IO) {
        try {
            val config = feishuPreferences.getFeishuConfig()
            if (!config.isConfigured()) {
                return@withContext ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = com.ai.assistance.operit.core.tools.StringResultData(""),
                    error = "飞书未配置，请先在设置中配置 App ID 和 App Secret"
                )
            }

            val title = tool.parameters.find { it.name == "title" }?.value
            if (title.isNullOrBlank()) {
                return@withContext ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = com.ai.assistance.operit.core.tools.StringResultData(""),
                    error = "缺少 title 参数"
                )
            }

            val content = tool.parameters.find { it.name == "content" }?.value

            val documentId = feishuClient.createDocument(config, title, content)

            if (documentId != null) {
                AppLogger.d(TAG, "文档创建成功: $documentId")
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = com.ai.assistance.operit.core.tools.StringResultData(
                        "文档创建成功\n文档 ID: $documentId\n标题: $title\n链接: https://feishu.cn/docx/$documentId"
                    )
                )
            } else {
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = com.ai.assistance.operit.core.tools.StringResultData(""),
                    error = "文档创建失败，请检查配置和网络连接"
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "创建文档异常", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = com.ai.assistance.operit.core.tools.StringResultData(""),
                error = "创建文档异常: ${e.message}"
            )
        }
    }

    /**
     * 创建飞书任务
     *
     * 参数:
     * - name: 任务名称
     * - description: 任务描述（可选）
     * - due_time: 截止时间戳（可选）
     */
    suspend fun createFeishuTask(tool: AITool): ToolResult = withContext(Dispatchers.IO) {
        try {
            val config = feishuPreferences.getFeishuConfig()
            if (!config.isConfigured()) {
                return@withContext ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = com.ai.assistance.operit.core.tools.StringResultData(""),
                    error = "飞书未配置，请先在设置中配置 App ID 和 App Secret"
                )
            }

            val name = tool.parameters.find { it.name == "name" }?.value
            if (name.isNullOrBlank()) {
                return@withContext ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = com.ai.assistance.operit.core.tools.StringResultData(""),
                    error = "缺少 name 参数"
                )
            }

            val description = tool.parameters.find { it.name == "description" }?.value
            val dueTime = tool.parameters.find { it.name == "due_time" }?.value?.toLongOrNull()

            val taskId = feishuClient.createTask(config, name, description, dueTime)

            if (taskId != null) {
                AppLogger.d(TAG, "任务创建成功: $taskId")
                val dueTimeStr = dueTime?.let {
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(it * 1000))
                } ?: "未设置"
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = com.ai.assistance.operit.core.tools.StringResultData(
                        "任务创建成功\n任务 ID: $taskId\n名称: $name\n截止时间: $dueTimeStr"
                    )
                )
            } else {
                ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = com.ai.assistance.operit.core.tools.StringResultData(""),
                    error = "任务创建失败，请检查配置和网络连接"
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "创建任务异常", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = com.ai.assistance.operit.core.tools.StringResultData(""),
                error = "创建任务异常: ${e.message}"
            )
        }
    }

    /**
     * 获取飞书任务列表
     *
     * 参数:
     * - page_size: 每页数量（可选，默认 20）
     */
    suspend fun getFeishuTasks(tool: AITool): ToolResult = withContext(Dispatchers.IO) {
        try {
            val config = feishuPreferences.getFeishuConfig()
            if (!config.isConfigured()) {
                return@withContext ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = com.ai.assistance.operit.core.tools.StringResultData(""),
                    error = "飞书未配置，请先在设置中配置 App ID 和 App Secret"
                )
            }

            val pageSize = tool.parameters.find { it.name == "page_size" }?.value?.toIntOrNull() ?: 20

            val tasks = feishuClient.getTasks(config, pageSize)

            if (tasks.isEmpty()) {
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = com.ai.assistance.operit.core.tools.StringResultData("未获取到任何任务")
                )
            } else {
                val resultText = buildString {
                    appendLine("获取到 ${tasks.size} 个任务:\n")
                    tasks.forEachIndexed { index, task ->
                        val statusText = when (task.status) {
                            "todo" -> "待办"
                            "in_progress" -> "进行中"
                            "completed" -> "已完成"
                            else -> "未知"
                        }
                        appendLine("${index + 1}. ${task.name} [$statusText]")
                        if (task.description.isNotBlank()) {
                            appendLine("   描述: ${task.description}")
                        }
                        task.dueTime?.let {
                            val dueTimeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(it * 1000))
                            appendLine("   截止时间: $dueTimeStr")
                        }
                        appendLine()
                    }
                }
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = com.ai.assistance.operit.core.tools.StringResultData(resultText)
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取任务列表异常", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = com.ai.assistance.operit.core.tools.StringResultData(""),
                error = "获取任务列表异常: ${e.message}"
            )
        }
    }

    /**
     * 设置默认飞书聊天 ID
     *
     * 参数:
     * - chat_id: 聊天 ID
     */
    suspend fun setDefaultFeishuChat(tool: AITool): ToolResult = withContext(Dispatchers.IO) {
        try {
            val chatId = tool.parameters.find { it.name == "chat_id" }?.value
            if (chatId.isNullOrBlank()) {
                return@withContext ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = com.ai.assistance.operit.core.tools.StringResultData(""),
                    error = "缺少 chat_id 参数"
                )
            }

            feishuPreferences.saveDefaultChatId(chatId)

            ToolResult(
                toolName = tool.name,
                success = true,
                result = com.ai.assistance.operit.core.tools.StringResultData(
                    "已设置默认聊天 ID: $chatId"
                )
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "设置默认聊天异常", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = com.ai.assistance.operit.core.tools.StringResultData(""),
                error = "设置默认聊天异常: ${e.message}"
            )
        }
    }

    /**
     * 获取飞书配置状态
     */
    suspend fun getFeishuStatus(tool: AITool): ToolResult = withContext(Dispatchers.IO) {
        try {
            val config = feishuPreferences.getFeishuConfig()
            val enabled = feishuPreferences.feishuEnabledFlow.first()
            val defaultChatId = feishuPreferences.getDefaultChatId()

            val statusText = buildString {
                appendLine("飞书配置状态:")
                appendLine("  已启用: ${if (enabled) "是" else "否"}")
                appendLine("  已配置: ${if (config.isConfigured()) "是" else "否"}")
                if (config.isConfigured()) {
                    appendLine("  App ID: ${config.appId.take(8)}***")
                }
                if (defaultChatId.isNotBlank()) {
                    appendLine("  默认聊天 ID: $defaultChatId")
                }
            }

            ToolResult(
                toolName = tool.name,
                success = true,
                result = com.ai.assistance.operit.core.tools.StringResultData(statusText)
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取飞书状态异常", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = com.ai.assistance.operit.core.tools.StringResultData(""),
                error = "获取飞书状态异常: ${e.message}"
            )
        }
    }

    /**
     * 测试飞书连接
     */
    suspend fun testFeishuConnection(tool: AITool): ToolResult = withContext(Dispatchers.IO) {
        try {
            val config = feishuPreferences.getFeishuConfig()
            val (success, message) = feishuClient.testConnection(config)

            ToolResult(
                toolName = tool.name,
                success = success,
                result = com.ai.assistance.operit.core.tools.StringResultData(message)
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "测试连接异常", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = com.ai.assistance.operit.core.tools.StringResultData(""),
                error = "测试连接异常: ${e.message}"
            )
        }
    }

    /**
     * 获取私聊用户列表
     */
    suspend fun getFeishuP2PUsers(tool: AITool): ToolResult = withContext(Dispatchers.IO) {
        try {
            val config = feishuPreferences.getFeishuConfig()
            if (!config.isConfigured()) {
                return@withContext ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = com.ai.assistance.operit.core.tools.StringResultData(""),
                    error = "飞书未配置，请先在设置中配置 App ID 和 App Secret"
                )
            }

            val users = feishuClient.getBotUsers(config)

            if (users.isEmpty()) {
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = com.ai.assistance.operit.core.tools.StringResultData(
                        "未找到私聊用户。\n\n注意：用户必须先主动给机器人发消息，机器人才能获取到会话信息。\n\n请在飞书中找到机器人，主动发送一条消息后再试。"
                    )
                )
            } else {
                val resultText = buildString {
                    appendLine("找到 ${users.size} 个私聊用户:\n")
                    users.forEachIndexed { index, (chatId, name) ->
                        appendLine("${index + 1}. $name")
                        appendLine("   chat_id: $chatId")
                        appendLine()
                    }
                    appendLine("提示: 使用 chat_id 作为 receive_id 发送私聊消息")
                }
                ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = com.ai.assistance.operit.core.tools.StringResultData(resultText)
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取私聊用户异常", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = com.ai.assistance.operit.core.tools.StringResultData(""),
                error = "获取私聊用户异常: ${e.message}"
            )
        }
    }
}