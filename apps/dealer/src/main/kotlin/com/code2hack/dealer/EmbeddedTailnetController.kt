package com.code2hack.dealer

import android.content.Context
import android.net.ConnectivityManager
import com.code2hack.tailnet.embeddedtailnet.Engine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class EmbeddedTailnetConnectionState {
    STOPPED,
    STARTING,
    STOPPING,
    LOGIN_REQUIRED,
    CONNECTED,
    DEGRADED,
    UNAVAILABLE,
    ERROR,
}

data class EmbeddedTailnetStatus(
    val state: EmbeddedTailnetConnectionState = EmbeddedTailnetConnectionState.STOPPED,
    val loginUrl: String? = null,
    val nodeName: String? = null,
    val path: String? = null,
    val relay: String? = null,
    val health: List<String> = emptyList(),
    val error: String? = null,
)

/** Android lifecycle owner for the retained userspace tailnet route. */
internal class EmbeddedTailnetController(
    private val context: Context,
    private val scope: CoroutineScope,
    internal val engine: Engine = Engine(),
) {
    private val mutableStatus = MutableStateFlow(EmbeddedTailnetStatus())
    val status: StateFlow<EmbeddedTailnetStatus> = mutableStatus.asStateFlow()
    private var poller: Job? = null

    fun start() {
        if (poller?.isActive == true || mutableStatus.value.state in ACTIVE_STATES) return
        mutableStatus.value = EmbeddedTailnetStatus(EmbeddedTailnetConnectionState.STARTING)
        poller = scope.launch(Dispatchers.IO) {
            try {
                updateNetwork()
                mutableStatus.value = engine.start(stateDirectory()).toEmbeddedTailnetStatus()
                while (true) {
                    delay(STATUS_INTERVAL_MS)
                    updateNetwork()
                    mutableStatus.value = engine.status().toEmbeddedTailnetStatus()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                runCatching { engine.stop() }
                mutableStatus.value = EmbeddedTailnetStatus(
                    state = EmbeddedTailnetConnectionState.ERROR,
                    error = failure.message ?: failure::class.java.simpleName,
                )
            } finally {
                poller = null
            }
        }
    }

    suspend fun stop() {
        mutableStatus.value = mutableStatus.value.copy(state = EmbeddedTailnetConnectionState.STOPPING)
        poller?.cancel()
        poller = null
        withContext(Dispatchers.IO) { engine.stop() }
        mutableStatus.value = EmbeddedTailnetStatus(EmbeddedTailnetConnectionState.STOPPED)
    }

    fun connectionState(): EmbeddedTailnetConnectionState = mutableStatus.value.state

    private fun updateNetwork() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val properties = manager.getLinkProperties(manager.activeNetwork)
        val interfaceName = properties?.interfaceName.orEmpty()
        val addresses = buildJsonArray {
            properties?.linkAddresses.orEmpty().forEach { link ->
                val address = link.address.hostAddress?.substringBefore('%') ?: return@forEach
                add(JsonPrimitive("$address/${link.prefixLength}"))
            }
        }
        val gateway = properties?.routes
            ?.firstOrNull { it.isDefaultRoute }
            ?.gateway
            ?.hostAddress
            ?.substringBefore('%')
            .orEmpty()
        engine.setNetwork(interfaceName, addresses.toString(), gateway)
    }

    private fun stateDirectory(): String = context.filesDir.resolve("embedded-tailnet").absolutePath

    private companion object {
        const val STATUS_INTERVAL_MS = 1_000L
        val ACTIVE_STATES = setOf(
            EmbeddedTailnetConnectionState.STARTING,
            EmbeddedTailnetConnectionState.LOGIN_REQUIRED,
            EmbeddedTailnetConnectionState.CONNECTED,
            EmbeddedTailnetConnectionState.DEGRADED,
            EmbeddedTailnetConnectionState.UNAVAILABLE,
        )
    }
}

internal fun String.toEmbeddedTailnetStatus(): EmbeddedTailnetStatus {
    val value = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(this).jsonObject }
        .getOrElse {
            return EmbeddedTailnetStatus(
                state = EmbeddedTailnetConnectionState.ERROR,
                error = "Invalid embedded-tailnet status: ${it.message}",
            )
        }
    val rawState = value.text("state")
    return EmbeddedTailnetStatus(
        state = when (rawState) {
            "stopped" -> EmbeddedTailnetConnectionState.STOPPED
            "starting" -> EmbeddedTailnetConnectionState.STARTING
            "login_required" -> EmbeddedTailnetConnectionState.LOGIN_REQUIRED
            "connected" -> EmbeddedTailnetConnectionState.CONNECTED
            "degraded" -> EmbeddedTailnetConnectionState.DEGRADED
            "unavailable" -> EmbeddedTailnetConnectionState.UNAVAILABLE
            else -> EmbeddedTailnetConnectionState.ERROR
        },
        loginUrl = value.text("loginUrl"),
        nodeName = value.text("nodeName"),
        path = value.text("path"),
        relay = value.text("relay"),
        health = (value["health"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        },
        error = if (rawState == null) "Embedded-tailnet status omitted state" else null,
    )
}

private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull
