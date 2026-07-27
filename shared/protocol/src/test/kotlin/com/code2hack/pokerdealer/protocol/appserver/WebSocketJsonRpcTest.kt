package com.code2hack.pokerdealer.protocol.appserver

import com.code2hack.pokerdealer.protocol.host.DuplexByteStream
import com.code2hack.pokerdealer.protocol.host.ConnectionPhaseTimeoutException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

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
        val stream = MemoryStream(
            serverTextFrame("""{"id":7,"method":"future/request","params":{"value":1}}""") +
                serverTextFrame("""{"method":"future/event","params":{"value":2},"futureField":true}"""),
        )
        val peer = WebSocketJsonRpcPeer(
            AppServerWebSocket(stream, maskFactory = { byteArrayOf(0, 0, 0, 0) }),
        )

        val notification = peer.receiveNotification()

        assertEquals("future/event", notification?.method)
        assertEquals("true", notification?.raw?.get("futureField").toString())
        assertTrue(stream.writtenBytes().containsClientText("Dealer cannot handle server request"))
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

private fun serverTextFrame(text: String): ByteArray {
    val payload = text.toByteArray(Charsets.UTF_8)
    return serverFrame(fin = true, opcode = 0x1, payload = payload)
}

private fun serverFrame(fin: Boolean, opcode: Int, payload: ByteArray): ByteArray {
    require(payload.size < 126)
    return byteArrayOf(((if (fin) 0x80 else 0) or opcode).toByte(), payload.size.toByte()) + payload
}

private fun ByteArray.containsClientText(text: String): Boolean {
    val payload = text.toByteArray(Charsets.UTF_8)
    return indices.any { index ->
        index + payload.size <= size && payload.indices.all { payloadIndex ->
            this[index + payloadIndex] == payload[payloadIndex]
        }
    }
}
