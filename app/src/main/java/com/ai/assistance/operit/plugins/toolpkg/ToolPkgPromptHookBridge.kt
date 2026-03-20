package com.ai.assistance.operit.plugins.toolpkg

import com.ai.assistance.operit.core.chat.hooks.PromptFinalizeHook
import com.ai.assistance.operit.core.chat.hooks.PromptHistoryHook
import com.ai.assistance.operit.core.chat.hooks.PromptHookContext
import com.ai.assistance.operit.core.chat.hooks.PromptHookMutation
import com.ai.assistance.operit.core.chat.hooks.PromptHookRegistry
import com.ai.assistance.operit.core.chat.hooks.PromptInputHook
import com.ai.assistance.operit.core.chat.hooks.PromptMessage
import com.ai.assistance.operit.core.chat.hooks.SystemPromptComposeHook
import com.ai.assistance.operit.core.chat.hooks.ToolPromptComposeHook
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.core.tools.packTool.ToolPkgContainerRuntime
import com.ai.assistance.operit.core.tools.packTool.TOOLPKG_EVENT_PROMPT_FINALIZE
import com.ai.assistance.operit.core.tools.packTool.TOOLPKG_EVENT_PROMPT_HISTORY
import com.ai.assistance.operit.core.tools.packTool.TOOLPKG_EVENT_PROMPT_INPUT
import com.ai.assistance.operit.core.tools.packTool.TOOLPKG_EVENT_SYSTEM_PROMPT_COMPOSE
import com.ai.assistance.operit.core.tools.packTool.TOOLPKG_EVENT_TOOL_PROMPT_COMPOSE
import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "ToolPkgPromptHookBridge"

internal object ToolPkgPromptHookBridge {
    private val installed = AtomicBoolean(false)
    @Volatile
    private var promptInputHooks: List<ToolPkgPromptHookRegistration> = emptyList()
    @Volatile
    private var promptHistoryHooks: List<ToolPkgPromptHookRegistration> = emptyList()
    @Volatile
    private var systemPromptComposeHooks: List<ToolPkgPromptHookRegistration> = emptyList()
    @Volatile
    private var toolPromptComposeHooks: List<ToolPkgPromptHookRegistration> = emptyList()
    @Volatile
    private var promptFinalizeHooks: List<ToolPkgPromptHookRegistration> = emptyList()
    private val runtimeChangeListener =
        PackageManager.ToolPkgRuntimeChangeListener {
            syncToolPkgRegistrations(toolPkgPackageManager().getImportedToolPkgContainerRuntimes())
        }

    private object PromptInputBridge : PromptInputHook {
        override val id: String = "builtin.toolpkg.prompt-input-hook-bridge"

        override fun onEvent(context: PromptHookContext): PromptHookMutation? {
            return dispatchPromptHooks(
                hooks = promptInputHooks,
                familyEvent = TOOLPKG_EVENT_PROMPT_INPUT,
                context = context,
                parseMutation = ::parsePromptInputMutation
            )
        }
    }

    private object PromptHistoryBridge : PromptHistoryHook {
        override val id: String = "builtin.toolpkg.prompt-history-hook-bridge"

        override fun onEvent(context: PromptHookContext): PromptHookMutation? {
            return dispatchPromptHooks(
                hooks = promptHistoryHooks,
                familyEvent = TOOLPKG_EVENT_PROMPT_HISTORY,
                context = context,
                parseMutation = ::parsePromptHistoryMutation
            )
        }
    }

    private object SystemPromptComposeBridge : SystemPromptComposeHook {
        override val id: String = "builtin.toolpkg.system-prompt-compose-hook-bridge"

        override fun onEvent(context: PromptHookContext): PromptHookMutation? {
            return dispatchPromptHooks(
                hooks = systemPromptComposeHooks,
                familyEvent = TOOLPKG_EVENT_SYSTEM_PROMPT_COMPOSE,
                context = context,
                parseMutation = ::parseSystemPromptMutation
            )
        }
    }

    private object ToolPromptComposeBridge : ToolPromptComposeHook {
        override val id: String = "builtin.toolpkg.tool-prompt-compose-hook-bridge"

        override fun onEvent(context: PromptHookContext): PromptHookMutation? {
            return dispatchPromptHooks(
                hooks = toolPromptComposeHooks,
                familyEvent = TOOLPKG_EVENT_TOOL_PROMPT_COMPOSE,
                context = context,
                parseMutation = ::parseToolPromptMutation
            )
        }
    }

    private object PromptFinalizeBridge : PromptFinalizeHook {
        override val id: String = "builtin.toolpkg.prompt-finalize-hook-bridge"

        override fun onEvent(context: PromptHookContext): PromptHookMutation? {
            return dispatchPromptHooks(
                hooks = promptFinalizeHooks,
                familyEvent = TOOLPKG_EVENT_PROMPT_FINALIZE,
                context = context,
                parseMutation = ::parsePromptFinalizeMutation
            )
        }
    }

    fun register() {
        if (!installed.compareAndSet(false, true)) {
            return
        }
        PromptHookRegistry.registerPromptInputHook(PromptInputBridge)
        PromptHookRegistry.registerPromptHistoryHook(PromptHistoryBridge)
        PromptHookRegistry.registerSystemPromptComposeHook(SystemPromptComposeBridge)
        PromptHookRegistry.registerToolPromptComposeHook(ToolPromptComposeBridge)
        PromptHookRegistry.registerPromptFinalizeHook(PromptFinalizeBridge)

        val manager = toolPkgPackageManager()
        manager.addToolPkgRuntimeChangeListener(runtimeChangeListener)
    }

    private fun dispatchPromptHooks(
        hooks: List<ToolPkgPromptHookRegistration>,
        familyEvent: String,
        context: PromptHookContext,
        parseMutation: (Any?, PromptHookContext) -> PromptHookMutation?
    ): PromptHookMutation? {
        if (hooks.isEmpty()) {
            return null
        }

        val manager = toolPkgPackageManager()
        var current = context
        hooks.forEach { hook ->
            val resolvedHookId = hook.hookId
            val resolvedContainer = hook.containerPackageName
            val resolvedFunction = hook.functionName
            val result =
                manager.runToolPkgMainHook(
                    containerPackageName = resolvedContainer,
                    functionName = resolvedFunction,
                    event = familyEvent,
                    eventName = current.stage,
                    pluginId = resolvedHookId,
                    inlineFunctionSource = hook.functionSource,
                    eventPayload = buildPromptEventPayload(current)
                )
            val decoded =
                result.getOrElse { error ->
                    AppLogger.e(
                        TAG,
                        "ToolPkg prompt hook failed: $resolvedContainer:$resolvedHookId",
                        error
                    )
                    return@getOrElse null
                }?.let { raw ->
                    runCatching { decodeToolPkgHookResult(raw) }
                        .getOrElse { error ->
                            AppLogger.e(
                                TAG,
                                "ToolPkg prompt hook decode failed: $resolvedContainer:$resolvedHookId",
                                error
                            )
                            null
                        }
                }
            val mutation = parseMutation(decoded, current) ?: return@forEach
            current = applyMutation(current, mutation)
        }

        return PromptHookMutation(
            rawInput = current.rawInput,
            processedInput = current.processedInput,
            chatHistory = current.chatHistory,
            preparedHistory = current.preparedHistory,
            systemPrompt = current.systemPrompt,
            toolPrompt = current.toolPrompt,
            metadata = current.metadata
        )
    }

    private fun syncToolPkgRegistrations(activeContainers: List<ToolPkgContainerRuntime>) {
        promptInputHooks =
            activeContainers.flatMap { runtime ->
                runtime.promptInputHooks.map { hook ->
                    ToolPkgPromptHookRegistration(
                        containerPackageName = runtime.packageName,
                        hookId = hook.id,
                        functionName = hook.function,
                        functionSource = hook.functionSource
                    )
                }
            }.sortedWith(
                compareBy(
                    ToolPkgPromptHookRegistration::containerPackageName,
                    ToolPkgPromptHookRegistration::hookId
                )
            )

        promptHistoryHooks =
            activeContainers.flatMap { runtime ->
                runtime.promptHistoryHooks.map { hook ->
                    ToolPkgPromptHookRegistration(
                        containerPackageName = runtime.packageName,
                        hookId = hook.id,
                        functionName = hook.function,
                        functionSource = hook.functionSource
                    )
                }
            }.sortedWith(
                compareBy(
                    ToolPkgPromptHookRegistration::containerPackageName,
                    ToolPkgPromptHookRegistration::hookId
                )
            )

        systemPromptComposeHooks =
            activeContainers.flatMap { runtime ->
                runtime.systemPromptComposeHooks.map { hook ->
                    ToolPkgPromptHookRegistration(
                        containerPackageName = runtime.packageName,
                        hookId = hook.id,
                        functionName = hook.function,
                        functionSource = hook.functionSource
                    )
                }
            }.sortedWith(
                compareBy(
                    ToolPkgPromptHookRegistration::containerPackageName,
                    ToolPkgPromptHookRegistration::hookId
                )
            )

        toolPromptComposeHooks =
            activeContainers.flatMap { runtime ->
                runtime.toolPromptComposeHooks.map { hook ->
                    ToolPkgPromptHookRegistration(
                        containerPackageName = runtime.packageName,
                        hookId = hook.id,
                        functionName = hook.function,
                        functionSource = hook.functionSource
                    )
                }
            }.sortedWith(
                compareBy(
                    ToolPkgPromptHookRegistration::containerPackageName,
                    ToolPkgPromptHookRegistration::hookId
                )
            )

        promptFinalizeHooks =
            activeContainers.flatMap { runtime ->
                runtime.promptFinalizeHooks.map { hook ->
                    ToolPkgPromptHookRegistration(
                        containerPackageName = runtime.packageName,
                        hookId = hook.id,
                        functionName = hook.function,
                        functionSource = hook.functionSource
                    )
                }
            }.sortedWith(
                compareBy(
                    ToolPkgPromptHookRegistration::containerPackageName,
                    ToolPkgPromptHookRegistration::hookId
                )
            )
    }

    private fun buildPromptEventPayload(context: PromptHookContext): Map<String, Any?> {
        return buildMap {
            put("stage", context.stage)
            put("functionType", context.functionType)
            put("promptFunctionType", context.promptFunctionType)
            put("useEnglish", context.useEnglish)
            put("rawInput", context.rawInput)
            put("processedInput", context.processedInput)
            put("chatHistory", context.chatHistory.map(::promptMessageToMap))
            put("preparedHistory", context.preparedHistory.map(::promptMessageToMap))
            put("systemPrompt", context.systemPrompt)
            put("toolPrompt", context.toolPrompt)
            put("modelParameters", context.modelParameters)
            put("availableTools", context.availableTools)
            put("metadata", context.metadata)
        }
    }

    private fun promptMessageToMap(message: PromptMessage): Map<String, Any?> {
        return mapOf(
            "role" to message.role,
            "content" to message.content
        )
    }

    private fun applyMutation(
        context: PromptHookContext,
        mutation: PromptHookMutation
    ): PromptHookContext {
        return context.copy(
            rawInput = mutation.rawInput ?: context.rawInput,
            processedInput = mutation.processedInput ?: context.processedInput,
            chatHistory = mutation.chatHistory ?: context.chatHistory,
            preparedHistory = mutation.preparedHistory ?: context.preparedHistory,
            systemPrompt = mutation.systemPrompt ?: context.systemPrompt,
            toolPrompt = mutation.toolPrompt ?: context.toolPrompt,
            metadata = if (mutation.metadata.isEmpty()) context.metadata else context.metadata + mutation.metadata
        )
    }

    private fun parsePromptInputMutation(
        decoded: Any?,
        context: PromptHookContext
    ): PromptHookMutation? {
        return when (decoded) {
            null -> null
            is String -> PromptHookMutation(processedInput = decoded)
            is JSONObject -> parsePromptHookObject(decoded)
            else -> null
        }
    }

    private fun parsePromptHistoryMutation(
        decoded: Any?,
        context: PromptHookContext
    ): PromptHookMutation? {
        return when (decoded) {
            null -> null
            is JSONArray -> {
                val messages = parsePromptMessages(decoded) ?: return null
                if (context.stage == "before_prepare_history") {
                    PromptHookMutation(chatHistory = messages)
                } else {
                    PromptHookMutation(preparedHistory = messages)
                }
            }
            is JSONObject -> parsePromptHookObject(decoded)
            else -> null
        }
    }

    private fun parseSystemPromptMutation(
        decoded: Any?,
        context: PromptHookContext
    ): PromptHookMutation? {
        return when (decoded) {
            null -> null
            is String -> PromptHookMutation(systemPrompt = decoded)
            is JSONObject -> parsePromptHookObject(decoded)
            else -> null
        }
    }

    private fun parseToolPromptMutation(
        decoded: Any?,
        context: PromptHookContext
    ): PromptHookMutation? {
        return when (decoded) {
            null -> null
            is String -> PromptHookMutation(toolPrompt = decoded)
            is JSONObject -> parsePromptHookObject(decoded)
            else -> null
        }
    }

    private fun parsePromptFinalizeMutation(
        decoded: Any?,
        context: PromptHookContext
    ): PromptHookMutation? {
        return when (decoded) {
            null -> null
            is String -> PromptHookMutation(processedInput = decoded)
            is JSONArray -> {
                val messages = parsePromptMessages(decoded) ?: return null
                PromptHookMutation(preparedHistory = messages)
            }
            is JSONObject -> parsePromptHookObject(decoded)
            else -> null
        }
    }

    private fun parsePromptHookObject(jsonObject: JSONObject): PromptHookMutation {
        val metadata = jsonObject.optJSONObject("metadata")?.let(::jsonObjectToMap).orEmpty()
        return PromptHookMutation(
            rawInput = jsonObject.optString("rawInput").takeIf { it.isNotBlank() },
            processedInput = jsonObject.optString("processedInput").takeIf { it.isNotBlank() },
            chatHistory = parsePromptMessages(jsonObject.optJSONArray("chatHistory")),
            preparedHistory = parsePromptMessages(jsonObject.optJSONArray("preparedHistory")),
            systemPrompt = jsonObject.optString("systemPrompt").takeIf { it.isNotBlank() },
            toolPrompt = jsonObject.optString("toolPrompt").takeIf { it.isNotBlank() },
            metadata = metadata
        )
    }

    private fun parsePromptMessages(jsonArray: JSONArray?): List<PromptMessage>? {
        if (jsonArray == null) {
            return null
        }
        val result = mutableListOf<PromptMessage>()
        for (index in 0 until jsonArray.length()) {
            val item = jsonArray.opt(index) as? JSONObject ?: continue
            val role = item.optString("role").trim()
            val content = item.optString("content")
            if (role.isBlank()) {
                continue
            }
            result.add(
                PromptMessage(
                    role = role,
                    content = content
                )
            )
        }
        return result
    }
}
