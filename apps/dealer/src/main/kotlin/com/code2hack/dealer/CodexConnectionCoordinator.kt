package com.code2hack.dealer

import com.code2hack.pokerdealer.protocol.appserver.CodexAppServerSession
import com.code2hack.pokerdealer.protocol.appserver.HostSessionManager
import com.code2hack.pokerdealer.protocol.appserver.HostSessionState
import com.code2hack.pokerdealer.protocol.appserver.HostSessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Owns Codex-host session generations. It has no Poker/CXR dependency. */
internal class CodexConnectionCoordinator(
    private val sessions: HostSessionManager,
    private val scope: CoroutineScope,
    private val onConnected: suspend (hostId: String, generation: Long) -> Unit,
    private val onDisconnected: suspend (hostId: String, generation: Long) -> Unit,
) {
    private val generations = mutableMapOf<String, Long>()
    private var connectedHostIds = emptySet<String>()
    private var observer: Job? = null
    private val mutableState = MutableStateFlow<Map<String, HostSessionState>>(emptyMap())
    val state: StateFlow<Map<String, HostSessionState>> = mutableState.asStateFlow()

    suspend fun start() {
        if (observer == null) {
            observer = scope.launch {
                sessions.state.collect { current ->
                    mutableState.value = current
                    val connected = current
                        .filterValues { it.status == HostSessionStatus.CONNECTED }
                        .keys
                    val disconnected = connectedHostIds - connected
                    val newlyConnected = connected - connectedHostIds
                    connectedHostIds = connected
                    disconnected.forEach { hostId ->
                        onDisconnected(hostId, generations[hostId] ?: 0L)
                    }
                    newlyConnected.forEach { hostId ->
                        val generation = generations.getOrDefault(hostId, 0L) + 1L
                        generations[hostId] = generation
                        onConnected(hostId, generation)
                    }
                }
            }
        }
        sessions.start()
    }

    suspend fun setEnabled(hostId: String, enabled: Boolean) = sessions.setEnabled(hostId, enabled)

    fun appServer(hostId: String): CodexAppServerSession? = sessions.connectedSession(hostId)?.appServer

    fun generation(hostId: String): Long? = generations[hostId]

    fun isCurrent(hostId: String, generation: Long): Boolean =
        generations[hostId] == generation && hostId in connectedHostIds

    suspend fun close() {
        sessions.close()
        observer?.cancelAndJoin()
        observer = null
        connectedHostIds = emptySet()
        mutableState.update { emptyMap() }
    }
}
