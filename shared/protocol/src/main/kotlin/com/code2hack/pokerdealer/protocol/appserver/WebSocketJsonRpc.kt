package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.protocol.host.DuplexByteStream
import com.code2hack.pokerdealer.protocol.host.withConnectionPhaseTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val WebSocketRandom = SecureRandom()
private fun SecureRandom.bytes(size: Int): ByteArray = ByteArray(size).also(::nextBytes)

val AppServerJson = Json {
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = true
}

class AppServerWebSocket(
    private val stream: DuplexByteStream,
    private val hostHeader: String = "localhost",
    private val keyFactory: () -> String = { Base64.getEncoder().encodeToString(WebSocketRandom.bytes(16)) },
    private val maskFactory: () -> ByteArray = { WebSocketRandom.bytes(4) },
    private val maxPayloadBytes: Long = 8L * 1_024 * 1_024,
    private val maxHeaderBytes: Int = 16 * 1_024,
    private val handshakeTimeoutMs: Long = 10_000,
) {
    init {
        require(maxPayloadBytes in 1..Int.MAX_VALUE.toLong()) { "WebSocket payload limit is invalid" }
        require(maxHeaderBytes > 0) { "WebSocket header limit must be positive" }
        require(handshakeTimeoutMs > 0) { "WebSocket handshake timeout must be positive" }
    }

    suspend fun open() {
        try {
            withConnectionPhaseTimeout("WebSocket HTTP upgrade", handshakeTimeoutMs) {
                val key = keyFactory()
                val request = buildString {
                    append("GET / HTTP/1.1\r\n")
                    append("Host: ").append(hostHeader).append("\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("\r\n")
                }
                stream.write(request.toByteArray(Charsets.US_ASCII))
                val response = readHttpHeader()
                require(response.lineSequence().firstOrNull()?.contains(" 101 ") == true) {
                    "app-server proxy did not upgrade to WebSocket"
                }
                require(response.header("Upgrade").equals("websocket", ignoreCase = true)) {
                    "app-server proxy returned an invalid Upgrade header"
                }
                require(
                    response.header("Connection")?.split(',')?.any {
                        it.trim().equals("upgrade", ignoreCase = true)
                    } == true,
                ) {
                    "app-server proxy returned an invalid Connection header"
                }
                val accept = response.header("Sec-WebSocket-Accept")
                require(accept == websocketAccept(key)) {
                    "app-server proxy returned an invalid WebSocket accept key"
                }
            }
        } catch (failure: Throwable) {
            closeAfterFailure(failure)
            throw failure
        }
    }

    suspend fun sendText(text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        require(payload.size.toLong() <= maxPayloadBytes) { "WebSocket payload exceeds limit" }
        writeFrame(opcode = 0x1, payload = payload)
    }

    suspend fun readText(): String? = try {
        readTextFrames()
    } catch (failure: Throwable) {
        closeAfterFailure(failure)
        throw failure
    }

    private suspend fun readTextFrames(): String? {
        val message = ByteArrayOutputStream()
        var textStarted = false
        while (true) {
            val first = readByteOrNull() ?: return null
            require(first and 0x70 == 0) { "WebSocket extensions are not supported" }
            val fin = first and 0x80 != 0
            val opcode = first and 0x0F
            val second = readByte()
            val masked = second and 0x80 != 0
            require(!masked) { "Server WebSocket frames must not be masked" }
            val length = readPayloadLength(second and 0x7F)
            require(length <= maxPayloadBytes) { "WebSocket payload exceeds limit" }
            if (opcode >= 0x8) {
                require(fin) { "WebSocket control frames must not be fragmented" }
                require(length <= 125) { "WebSocket control frame is too large" }
            }
            val payload = readExactly(length.toInt())

            when (opcode) {
                0x0 -> {
                    require(textStarted) { "Unexpected continuation frame" }
                    require(message.size().toLong() + length <= maxPayloadBytes) { "WebSocket message exceeds limit" }
                    message.write(payload)
                    if (fin) return message.toByteArray().toString(Charsets.UTF_8)
                }
                0x1 -> {
                    require(!textStarted) { "Unexpected text frame before fragmented message completed" }
                    require(length <= maxPayloadBytes) { "WebSocket message exceeds limit" }
                    textStarted = true
                    message.write(payload)
                    if (fin) return message.toByteArray().toString(Charsets.UTF_8)
                }
                0x8 -> return null
                0x9 -> writeFrame(opcode = 0xA, payload = payload)
                0xA -> Unit
                else -> error("Unsupported WebSocket opcode $opcode")
            }
        }
    }

    suspend fun close() {
        stream.close()
    }

    private suspend fun closeAfterFailure(failure: Throwable) {
        withContext(NonCancellable) {
            try {
                stream.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
        }
    }

    private suspend fun writeFrame(opcode: Int, payload: ByteArray) {
        val header = ByteArrayOutputStream()
        header.write(0x80 or opcode)
        when {
            payload.size < 126 -> header.write(0x80 or payload.size)
            payload.size <= 0xFFFF -> {
                header.write(0x80 or 126)
                header.write((payload.size ushr 8) and 0xFF)
                header.write(payload.size and 0xFF)
            }
            else -> {
                header.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) header.write((payload.size.toLong() ushr shift).toInt() and 0xFF)
            }
        }
        val mask = maskFactory()
        require(mask.size == 4) { "WebSocket client mask must be four bytes" }
        header.write(mask)
        val maskedPayload = payload.copyOf()
        maskedPayload.indices.forEach { index ->
            maskedPayload[index] = (maskedPayload[index].toInt() xor mask[index % 4].toInt()).toByte()
        }
        stream.write(header.toByteArray() + maskedPayload)
    }

    private suspend fun readHttpHeader(): String {
        val bytes = ByteArrayOutputStream()
        val delimiter = byteArrayOf(13, 10, 13, 10)
        var matched = 0
        while (matched < delimiter.size) {
            require(bytes.size() < maxHeaderBytes) { "WebSocket HTTP header exceeds limit" }
            val byte = readByte()
            bytes.write(byte)
            matched = when {
                byte == delimiter[matched].toInt() -> matched + 1
                byte == delimiter[0].toInt() -> 1
                else -> 0
            }
        }
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }

    private suspend fun readPayloadLength(marker: Int): Long = when (marker) {
        126 -> readExactly(2).fold(0L) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF).toLong() }
        127 -> readExactly(8).also { require(it[0].toInt() and 0x80 == 0) { "Invalid WebSocket payload length" } }
            .fold(0L) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF).toLong() }
        else -> marker.toLong()
    }

    private suspend fun readByte(): Int = readByteOrNull() ?: error("Unexpected EOF")

    private suspend fun readByteOrNull(): Int? {
        val one = ByteArray(1)
        while (true) {
            when (stream.read(one)) {
                -1 -> return null
                0 -> error("Duplex stream made no read progress")
                else -> return one[0].toInt() and 0xFF
            }
        }
    }

    private suspend fun readExactly(size: Int): ByteArray {
        val out = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = stream.read(out, offset, size - offset)
            require(read != -1) { "Unexpected EOF" }
            require(read > 0) { "Duplex stream made no read progress" }
            offset += read
        }
        return out
    }
}

private fun String.header(name: String): String? {
    return lineSequence().drop(1).firstNotNullOfOrNull { line ->
        val separator = line.indexOf(':')
        if (separator == -1 || !line.substring(0, separator).trim().equals(name, ignoreCase = true)) {
            null
        } else {
            line.substring(separator + 1).trim()
        }
    }
}

private fun websocketAccept(key: String): String {
    val digest = MessageDigest.getInstance("SHA-1")
        .digest("${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11".toByteArray(Charsets.US_ASCII))
    return Base64.getEncoder().encodeToString(digest)
}

data class AppServerNotification(
    val method: String,
    val params: JsonElement = JsonObject(emptyMap()),
    val raw: JsonObject? = null,
)

data class AppServerRequest(
    val id: JsonElement,
    val method: String,
    val params: JsonElement = JsonObject(emptyMap()),
    val raw: JsonObject? = null,
)

interface JsonRpcPeer {
    suspend fun request(method: String, params: JsonElement = JsonObject(emptyMap())): JsonElement
    suspend fun notify(method: String, params: JsonElement? = null)
    suspend fun receiveNotification(): AppServerNotification?
    suspend fun receiveServerRequest(): AppServerRequest? = null
    suspend fun respond(request: AppServerRequest, result: JsonElement) {
        error("Server requests are not supported by this peer")
    }
    suspend fun reject(request: AppServerRequest, message: String) {
        error("Server requests are not supported by this peer")
    }
    suspend fun awaitClose(): Nothing = awaitCancellation()
    suspend fun close()
}

class JsonRpcRemoteException(
    val method: String,
    val error: JsonElement,
) : IllegalStateException("app-server $method failed: $error") {
    val code: Int?
        get() = ((error as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
}

class WebSocketJsonRpcPeer(
    private val socket: AppServerWebSocket,
    private val maxPendingNotifications: Int = 1_024,
    private val maxPendingServerRequests: Int = 64,
    private val supportedServerRequests: Set<String> = emptySet(),
    private val onRejectedServerRequest: (String) -> Unit = {},
    private val onDiagnostic: (JsonObject) -> Unit = {},
) : JsonRpcPeer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextId = AtomicLong(1)
    private val closed = AtomicBoolean()
    private val writeMutex = Mutex()
    private val pendingResponses = ConcurrentHashMap<JsonElement, PendingResponse>()
    private val pendingServerRequests = ConcurrentHashMap<JsonElement, AppServerRequest>()
    private val closeFailure = CompletableDeferred<Throwable>()
    private val notifications = Channel<AppServerNotification>(maxPendingNotifications)
    private val serverRequests = Channel<AppServerRequest>(maxPendingServerRequests)
    private val reader = scope.launch { readLoop() }

    init {
        require(maxPendingNotifications > 0) { "Pending notification limit must be positive" }
        require(maxPendingServerRequests > 0) { "Pending server-request limit must be positive" }
    }

    override suspend fun request(method: String, params: JsonElement): JsonElement {
        check(!closed.get()) { "app-server connection is closed" }
        val id = JsonPrimitive(nextId.getAndIncrement())
        val response = CompletableDeferred<JsonElement>()
        pendingResponses[id] = PendingResponse(method, response)
        try {
            send(
                buildJsonObject {
                    put("id", id)
                    put("method", JsonPrimitive(method))
                    put("params", params)
                },
            )
            return response.await()
        } finally {
            pendingResponses.remove(id)
        }
    }

    override suspend fun notify(method: String, params: JsonElement?) {
        check(!closed.get()) { "app-server connection is closed" }
        send(
            buildJsonObject {
                put("method", JsonPrimitive(method))
                if (params != null) put("params", params)
            },
        )
    }

    override suspend fun receiveNotification(): AppServerNotification? =
        notifications.receiveCatching().getOrThrow()

    override suspend fun receiveServerRequest(): AppServerRequest? =
        serverRequests.receiveCatching().getOrThrow()

    override suspend fun respond(request: AppServerRequest, result: JsonElement) {
        require(pendingServerRequests.remove(request.id, request)) {
            "Server request ${request.id} is no longer pending"
        }
        send(buildJsonObject {
            put("id", request.id)
            put("result", result)
        })
    }

    override suspend fun reject(request: AppServerRequest, message: String) {
        require(pendingServerRequests.remove(request.id, request)) {
            "Server request ${request.id} is no longer pending"
        }
        reject(request.id, request.method, request.raw ?: JsonObject(emptyMap()), message)
    }

    override suspend fun close() {
        terminate(IllegalStateException("app-server connection closed"))
    }

    override suspend fun awaitClose(): Nothing = throw closeFailure.await()

    private suspend fun readLoop() {
        var failure: Throwable = IllegalStateException("app-server connection closed")
        try {
            while (true) {
                when (val inbound = readInbound() ?: break) {
                    is Inbound.Response -> {
                        val pending = pendingResponses.remove(inbound.id)
                        if (pending == null) {
                            runCatching { onDiagnostic(inbound.raw) }
                        } else if (inbound.error != null) {
                            pending.result.completeExceptionally(JsonRpcRemoteException(pending.method, inbound.error))
                        } else {
                            pending.result.complete(inbound.result ?: JsonNull)
                        }
                    }
                    is Inbound.Notification -> {
                        check(
                            notifications.trySend(
                                AppServerNotification(
                                    inbound.method,
                                    inbound.params ?: JsonObject(emptyMap()),
                                    inbound.raw,
                                ),
                            ).isSuccess,
                        ) { "Too many pending app-server notifications" }
                    }
                    is Inbound.ServerRequest -> dispatch(inbound)
                    is Inbound.MalformedServerRequest -> reject(inbound.id, null, inbound.raw, "Malformed server request")
                }
            }
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            terminate(failure)
        }
    }

    private suspend fun dispatch(inbound: Inbound.ServerRequest) {
        if (inbound.method !in supportedServerRequests) {
            reject(
                inbound.id,
                inbound.method,
                inbound.raw,
                "Dealer cannot handle server request '${inbound.method}'",
            )
            return
        }
        val request = AppServerRequest(
            inbound.id,
            inbound.method,
            inbound.params ?: JsonObject(emptyMap()),
            inbound.raw,
        )
        pendingServerRequests[inbound.id] = request
        if (serverRequests.trySend(request).isFailure) {
            pendingServerRequests.remove(inbound.id)
            reject(inbound.id, inbound.method, inbound.raw, "Dealer server-request queue is full")
        }
    }

    private suspend fun terminate(failure: Throwable) {
        if (!closed.compareAndSet(false, true)) return
        closeFailure.complete(failure)
        withContext(NonCancellable) {
            try {
                socket.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            pendingResponses.values.forEach { it.result.completeExceptionally(failure) }
            pendingResponses.clear()
            pendingServerRequests.clear()
            notifications.close(failure)
            serverRequests.close(failure)
            scope.cancel()
        }
    }

    private suspend fun send(message: JsonObject) {
        try {
            writeMutex.withLock {
                check(!closed.get()) { "app-server connection is closed" }
                socket.sendText(AppServerJson.encodeToString(JsonElement.serializer(), message))
            }
        } catch (failure: Throwable) {
            terminate(failure)
            throw failure
        }
    }

    private suspend fun reject(id: JsonElement, method: String?, raw: JsonObject, message: String) {
        send(
            buildJsonObject {
                put("id", id)
                put(
                    "error",
                    buildJsonObject {
                        put("code", JsonPrimitive(if (method == null) -32600 else -32601))
                        put("message", JsonPrimitive(message))
                    },
                )
            },
        )
        if (method != null) runCatching { onRejectedServerRequest(method) }
        if (method != "config/read") runCatching { onDiagnostic(raw) }
    }

    private suspend fun readInbound(): Inbound? {
        val text = socket.readText() ?: return null
        val message = AppServerJson.parseToJsonElement(text) as? JsonObject
            ?: error("Invalid JSON-RPC message: expected object")
        val id = message["id"]
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
        return when {
            method != null && id != null -> Inbound.ServerRequest(id, method, message["params"], message)
            method != null -> Inbound.Notification(method, message["params"], message)
            id != null && "method" in message -> Inbound.MalformedServerRequest(id, message)
            id != null -> Inbound.Response(id, message["result"], message["error"], message)
            else -> error("Invalid JSON-RPC message: $message")
        }
    }

    private data class PendingResponse(
        val method: String,
        val result: CompletableDeferred<JsonElement>,
    )

    private sealed interface Inbound {
        data class Response(
            val id: JsonElement,
            val result: JsonElement?,
            val error: JsonElement?,
            val raw: JsonObject,
        ) : Inbound
        data class Notification(val method: String, val params: JsonElement?, val raw: JsonObject) : Inbound
        data class ServerRequest(
            val id: JsonElement,
            val method: String,
            val params: JsonElement?,
            val raw: JsonObject,
        ) : Inbound
        data class MalformedServerRequest(val id: JsonElement, val raw: JsonObject) : Inbound
    }
}
