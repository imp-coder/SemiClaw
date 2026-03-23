package com.ai.assistance.operit.core.tools.system

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import java.util.UUID

/**
 * Stub Terminal class - Terminal module removed
 * This is a stub to allow compilation while terminal functionality is disabled.
 */
class Terminal private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: Terminal? = null

        fun getInstance(context: Context): Terminal {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Terminal(context.applicationContext).also { INSTANCE = it }
            }
        }

        private const val TAG = "Terminal"
    }

    fun isConnected(): Boolean = false

    suspend fun initialize(): Boolean = false

    fun createSession(name: String): String = UUID.randomUUID().toString()

    fun executeCommand(sessionId: String, command: String): String? = null

    fun executeCommandFlow(sessionId: String, command: String): Flow<CommandOutput> {
        return flowOf(CommandOutput())
    }

    fun closeSession(sessionId: String) {}

    fun destroy() {}

    fun sendInput(sessionId: String, input: String) {}

    fun sendInterruptSignal(sessionId: String) {}

    private val _terminalState = MutableStateFlow(TerminalState())
    val terminalState: StateFlow<TerminalState> = _terminalState

    data class TerminalState(
        val sessions: List<SessionInfo> = emptyList()
    )

    data class SessionInfo(
        val id: String,
        val title: String
    )

    data class CommandOutput(
        val outputChunk: String = "",
        val isCompleted: Boolean = true
    )
}