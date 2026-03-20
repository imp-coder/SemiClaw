package com.ai.assistance.operit.core.chat.hooks

import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "PromptHookRegistry"

data class PromptMessage(
    val role: String,
    val content: String
)

data class PromptHookContext(
    val stage: String,
    val functionType: String? = null,
    val promptFunctionType: String? = null,
    val useEnglish: Boolean? = null,
    val rawInput: String? = null,
    val processedInput: String? = null,
    val chatHistory: List<PromptMessage> = emptyList(),
    val preparedHistory: List<PromptMessage> = emptyList(),
    val systemPrompt: String? = null,
    val toolPrompt: String? = null,
    val modelParameters: List<Map<String, Any?>> = emptyList(),
    val availableTools: List<Map<String, Any?>> = emptyList(),
    val metadata: Map<String, Any?> = emptyMap()
)

data class PromptHookMutation(
    val rawInput: String? = null,
    val processedInput: String? = null,
    val chatHistory: List<PromptMessage>? = null,
    val preparedHistory: List<PromptMessage>? = null,
    val systemPrompt: String? = null,
    val toolPrompt: String? = null,
    val metadata: Map<String, Any?> = emptyMap()
)

interface PromptInputHook {
    val id: String

    fun onEvent(context: PromptHookContext): PromptHookMutation? = null
}

interface PromptHistoryHook {
    val id: String

    fun onEvent(context: PromptHookContext): PromptHookMutation? = null
}

interface SystemPromptComposeHook {
    val id: String

    fun onEvent(context: PromptHookContext): PromptHookMutation? = null
}

interface ToolPromptComposeHook {
    val id: String

    fun onEvent(context: PromptHookContext): PromptHookMutation? = null
}

interface PromptFinalizeHook {
    val id: String

    fun onEvent(context: PromptHookContext): PromptHookMutation? = null
}

fun List<Pair<String, String>>.toPromptMessages(): List<PromptMessage> {
    return map { (role, content) ->
        PromptMessage(role = role, content = content)
    }
}

fun List<PromptMessage>.toRoleContentPairs(): List<Pair<String, String>> {
    return map { message ->
        message.role to message.content
    }
}

object PromptHookRegistry {
    private val promptInputHooks = CopyOnWriteArrayList<PromptInputHook>()
    private val promptHistoryHooks = CopyOnWriteArrayList<PromptHistoryHook>()
    private val systemPromptComposeHooks = CopyOnWriteArrayList<SystemPromptComposeHook>()
    private val toolPromptComposeHooks = CopyOnWriteArrayList<ToolPromptComposeHook>()
    private val promptFinalizeHooks = CopyOnWriteArrayList<PromptFinalizeHook>()

    @Synchronized
    fun registerPromptInputHook(hook: PromptInputHook) {
        unregisterPromptInputHook(hook.id)
        promptInputHooks.add(hook)
    }

    @Synchronized
    fun unregisterPromptInputHook(hookId: String) {
        promptInputHooks.removeAll { it.id == hookId }
    }

    @Synchronized
    fun registerPromptHistoryHook(hook: PromptHistoryHook) {
        unregisterPromptHistoryHook(hook.id)
        promptHistoryHooks.add(hook)
    }

    @Synchronized
    fun unregisterPromptHistoryHook(hookId: String) {
        promptHistoryHooks.removeAll { it.id == hookId }
    }

    @Synchronized
    fun registerSystemPromptComposeHook(hook: SystemPromptComposeHook) {
        unregisterSystemPromptComposeHook(hook.id)
        systemPromptComposeHooks.add(hook)
    }

    @Synchronized
    fun unregisterSystemPromptComposeHook(hookId: String) {
        systemPromptComposeHooks.removeAll { it.id == hookId }
    }

    @Synchronized
    fun registerToolPromptComposeHook(hook: ToolPromptComposeHook) {
        unregisterToolPromptComposeHook(hook.id)
        toolPromptComposeHooks.add(hook)
    }

    @Synchronized
    fun unregisterToolPromptComposeHook(hookId: String) {
        toolPromptComposeHooks.removeAll { it.id == hookId }
    }

    @Synchronized
    fun registerPromptFinalizeHook(hook: PromptFinalizeHook) {
        unregisterPromptFinalizeHook(hook.id)
        promptFinalizeHooks.add(hook)
    }

    @Synchronized
    fun unregisterPromptFinalizeHook(hookId: String) {
        promptFinalizeHooks.removeAll { it.id == hookId }
    }

    fun dispatchPromptInputHooks(initialContext: PromptHookContext): PromptHookContext {
        return dispatch(
            initialContext = initialContext,
            hooks = promptInputHooks,
            hookLabel = "PromptInputHook"
        ) { hook, context ->
            hook.onEvent(context)
        }
    }

    fun dispatchPromptHistoryHooks(initialContext: PromptHookContext): PromptHookContext {
        return dispatch(
            initialContext = initialContext,
            hooks = promptHistoryHooks,
            hookLabel = "PromptHistoryHook"
        ) { hook, context ->
            hook.onEvent(context)
        }
    }

    fun dispatchSystemPromptComposeHooks(initialContext: PromptHookContext): PromptHookContext {
        return dispatch(
            initialContext = initialContext,
            hooks = systemPromptComposeHooks,
            hookLabel = "SystemPromptComposeHook"
        ) { hook, context ->
            hook.onEvent(context)
        }
    }

    fun dispatchToolPromptComposeHooks(initialContext: PromptHookContext): PromptHookContext {
        return dispatch(
            initialContext = initialContext,
            hooks = toolPromptComposeHooks,
            hookLabel = "ToolPromptComposeHook"
        ) { hook, context ->
            hook.onEvent(context)
        }
    }

    fun dispatchPromptFinalizeHooks(initialContext: PromptHookContext): PromptHookContext {
        return dispatch(
            initialContext = initialContext,
            hooks = promptFinalizeHooks,
            hookLabel = "PromptFinalizeHook"
        ) { hook, context ->
            hook.onEvent(context)
        }
    }

    private fun <THook> dispatch(
        initialContext: PromptHookContext,
        hooks: List<THook>,
        hookLabel: String,
        invoke: (THook, PromptHookContext) -> PromptHookMutation?
    ): PromptHookContext {
        var current = initialContext
        hooks.forEach { hook ->
            val mutation =
                runCatching { invoke(hook, current) }
                    .onFailure { error ->
                        AppLogger.e(TAG, "$hookLabel callback failed", error)
                    }
                    .getOrNull()
                    ?: return@forEach
            current = applyMutation(current, mutation)
        }
        return current
    }

    private fun applyMutation(
        current: PromptHookContext,
        mutation: PromptHookMutation
    ): PromptHookContext {
        val mergedMetadata =
            if (mutation.metadata.isEmpty()) {
                current.metadata
            } else {
                current.metadata + mutation.metadata
            }
        return current.copy(
            rawInput = mutation.rawInput ?: current.rawInput,
            processedInput = mutation.processedInput ?: current.processedInput,
            chatHistory = mutation.chatHistory ?: current.chatHistory,
            preparedHistory = mutation.preparedHistory ?: current.preparedHistory,
            systemPrompt = mutation.systemPrompt ?: current.systemPrompt,
            toolPrompt = mutation.toolPrompt ?: current.toolPrompt,
            metadata = mergedMetadata
        )
    }
}
