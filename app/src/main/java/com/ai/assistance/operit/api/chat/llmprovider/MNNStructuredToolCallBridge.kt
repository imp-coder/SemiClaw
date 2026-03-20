package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.ChatUtils
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

internal object MNNStructuredToolCallBridge {
    private data class ToolResultRecord(
        val name: String?,
        val content: String
    )

    fun buildToolsJson(toolPrompts: List<ToolPrompt>?): String? {
        if (toolPrompts.isNullOrEmpty()) {
            return null
        }
        val tools = buildToolDefinitions(toolPrompts)
        return if (tools.length() > 0) tools.toString() else null
    }

    fun buildMessagesJson(
        history: List<Pair<String, String>>,
        preserveThinkInHistory: Boolean
    ): String {
        return buildStructuredMessages(history, preserveThinkInHistory).toString()
    }

    fun convertToolCallPayloadToXml(content: String): String {
        if (content.isBlank()) {
            return content
        }

        if (ChatMarkupRegex.containsAnyToolLikeTag(content)) {
            return content
        }

        val toolCalls = parsePossibleToolCallsFromText(content) ?: return content
        val xml = convertToolCallsToXml(toolCalls)
        return if (xml.isBlank()) content else xml
    }

    private fun buildStructuredMessages(
        history: List<Pair<String, String>>,
        preserveThinkInHistory: Boolean
    ): JSONArray {
        val standardizedHistory = ChatUtils.mapChatHistoryToStandardRoles(
            history,
            extractThinking = preserveThinkInHistory
        )
        val mergedHistory = mergeConsecutiveMessages(standardizedHistory)
        val messagesArray = JSONArray()
        val lastToolCallIds = mutableListOf<String>()

        for ((role, content) in mergedHistory) {
            when (role) {
                "assistant" -> {
                    val (textContent, toolCalls) = parseXmlToolCalls(content)
                    val historyMessage = JSONObject().apply {
                        put("role", role)
                    }

                    if (toolCalls != null && toolCalls.length() > 0) {
                        historyMessage.put("content", if (textContent.isNotBlank()) textContent else JSONObject.NULL)
                        historyMessage.put("tool_calls", toolCalls)
                        lastToolCallIds.clear()
                        for (i in 0 until toolCalls.length()) {
                            val callId = toolCalls.optJSONObject(i)?.optString("id", "") ?: ""
                            if (callId.isNotBlank()) {
                                lastToolCallIds.add(callId)
                            }
                        }
                    } else {
                        historyMessage.put("content", nonEmptyContent(content))
                        lastToolCallIds.clear()
                    }

                    messagesArray.put(historyMessage)
                }

                "user" -> {
                    val (textContent, toolResults) = parseXmlToolResults(content)
                    var hasHandledToolCalls = false

                    if (lastToolCallIds.isNotEmpty()) {
                        val resultsList = toolResults ?: emptyList()
                        for (i in lastToolCallIds.indices) {
                            val toolMessage = JSONObject().apply {
                                put("role", "tool")
                                put("tool_call_id", lastToolCallIds[i])
                            }
                            val result = resultsList.getOrNull(i)
                            if (result != null) {
                                if (!result.name.isNullOrBlank()) {
                                    toolMessage.put("name", result.name)
                                }
                                toolMessage.put("content", nonEmptyContent(result.content))
                            } else {
                                toolMessage.put("content", "User cancelled")
                            }
                            messagesArray.put(toolMessage)
                        }
                        hasHandledToolCalls = true
                        lastToolCallIds.clear()
                    }

                    when {
                        textContent.isNotBlank() -> {
                            messagesArray.put(JSONObject().apply {
                                put("role", role)
                                put("content", textContent)
                            })
                        }

                        !hasHandledToolCalls -> {
                            messagesArray.put(JSONObject().apply {
                                put("role", role)
                                put("content", nonEmptyContent(content))
                            })
                        }
                    }
                }

                else -> {
                    lastToolCallIds.clear()
                    messagesArray.put(JSONObject().apply {
                        put("role", role)
                        put("content", nonEmptyContent(content))
                    })
                }
            }
        }

        return messagesArray
    }

    private fun mergeConsecutiveMessages(history: List<Pair<String, String>>): List<Pair<String, String>> {
        if (history.isEmpty()) {
            return history
        }

        val mergedHistory = mutableListOf<Pair<String, String>>()
        for ((role, content) in history) {
            if (mergedHistory.isNotEmpty() && role == mergedHistory.last().first && role != "system") {
                val lastMessage = mergedHistory.last()
                mergedHistory[mergedHistory.lastIndex] = role to (lastMessage.second + "\n" + content)
            } else {
                mergedHistory.add(role to content)
            }
        }
        return mergedHistory
    }

    private fun nonEmptyContent(content: String): String {
        return if (content.isBlank()) "[Empty]" else content
    }

    private fun buildToolDefinitions(toolPrompts: List<ToolPrompt>): JSONArray {
        val tools = JSONArray()

        for (tool in toolPrompts) {
            tools.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", tool.name)
                    val fullDescription = if (tool.details.isNotEmpty()) {
                        "${tool.description}\n${tool.details}"
                    } else {
                        tool.description
                    }
                    put("description", fullDescription)
                    put("parameters", buildSchemaFromStructured(tool.parametersStructured ?: emptyList()))
                })
            })
        }

        return tools
    }

    private fun buildSchemaFromStructured(params: List<ToolParameterSchema>): JSONObject {
        val schema = JSONObject().apply {
            put("type", "object")
        }

        val properties = JSONObject()
        val required = JSONArray()

        for (param in params) {
            properties.put(param.name, JSONObject().apply {
                put("type", param.type)
                put("description", param.description)
                if (param.default != null) {
                    put("default", param.default)
                }
            })

            if (param.required) {
                required.put(param.name)
            }
        }

        schema.put("properties", properties)
        if (required.length() > 0) {
            schema.put("required", required)
        }

        return schema
    }

    private fun convertToolCallsToXml(toolCalls: JSONArray): String {
        val xml = StringBuilder()

        for (i in 0 until toolCalls.length()) {
            val toolCall = toolCalls.optJSONObject(i) ?: continue
            val function = toolCall.optJSONObject("function") ?: continue
            val name = function.optString("name", "")
            if (name.isBlank()) {
                continue
            }

            val argumentsRaw = function.optString("arguments", "")
            val paramsObj = kotlin.runCatching {
                JSONObject(argumentsRaw)
            }.getOrNull()

            val toolTagName = ChatMarkupRegex.generateRandomToolTagName()
            xml.append("<")
                .append(toolTagName)
                .append(" name=\"")
                .append(name)
                .append("\">")

            if (paramsObj != null) {
                val keys = paramsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = paramsObj.opt(key)
                    xml.append("\n<param name=\"")
                        .append(key)
                        .append("\">")
                        .append(escapeXml(value?.toString() ?: ""))
                        .append("</param>")
                }
            } else if (argumentsRaw.isNotBlank()) {
                xml.append("\n<param name=\"_raw_arguments\">")
                    .append(escapeXml(argumentsRaw))
                    .append("</param>")
            }

            xml.append("\n</")
                .append(toolTagName)
                .append(">\n")
        }

        return xml.toString().trim()
    }

    private fun parsePossibleToolCallsFromText(content: String): JSONArray? {
        val trimmed = content.trim()
        if (trimmed.isBlank()) {
            return null
        }

        val candidates = LinkedHashSet<String>()
        candidates.add(trimmed)

        val extractedJson = ChatUtils.extractJson(trimmed).trim()
        if (extractedJson.isNotBlank()) {
            candidates.add(extractedJson)
        }

        val extractedArray = ChatUtils.extractJsonArray(trimmed).trim()
        if (extractedArray.isNotBlank()) {
            candidates.add(extractedArray)
        }

        val fencedRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        fencedRegex.findAll(trimmed).forEach { match ->
            val fenced = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (fenced.isNotBlank()) {
                candidates.add(fenced)
            }
        }

        for (candidate in candidates) {
            val fromObject = kotlin.runCatching {
                extractToolCallsFromAny(JSONObject(candidate))
            }.getOrNull()
            if (fromObject != null && fromObject.length() > 0) {
                return fromObject
            }

            val fromArray = kotlin.runCatching {
                extractToolCallsFromAny(JSONArray(candidate))
            }.getOrNull()
            if (fromArray != null && fromArray.length() > 0) {
                return fromArray
            }
        }

        return null
    }

    private fun extractToolCallsFromAny(root: JSONObject): JSONArray? {
        root.optJSONArray("tool_calls")?.let { array ->
            val normalized = normalizeToolCalls(array)
            if (normalized.length() > 0) {
                return normalized
            }
        }

        root.optJSONObject("function_call")?.let { functionCall ->
            val normalized = normalizeSingleToolCall(functionCall, 0)
            if (normalized != null) {
                return JSONArray().put(normalized)
            }
        }

        if (root.optString("type", "") == "function_call") {
            val normalized = normalizeSingleToolCall(root, 0)
            if (normalized != null) {
                return JSONArray().put(normalized)
            }
        }

        root.optJSONArray("output")?.let { outputArray ->
            val normalized = normalizeToolCalls(outputArray)
            if (normalized.length() > 0) {
                return normalized
            }
        }

        return null
    }

    private fun extractToolCallsFromAny(root: JSONArray): JSONArray? {
        val normalized = normalizeToolCalls(root)
        return if (normalized.length() > 0) normalized else null
    }

    private fun normalizeToolCalls(source: JSONArray): JSONArray {
        val normalized = JSONArray()
        for (i in 0 until source.length()) {
            val item = source.optJSONObject(i) ?: continue
            val normalizedCall = normalizeSingleToolCall(item, i) ?: continue
            normalized.put(normalizedCall)
        }
        return normalized
    }

    private fun normalizeSingleToolCall(raw: JSONObject, index: Int): JSONObject? {
        val functionObject = raw.optJSONObject("function")
        val functionCallObject = raw.optJSONObject("function_call")

        val name = when {
            functionObject != null -> functionObject.optString("name", "")
            raw.optString("name", "").isNotBlank() -> raw.optString("name", "")
            functionCallObject != null -> functionCallObject.optString("name", "")
            else -> ""
        }
        if (name.isBlank()) {
            return null
        }

        val argumentsValue: Any? = when {
            functionObject != null && functionObject.has("arguments") -> functionObject.opt("arguments")
            raw.has("arguments") -> raw.opt("arguments")
            functionCallObject != null && functionCallObject.has("arguments") -> functionCallObject.opt("arguments")
            else -> null
        }

        val arguments = when (argumentsValue) {
            is JSONObject, is JSONArray -> argumentsValue.toString()
            is String -> if (argumentsValue.isBlank()) "{}" else argumentsValue
            null -> "{}"
            else -> argumentsValue.toString()
        }

        val rawId = raw.optString("id", "")
            .ifBlank { raw.optString("call_id", "") }
            .ifBlank { "call_${sanitizeToolCallId(name)}_$index" }
        val callId = sanitizeToolCallId(rawId)

        return JSONObject().apply {
            put("id", callId)
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("arguments", arguments)
            })
        }
    }

    private fun parseXmlToolCalls(content: String): Pair<String, JSONArray?> {
        val matches = ChatMarkupRegex.toolCallPattern.findAll(content)
        if (!matches.any()) {
            return content to null
        }

        val toolCalls = JSONArray()
        var textContent = content
        var callIndex = 0

        matches.forEach { match ->
            val toolName = match.groupValues[2]
            val toolBody = match.groupValues[3]

            val params = JSONObject()
            ChatMarkupRegex.toolParamPattern.findAll(toolBody).forEach { paramMatch ->
                val paramName = paramMatch.groupValues[1]
                val paramValue = XmlEscaper.unescape(paramMatch.groupValues[2].trim())
                params.put(paramName, paramValue)
            }

            val toolNamePart = sanitizeToolCallId(toolName)
            val hashPart = stableIdHashPart("${toolName}:${params}")
            val callId = sanitizeToolCallId("call_${toolNamePart}_${hashPart}_$callIndex")

            toolCalls.put(JSONObject().apply {
                put("id", callId)
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", toolName)
                    put("arguments", params.toString())
                })
            })

            callIndex++
            textContent = textContent.replace(match.value, "")
        }

        return textContent.trim() to toolCalls
    }

    private fun parseXmlToolResults(content: String): Pair<String, List<ToolResultRecord>?> {
        val matches = ChatMarkupRegex.toolResultAnyPattern.findAll(content)
        if (!matches.any()) {
            return content to null
        }

        val results = mutableListOf<ToolResultRecord>()
        var textContent = content

        matches.forEach { match ->
            val fullContent = match.groupValues[2].trim()
            val contentMatch = ChatMarkupRegex.contentTag.find(fullContent)
            val resultContent = if (contentMatch != null) {
                contentMatch.groupValues[1].trim()
            } else {
                fullContent
            }
            val resultName = ChatMarkupRegex.nameAttr.find(match.value)?.groupValues?.getOrNull(1)
            results.add(ToolResultRecord(resultName, resultContent))
            textContent = textContent.replace(match.value, "").trim()
        }

        return textContent.trim() to results
    }

    private object XmlEscaper {
        fun escape(text: String): String {
            return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
        }

        fun unescape(text: String): String {
            return text.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
        }
    }

    private fun escapeXml(text: String): String {
        return XmlEscaper.escape(text)
    }

    private fun sanitizeToolCallId(raw: String): String {
        val output = buildString(raw.length) {
            raw.forEach { ch ->
                if (ch.isLetterOrDigit() || ch == '_' || ch == '-') {
                    append(ch)
                } else {
                    append('_')
                }
            }
        }.replace(Regex("_+"), "_").trim('_')
        return if (output.isEmpty()) "call" else output
    }

    private fun stableIdHashPart(raw: String): String {
        val hash = raw.hashCode()
        val positive = if (hash == Int.MIN_VALUE) 0 else abs(hash)
        val base = positive.toString(36).filter { it.isLetterOrDigit() }.lowercase()
        return if (base.isEmpty()) "0" else base
    }
}
