package com.ai.assistance.operit.core.tools

import android.content.Context
import android.content.Intent
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 工具执行进度通知器
 * 用于在工具执行过程中发送进度消息到悬浮聊天窗口和飞书
 */
object ToolProgressNotifier {
    private const val TAG = "ToolProgressNotifier"

    const val ACTION_TOOL_PROGRESS = "com.ai.assistance.operit.TOOL_PROGRESS"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_TOOL_NAME = "tool_name"
    const val EXTRA_PROGRESS_TYPE = "progress_type"

    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 进度类型
     */
    enum class ProgressType {
        USER_MESSAGE, // 用户消息
        START,      // 开始执行
        IN_PROGRESS, // 进行中
        SUCCESS,    // 成功完成
        ERROR       // 错误
    }

    // 飞书服务实例（由外部设置）
    private var feishuService: com.ai.assistance.operit.services.FeishuServiceManager? = null
    private var feishuChatId: String? = null

    /**
     * 设置飞书服务实例
     */
    fun setFeishuService(service: com.ai.assistance.operit.services.FeishuServiceManager?, chatId: String?) {
        feishuService = service
        feishuChatId = chatId
    }

    /**
     * 清除飞书服务实例
     */
    fun clearFeishuService() {
        feishuService = null
        feishuChatId = null
    }

    /**
     * 发送用户消息（用于显示用户发送的命令/问题，只发送到悬浮窗，不发送到飞书）
     */
    fun notifyUserMessage(context: Context, message: String) {
        AppLogger.d(TAG, "发送用户消息到悬浮窗: $message")

        // 只发送广播到悬浮窗，不发送到飞书（因为消息本身就是从飞书来的）
        val intent = Intent(ACTION_TOOL_PROGRESS).apply {
            putExtra(EXTRA_TOOL_NAME, "user")
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_PROGRESS_TYPE, ProgressType.USER_MESSAGE.name)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    /**
     * 发送工具执行进度消息（同时发送到悬浮窗和飞书）
     */
    fun notifyProgress(context: Context, toolName: String, message: String, progressType: ProgressType) {
        AppLogger.d(TAG, "发送进度消息: $toolName - $message ($progressType)")

        // 1. 发送广播到悬浮窗
        val intent = Intent(ACTION_TOOL_PROGRESS).apply {
            putExtra(EXTRA_TOOL_NAME, toolName)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_PROGRESS_TYPE, progressType.name)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)

        // 2. 发送飞书消息（如果有飞书服务）
        feishuService?.let { service ->
            feishuChatId?.let { chatId ->
                scope.launch {
                    try {
                        service.sendMessage(chatId, message)
                        AppLogger.d(TAG, "飞书消息已发送: $message")
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "发送飞书消息失败", e)
                    }
                }
            }
        }
    }

    /**
     * 发送开始执行消息
     */
    fun notifyStart(context: Context, toolName: String, message: String) {
        notifyProgress(context, toolName, message, ProgressType.START)
    }

    /**
     * 发送进行中消息
     */
    fun notifyInProgress(context: Context, toolName: String, message: String) {
        notifyProgress(context, toolName, message, ProgressType.IN_PROGRESS)
    }

    /**
     * 发送成功完成消息
     */
    fun notifySuccess(context: Context, toolName: String, message: String) {
        notifyProgress(context, toolName, message, ProgressType.SUCCESS)
    }

    /**
     * 发送错误消息
     */
    fun notifyError(context: Context, toolName: String, message: String) {
        notifyProgress(context, toolName, message, ProgressType.ERROR)
    }
}