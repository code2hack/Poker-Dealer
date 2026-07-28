package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.protocol.host.DuplexByteStream
import com.code2hack.pokerdealer.protocol.host.ConnectionPhaseTimeoutException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

class WebSocketJsonRpcTest {
    @Test
    fun `websocket upgrades, sends masked text, and reads server text frame`() = runTest {
        val stream = MemoryStream(
            (
                "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n" +
                    "\r\n"
                ).toByteArray(Charsets.US_ASCII) + serverTextFrame("""{"method":"turn/completed"}"""),
        )
        val socket = AppServerWebSocket(
            stream = stream,
            keyFactory = { "dGhlIHNhbXBsZSBub25jZQ==" },
            maskFactory = { byteArrayOf(0, 0, 0, 0) },
        )

        socket.open()
        socket.sendText("""{"method":"initialized"}""")

        assertTrue(stream.writtenText().contains("GET / HTTP/1.1"))
        assertTrue(stream.writtenText().contains("Upgrade: websocket"))
        assertTrue(stream.writtenBytes().containsClientText("""{"method":"initialized"}"""))
        assertEquals("""{"method":"turn/completed"}""", socket.readText())
    }

    @Test
    fun `websocket bounds a fragmented message across frames`() = runTest {
        val stream = MemoryStream(
            serverFrame(fin = false, opcode = 0x1, payload = "1234".toByteArray()) +
                serverFrame(fin = true, opcode = 0x0, payload = "5678".toByteArray()),
        )
        val socket = AppServerWebSocket(stream, maxPayloadBytes = 6)

        val failure = runCatching { socket.readText() }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("message exceeds limit"))
    }

    @Test
    fun `json rpc rejects unknown server requests and preserves unknown notifications`() = runTest {
        val rejected = mutableListOf<String>()
        val stream = MemoryStream(
            serverTextFrame("""{"id":7,"method":"future/request","params":{"value":1}}""") +
                serverTextFrame("""{"method":"future/event","params":{"value":2},"futureField":true}"""),
        )
        val peer = WebSocketJsonRpcPeer(
            AppServerWebSocket(stream, maskFactory = { byteArrayOf(0, 0, 0, 0) }),
            onRejectedServerRequest = rejected::add,
        )

        val notification = peer.receiveNotification()

        assertEquals("future/event", notification?.method)
        assertEquals(listOf("future/request"), rejected)
        assertEquals("true", notification?.raw?.get("futureField").toString())
        assertTrue(stream.writtenBytes().containsClientText("Dealer cannot handle server request"))
    }

    @Test
    fun `json rpc multiplexes fixture traffic through one reader`() = runTest {
        val rejected = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        val stream = InteractiveStream()
        val peer = WebSocketJsonRpcPeer(
            AppServerWebSocket(stream, maskFactory = { byteArrayOf(0, 0, 0, 0) }),
            supportedServerRequests = setOf("item/commandExecution/requestApproval"),
            onRejectedServerRequest = rejected::add,
            onDiagnostic = { diagnostics += it["method"].toString() },
        )
        val first = async(start = CoroutineStart.UNDISPATCHED) { peer.request("first") }
        val second = async(start = CoroutineStart.UNDISPATCHED) { peer.request("second") }

        stream.feedFixture("multiplex-notification.json")
        stream.feedFixture("multiplex-handled-request.json")
        stream.feedFixture("multiplex-response-2.json")
        stream.feedFixture("multiplex-unknown-request.json")
        stream.feedFixture("multiplex-malformed-request.json")
        stream.feedFixture("multiplex-response-1.json")

        val request = peer.receiveServerRequest()!!
        peer.respond(request, AppServerJson.parseToJsonElement("""{"decision":"accept"}"""))

        assertEquals("first", first.await().jsonObject["value"]?.toString()?.trim('"'))
        assertEquals("second", second.await().jsonObject["value"]?.toString()?.trim('"'))
        val notification = peer.receiveNotification()
        assertEquals("item/agentMessage/delta", notification?.method)
        assertEquals("true", notification?.raw?.get("futureField").toString())
        assertEquals("item/commandExecution/requestApproval", request.method)
        assertEquals(listOf("future/request"), rejected)
        assertTrue(diagnostics.any { it.contains("future/request") })
        assertTrue(stream.writtenBytes().containsClientText("Dealer cannot handle server request"))
        assertTrue(stream.writtenBytes().containsClientText("Malformed server request"))
        assertTrue(stream.writtenBytes().containsClientText("accept"))
        peer.close()
    }

    @Test
    fun `json rpc bounds notification and server-request queues`() = runTest {
        val notificationStream = InteractiveStream()
        val notificationPeer = WebSocketJsonRpcPeer(
            AppServerWebSocket(notificationStream),
            maxPendingNotifications = 1,
        )
        notificationStream.feedFixture("multiplex-notification.json")
        notificationStream.feedFixture("multiplex-notification.json")
        notificationStream.awaitClosed()

        assertEquals("item/agentMessage/delta", notificationPeer.receiveNotification()?.method)
        val overflow = runCatching { notificationPeer.receiveNotification() }.exceptionOrNull()
        assertTrue(overflow?.message.orEmpty().contains("Too many pending app-server notifications"))

        val rejected = mutableListOf<String>()
        val requestStream = InteractiveStream()
        val requestPeer = WebSocketJsonRpcPeer(
            AppServerWebSocket(requestStream, maskFactory = { byteArrayOf(0, 0, 0, 0) }),
            maxPendingServerRequests = 1,
            supportedServerRequests = setOf("item/commandExecution/requestApproval"),
            onRejectedServerRequest = rejected::add,
        )
        requestStream.feedFixture("multiplex-handled-request.json")
        requestStream.feedText(fixture("multiplex-handled-request.json").replace("\"id\": 40", "\"id\": 42"))
        requestStream.feedFixture("multiplex-notification.json")

        assertEquals("item/agentMessage/delta", requestPeer.receiveNotification()?.method)
        assertEquals(40, requestPeer.receiveServerRequest()?.id?.toString()?.toInt())
        assertEquals(listOf("item/commandExecution/requestApproval"), rejected)
        assertTrue(requestStream.writtenBytes().containsClientText("queue is full"))
        requestPeer.close()
    }

    @Test
    fun `handled request can fail closed only once`() = runTest {
        val stream = InteractiveStream()
        val peer = WebSocketJsonRpcPeer(
            AppServerWebSocket(stream, maskFactory = { byteArrayOf(0, 0, 0, 0) }),
            supportedServerRequests = setOf(COMMAND_APPROVAL_METHOD),
        )
        stream.feedFixture("multiplex-handled-request.json")
        val request = peer.receiveServerRequest()!!

        peer.reject(request, "Incomplete command scope")
        val duplicate = runCatching { peer.reject(request, "again") }.exceptionOrNull()

        assertTrue(stream.writtenBytes().containsClientText("Incomplete command scope"))
        assertTrue(duplicate?.message.orEmpty().contains("no longer pending"))
        peer.close()
    }

    @Test
    fun `file approval request reaches the bounded supported queue`() = runTest {
        val stream = InteractiveStream()
        val peer = WebSocketJsonRpcPeer(
            AppServerWebSocket(stream),
            supportedServerRequests = setOf(FILE_APPROVAL_METHOD),
        )
        stream.feedFixture("file-approval-request.json")

        val request = peer.receiveServerRequest()

        assertEquals(FILE_APPROVAL_METHOD, request?.method)
        peer.reject(request!!, "fixture complete")
        peer.close()
    }

    @Test
    fun `disconnect closes transport and fails every response waiter once`() = runTest {
        val stream = InteractiveStream()
        val peer = WebSocketJsonRpcPeer(AppServerWebSocket(stream))
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { peer.request("first") }.exceptionOrNull()
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { peer.request("second") }.exceptionOrNull()
        }

        stream.closeIncoming()

        val firstFailure = first.await()
        val secondFailure = second.await()
        assertEquals("app-server connection closed", firstFailure?.message)
        assertEquals("app-server connection closed", secondFailure?.message)
        assertEquals(1, stream.closeCalls.get())
    }

    @Test
    fun `websocket handshake timeout closes a blocked stream`() = runTest {
        val stream = BlockingStream()
        val socket = AppServerWebSocket(stream, handshakeTimeoutMs = 100)

        val failure = runCatching { socket.open() }.exceptionOrNull()

        assertTrue(failure is ConnectionPhaseTimeoutException)
        assertTrue(failure?.message.orEmpty().contains("WebSocket HTTP upgrade"))
        assertTrue(stream.closed)
    }

    @Test
    fun `app-server response timeout closes the peer`() = runTest {
        val peer = BlockingPeer()
        val session = CodexAppServerSession(peer, requestTimeoutMs = 100)

        val failure = runCatching { session.initialize() }.exceptionOrNull()

        assertTrue(failure is ConnectionPhaseTimeoutException)
        assertTrue(failure?.message.orEmpty().contains("initialize response"))
        assertTrue(peer.closed)
    }

    @Test
    fun `turn inactivity timeout closes the peer`() = runTest {
        val peer = BlockingPeer(requestsBlock = false)
        val session = CodexAppServerSession(peer, turnInactivityTimeoutMs = 100)
        session.initialize()

        val failure = runCatching {
            session.streamAgentCards("thread", "turn", "u4090/thread", firstSequence = 1)
        }.exceptionOrNull()

        assertTrue(failure is ConnectionPhaseTimeoutException)
        assertTrue(failure?.message.orEmpty().contains("turn notification"))
        assertTrue(peer.closed)
    }

    @Test
    fun `turn inactivity timer resets after each notification`() = runTest {
        val peer = DelayedNotificationPeer()
        val session = CodexAppServerSession(peer, turnInactivityTimeoutMs = 100)
        session.initialize()

        val cards = session.streamAgentCards("thread", "turn", "u4090/thread", firstSequence = 1)

        assertEquals("still working", cards.single().fullText)
    }

    @Test
    fun `cancelling a blocked WebSocket read closes the stream`() = runTest {
        val stream = BlockingStream()
        val socket = AppServerWebSocket(stream)
        val readJob = launch { socket.readText() }
        yield()

        readJob.cancelAndJoin()

        assertTrue(stream.closed)
    }
}

private class BlockingStream : DuplexByteStream {
    var closed = false

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = awaitCancellation()
    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
    override suspend fun close() {
        closed = true
    }
}

private class BlockingPeer(
    private val requestsBlock: Boolean = true,
) : JsonRpcPeer {
    var closed = false

    override suspend fun request(method: String, params: JsonElement): JsonElement {
        if (requestsBlock) awaitCancellation()
        return AppServerJson.parseToJsonElement("""{"serverInfo":{}}""")
    }

    override suspend fun notify(method: String, params: JsonElement?) = Unit
    override suspend fun receiveNotification(): AppServerNotification? = awaitCancellation()
    override suspend fun close() {
        closed = true
    }
}

private class DelayedNotificationPeer : JsonRpcPeer {
    private val notifications = ArrayDeque(
        listOf(
            AppServerNotification(
                "item/agentMessage/delta",
                AppServerJson.parseToJsonElement(
                    """{"threadId":"thread","turnId":"turn","itemId":"agent","delta":"still working"}""",
                ),
            ),
            AppServerNotification(
                "turn/completed",
                AppServerJson.parseToJsonElement(
                    """{"threadId":"thread","turn":{"id":"turn","status":"completed","items":[]}}""",
                ),
            ),
        ),
    )

    override suspend fun request(method: String, params: JsonElement): JsonElement =
        AppServerJson.parseToJsonElement("""{"serverInfo":{}}""")

    override suspend fun notify(method: String, params: JsonElement?) = Unit

    override suspend fun receiveNotification(): AppServerNotification {
        delay(75)
        return notifications.removeFirst()
    }

    override suspend fun close() = Unit
}

private class MemoryStream(
    incoming: ByteArray,
) : DuplexByteStream {
    private val incoming = incoming.copyOf()
    private val written = ByteArrayOutputStream()
    private var readOffset = 0

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (readOffset >= incoming.size) return -1
        val count = minOf(length, incoming.size - readOffset)
        incoming.copyInto(buffer, destinationOffset = offset, startIndex = readOffset, endIndex = readOffset + count)
        readOffset += count
        return count
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        written.write(buffer, offset, length)
    }

    override suspend fun close() = Unit

    fun writtenBytes(): ByteArray = written.toByteArray()
    fun writtenText(): String = writtenBytes().toString(Charsets.US_ASCII)
}

private class InteractiveStream : DuplexByteStream {
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val written = ByteArrayOutputStream()
    private var current = ByteArray(0)
    private var currentOffset = 0
    val closeCalls = AtomicInteger()

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (currentOffset == current.size) {
            current = incoming.receiveCatching().getOrNull() ?: return -1
            currentOffset = 0
        }
        val count = minOf(length, current.size - currentOffset)
        current.copyInto(buffer, offset, currentOffset, currentOffset + count)
        currentOffset += count
        return count
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        written.write(buffer, offset, length)
    }

    override suspend fun close() {
        if (closeCalls.incrementAndGet() == 1) incoming.close()
    }

    suspend fun feedFixture(name: String) = feedText(fixture(name))

    suspend fun feedText(text: String) {
        incoming.send(serverTextFrame(text))
    }

    fun closeIncoming() {
        incoming.close()
    }

    suspend fun awaitClosed() {
        while (closeCalls.get() == 0) yield()
    }

    fun writtenBytes(): ByteArray = written.toByteArray()
}

private fun fixture(name: String): String =
    object {}.javaClass.getResource("/app-server/v2/$name")?.readText()
        ?: error("Missing fixture $name")

private fun serverTextFrame(text: String): ByteArray {
    val payload = text.toByteArray(Charsets.UTF_8)
    return serverFrame(fin = true, opcode = 0x1, payload = payload)
}

private fun serverFrame(fin: Boolean, opcode: Int, payload: ByteArray): ByteArray {
    val first = ((if (fin) 0x80 else 0) or opcode).toByte()
    return when {
        payload.size < 126 -> byteArrayOf(first, payload.size.toByte()) + payload
        payload.size <= 0xFFFF ->
            byteArrayOf(first, 126, (payload.size ushr 8).toByte(), payload.size.toByte()) + payload
        else -> error("Test frame is too large")
    }
}

private fun ByteArray.containsClientText(text: String): Boolean {
    val payload = text.toByteArray(Charsets.UTF_8)
    return indices.any { index ->
        index + payload.size <= size && payload.indices.all { payloadIndex ->
            this[index + payloadIndex] == payload[payloadIndex]
        }
    }
}
