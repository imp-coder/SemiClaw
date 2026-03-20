package com.ai.assistance.operit.core.tools.javascript

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class ScriptExecutionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScriptExecutionReceiver"

        const val ACTION_EXECUTE_JS = "com.ai.assistance.operit.EXECUTE_JS"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_FUNCTION_NAME = "function_name"
        const val EXTRA_PARAMS = "params"
        const val EXTRA_TEMP_FILE = "temp_file"
        const val EXTRA_ENV_FILE_PATH = "env_file_path"
        const val EXTRA_TEMP_ENV_FILE = "temp_env_file"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXECUTE_JS) {
            return
        }

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        val functionName = intent.getStringExtra(EXTRA_FUNCTION_NAME)
        if (filePath.isNullOrBlank() || functionName.isNullOrBlank()) {
            AppLogger.e(TAG, "Missing required parameters: filePath=$filePath, functionName=$functionName")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            execute(
                context = context,
                filePath = filePath,
                functionName = functionName,
                paramsJson = intent.getStringExtra(EXTRA_PARAMS).orEmpty().ifBlank { "{}" },
                tempScript = intent.getBooleanExtra(EXTRA_TEMP_FILE, false),
                envFilePath = intent.getStringExtra(EXTRA_ENV_FILE_PATH),
                tempEnvFile = intent.getBooleanExtra(EXTRA_TEMP_ENV_FILE, false)
            )
        }
    }

    private fun execute(
        context: Context,
        filePath: String,
        functionName: String,
        paramsJson: String,
        tempScript: Boolean,
        envFilePath: String?,
        tempEnvFile: Boolean
    ) {
        val scriptFile = File(filePath)
        if (!scriptFile.exists()) {
            AppLogger.e(TAG, "JavaScript file not found: $filePath")
            return
        }

        try {
            val result = JsEngine(context).executeScriptFunction(
                script = scriptFile.readText(),
                functionName = functionName,
                params = parseParams(paramsJson),
                envOverrides = parseEnvFile(envFilePath)
            )
            AppLogger.d(TAG, "JavaScript execution result: ${result ?: "null"}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error executing JavaScript: ${e.message}", e)
        } finally {
            deleteIfNeeded(scriptFile, tempScript, "temporary script")
            if (tempEnvFile && !envFilePath.isNullOrBlank()) {
                deleteIfNeeded(File(envFilePath), true, "temporary env file")
            }
        }
    }

    private fun parseParams(paramsJson: String): Map<String, Any?> {
        return try {
            val payload = JSONObject(paramsJson)
            buildMap {
                payload.keys().forEach { key ->
                    put(key, payload.opt(key))
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error parsing params: $paramsJson", e)
            emptyMap()
        }
    }

    private fun parseEnvFile(envFilePath: String?): Map<String, String> {
        if (envFilePath.isNullOrBlank()) {
            return emptyMap()
        }

        return try {
            val envFile = File(envFilePath)
            if (!envFile.exists()) {
                AppLogger.w(TAG, "Env file not found: $envFilePath")
                return emptyMap()
            }

            buildMap {
                envFile.readLines().forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty() || line.startsWith("#")) {
                        return@forEach
                    }
                    val separatorIndex = line.indexOf('=')
                    if (separatorIndex <= 0) {
                        return@forEach
                    }
                    val key = line.substring(0, separatorIndex).trim()
                    if (key.isEmpty()) {
                        return@forEach
                    }
                    val value = line.substring(separatorIndex + 1).trim().removeWrappingQuotes()
                    put(key, value)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error parsing env file: $envFilePath", e)
            emptyMap()
        }
    }

    private fun String.removeWrappingQuotes(): String {
        if (length < 2) {
            return this
        }
        val first = first()
        val last = last()
        return if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            substring(1, length - 1)
        } else {
            this
        }
    }

    private fun deleteIfNeeded(file: File, enabled: Boolean, label: String) {
        if (!enabled) {
            return
        }
        runCatching {
            if (file.exists()) {
                file.delete()
            }
        }.onFailure { error ->
            AppLogger.e(TAG, "Error deleting $label: ${file.absolutePath}", error)
        }
    }
}
