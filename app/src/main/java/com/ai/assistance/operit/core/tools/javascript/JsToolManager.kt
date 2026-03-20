package com.ai.assistance.operit.core.tools.javascript

import android.content.Context
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class JsToolManager private constructor(
    private val context: Context,
    private val packageManager: PackageManager
) {

    companion object {
        private const val TAG = "JsToolManager"
        private const val MAX_CONCURRENT_ENGINES = 4

        @Volatile
        private var instance: JsToolManager? = null

        fun getInstance(context: Context, packageManager: PackageManager): JsToolManager {
            return instance
                ?: synchronized(this) {
                    instance
                        ?: JsToolManager(context.applicationContext, packageManager).also {
                            instance = it
                        }
                }
        }
    }

    private val engines = List(MAX_CONCURRENT_ENGINES) { JsEngine(context) }
    private val enginePool = Channel<JsEngine>(capacity = MAX_CONCURRENT_ENGINES).also { pool ->
        engines.forEach(pool::trySend)
    }

    private suspend fun <T> withEngine(block: suspend (JsEngine) -> T): T {
        val engine = enginePool.receive()
        return try {
            block(engine)
        } finally {
            enginePool.trySend(engine)
        }
    }

    private fun <T> withEngineBlocking(block: (JsEngine) -> T): T {
        return runBlocking {
            withEngine { engine -> block(engine) }
        }
    }

    private fun parseDotCall(toolName: String): Pair<String, String>? {
        val separatorIndex = toolName.lastIndexOf('.')
        if (separatorIndex <= 0 || separatorIndex >= toolName.lastIndex) {
            return null
        }
        return toolName.substring(0, separatorIndex) to toolName.substring(separatorIndex + 1)
    }

    private fun parsePackageToolName(toolName: String): Pair<String, String>? {
        val separatorIndex = toolName.indexOf(':')
        if (separatorIndex <= 0 || separatorIndex >= toolName.lastIndex) {
            return null
        }
        return toolName.substring(0, separatorIndex) to toolName.substring(separatorIndex + 1)
    }

    private fun buildRuntimeParams(
        packageName: String,
        params: Map<String, Any?>
    ): MutableMap<String, Any?> {
        val runtimeParams = params.toMutableMap()
        packageManager.getActivePackageStateId(packageName)?.let {
            runtimeParams["__operit_package_state"] = it
        }

        listOf(
            "__operit_package_caller_name",
            "__operit_package_chat_id",
            "__operit_package_caller_card_id"
        ).forEach { key ->
            val value = runtimeParams[key]?.toString()?.takeIf { it.isNotBlank() }
            if (value == null) {
                runtimeParams.remove(key)
            } else {
                runtimeParams[key] = value
            }
        }

        runtimeParams["__operit_package_name"] = packageName

        packageManager.resolveToolPkgSubpackageRuntimeInternal(packageName)?.let { runtime ->
            runtimeParams["__operit_toolpkg_subpackage_id"] = runtime.subpackageId
            runtimeParams["containerPackageName"] = runtime.containerPackageName
            runtimeParams["toolPkgId"] = runtime.containerPackageName
            runtimeParams["__operit_ui_package_name"] = runtime.containerPackageName
        } ?: run {
            runtimeParams.remove("__operit_toolpkg_subpackage_id")
            runtimeParams.remove("containerPackageName")
            runtimeParams.remove("toolPkgId")
            runtimeParams.remove("__operit_ui_package_name")
        }

        return runtimeParams
    }

    private fun convertToolParameters(
        tool: AITool,
        packageName: String,
        functionName: String
    ): MutableMap<String, Any?> {
        val toolDefinition = packageManager.getPackageTools(packageName)
            ?.tools
            ?.find { it.name == functionName }

        val converted = buildMap<String, Any?> {
            tool.parameters.forEach { parameter ->
                val type = toolDefinition
                    ?.parameters
                    ?.find { it.name == parameter.name }
                    ?.type
                    ?.lowercase()
                    ?: "string"

                val value =
                    try {
                        when (type) {
                            "number" -> parameter.value.toDoubleOrNull()
                                ?: parameter.value.toLongOrNull()
                                ?: parameter.value
                            "integer" -> parameter.value.toLongOrNull() ?: parameter.value
                            "boolean" -> parameter.value.toBooleanStrictOrNull()
                                ?: parameter.value.equals("1")
                            else -> parameter.value
                        }
                    } catch (e: Exception) {
                        AppLogger.w(
                            TAG,
                            "Parameter conversion failed: tool=${tool.name}, param=${parameter.name}, type=$type, error=${e.message}"
                        )
                        parameter.value
                    }
                put(parameter.name, value)
            }
        }

        return buildRuntimeParams(packageName, converted)
    }

    private fun success(toolName: String, value: Any?): ToolResult {
        return ToolResult(
            toolName = toolName,
            success = true,
            result = StringResultData(value?.toString() ?: "null")
        )
    }

    private fun failure(toolName: String, message: String): ToolResult {
        return ToolResult(
            toolName = toolName,
            success = false,
            result = StringResultData(""),
            error = message
        )
    }

    fun executeScript(toolName: String, params: Map<String, String>): String {
        val parsed = parseDotCall(toolName)
            ?: return "Invalid tool name format: $toolName. Expected format: packageName.functionName"
        val (packageName, functionName) = parsed
        val script = packageManager.getPackageScript(packageName)
            ?: return "Package not found: $packageName"

        return withEngineBlocking { engine ->
            try {
                val runtimeParams = buildRuntimeParams(
                    packageName = packageName,
                    params = params.mapValues { it.value as Any? }
                )
                engine.executeScriptFunction(
                    script = script,
                    functionName = functionName,
                    params = runtimeParams
                )?.toString() ?: "null"
            } catch (e: Exception) {
                AppLogger.e(
                    TAG,
                    "Script execution failed: package=$packageName, function=$functionName, error=${e.message}",
                    e
                )
                "Error: ${e.message}"
            }
        }
    }

    fun executeScript(script: String, tool: AITool): Flow<ToolResult> = channelFlow {
        val parsed = parsePackageToolName(tool.name)
        if (parsed == null) {
            send(failure(tool.name, "Invalid tool name format. Expected 'packageName:toolName'"))
            return@channelFlow
        }

        val (packageName, functionName) = parsed
        withEngine { engine ->
            val runtimeParams = convertToolParameters(tool, packageName, functionName)
            try {
                withTimeout(JsTimeoutConfig.SCRIPT_TIMEOUT_MS) {
                    val result = engine.executeScriptFunction(
                        script = script,
                        functionName = functionName,
                        params = runtimeParams,
                        onIntermediateResult = { value ->
                            trySend(success(tool.name, value))
                        }
                    )

                    val normalizedError = result?.toString()
                        ?.takeIf { it.startsWith("Error:", ignoreCase = true) }
                        ?.removePrefix("Error:")
                        ?.trim()
                    if (normalizedError != null) {
                        send(failure(tool.name, normalizedError))
                    } else {
                        send(success(tool.name, result))
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                send(
                    failure(
                        tool.name,
                        "Script execution timed out after ${JsTimeoutConfig.SCRIPT_TIMEOUT_MS}ms"
                    )
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Script execution failed: tool=${tool.name}, error=${e.message}", e)
                send(failure(tool.name, "Script execution failed: ${e.message}"))
            }
        }
    }

    fun executeComposeDsl(
        script: String,
        packageName: String = "",
        uiModuleId: String = "",
        toolPkgId: String = "",
        state: Map<String, Any?> = emptyMap(),
        memo: Map<String, Any?> = emptyMap(),
        moduleSpec: Map<String, Any?> = emptyMap(),
        envOverrides: Map<String, String> = emptyMap()
    ): String {
        val runtimeOptions = mutableMapOf<String, Any?>()
        if (packageName.isNotBlank()) {
            runtimeOptions["packageName"] = packageName
        }
        if (uiModuleId.isNotBlank()) {
            runtimeOptions["uiModuleId"] = uiModuleId
        }
        if (toolPkgId.isNotBlank()) {
            runtimeOptions["toolPkgId"] = toolPkgId
        }
        if (state.isNotEmpty()) {
            runtimeOptions["state"] = state
        }
        if (memo.isNotEmpty()) {
            runtimeOptions["memo"] = memo
        }
        if (moduleSpec.isNotEmpty()) {
            runtimeOptions["moduleSpec"] = moduleSpec
        }
        if (packageName.isNotBlank()) {
            packageManager.getActivePackageStateId(packageName)?.let {
                runtimeOptions["__operit_package_state"] = it
            }
        }

        return withEngineBlocking { engine ->
            try {
                engine.executeComposeDslScript(
                    script = script,
                    runtimeOptions = runtimeOptions,
                    envOverrides = envOverrides
                )?.toString() ?: "null"
            } catch (e: Exception) {
                AppLogger.e(TAG, "Compose DSL execution failed: ${e.message}", e)
                "Error: ${e.message}"
            }
        }
    }

    fun destroy() {
        enginePool.close()
        engines.forEach(JsEngine::destroy)
    }
}
