package com.code2hack.pokerdealer.protocol.host

import com.code2hack.pokerdealer.domain.CodexHost
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchRevokedHostKeyException
import com.jcraft.jsch.JSchUnknownHostKeyException
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SshHostAuthentication(
    val username: String,
    val privateKey: ByteArray,
    val publicKey: ByteArray? = null,
    val passphrase: ByteArray? = null,
    val knownHosts: ByteArray,
)

class JschHostSshClient(
    private val authenticationByHostId: Map<String, SshHostAuthentication>,
    private val connectTimeoutMs: Int = 10_000,
    private val channelTimeoutMs: Int = 10_000,
    private val commandTimeoutMs: Long = 30_000,
    private val maxCommandOutputBytes: Int = 1_048_576,
) : HostSshClient {
    init {
        require(connectTimeoutMs > 0) { "SSH connect timeout must be positive" }
        require(channelTimeoutMs > 0) { "SSH channel timeout must be positive" }
        require(commandTimeoutMs > 0) { "SSH command timeout must be positive" }
        require(maxCommandOutputBytes > 0) { "SSH command output limit must be positive" }
    }

    override suspend fun connect(
        host: CodexHost,
        tcpStream: DuplexByteStream,
    ): HostSshSession = withContext(Dispatchers.IO) {
        val authentication = authenticationByHostId[host.id]
            ?: error("No SSH authentication configured for ${host.id}")
        require(authentication.username.isNotBlank()) { "SSH username is required" }
        require(authentication.privateKey.isNotEmpty()) { "SSH private key is required" }
        require(authentication.knownHosts.isNotEmpty()) { "Pinned SSH known_hosts data is required" }

        val jsch = JSch().apply {
            setKnownHosts(ByteArrayInputStream(authentication.knownHosts))
            addIdentity(
                "${host.id}-dealer",
                authentication.privateKey,
                authentication.publicKey,
                authentication.passphrase,
            )
        }
        val pinnedHostKeys = jsch.hostKeyRepository.getHostKey(host.id, null).orEmpty()
        require(pinnedHostKeys.isNotEmpty()) {
            "Pinned SSH known_hosts data has no entry for ${host.id}"
        }
        val pinnedAlgorithms = pinnedHostKeys
            .flatMap { pinnedServerHostKeyAlgorithms(it.type, it.marker) }
            .distinct()
        require(pinnedAlgorithms.isNotEmpty()) {
            "Pinned SSH known_hosts data has no supported host-key algorithm for ${host.id}"
        }
        val session = jsch.getSession(authentication.username, host.id, 22).apply {
            setProxy(DuplexStreamProxy(tcpStream))
            setConfig("StrictHostKeyChecking", "yes")
            setConfig("PreferredAuthentications", "publickey")
            setConfig("server_host_key", pinnedAlgorithms.joinToString(","))
        }
        try {
            cancellableBlocking(
                onCancel = {
                    session.disconnect()
                    runBlocking { tcpStream.close() }
                },
            ) {
                session.connect(connectTimeoutMs)
            }
            JschHostSshSession(session, channelTimeoutMs, commandTimeoutMs, maxCommandOutputBytes)
        } catch (failure: JSchChangedHostKeyException) {
            session.disconnect()
            throw HostIdentityException("SSH host key changed for ${host.id}: ${failure.message}", failure)
        } catch (failure: JSchUnknownHostKeyException) {
            session.disconnect()
            throw HostIdentityException("SSH host key is not pinned for ${host.id}: ${failure.message}", failure)
        } catch (failure: JSchRevokedHostKeyException) {
            session.disconnect()
            throw HostIdentityException("SSH host key is revoked for ${host.id}: ${failure.message}", failure)
        } catch (failure: Throwable) {
            session.disconnect()
            throw failure
        }
    }
}

internal fun pinnedServerHostKeyAlgorithms(type: String, marker: String?): List<String> {
    val certificate = marker == "@cert-authority"
    return when (type) {
        "ssh-ed25519" -> listOf(
            if (certificate) "ssh-ed25519-cert-v01@openssh.com" else "ssh-ed25519",
        )
        "ssh-ed448" -> listOf(
            if (certificate) "ssh-ed448-cert-v01@openssh.com" else "ssh-ed448",
        )
        "ecdsa-sha2-nistp256",
        "ecdsa-sha2-nistp384",
        "ecdsa-sha2-nistp521",
        -> listOf(if (certificate) "$type-cert-v01@openssh.com" else type)
        "ssh-rsa" -> if (certificate) {
            listOf("rsa-sha2-512-cert-v01@openssh.com", "rsa-sha2-256-cert-v01@openssh.com")
        } else {
            listOf("rsa-sha2-512", "rsa-sha2-256")
        }
        else -> emptyList()
    }
}

private class JschHostSshSession(
    private val session: Session,
    private val channelTimeoutMs: Int,
    private val commandTimeoutMs: Long,
    private val maxCommandOutputBytes: Int,
) : HostSshSession {
    override suspend fun exec(command: String): CommandResult {
        val channel = withContext(Dispatchers.IO) {
            (session.openChannel("exec") as ChannelExec).apply {
                setCommand(command)
                setInputStream(null)
            }
        }
        return try {
            withConnectionPhaseTimeout("SSH command", commandTimeoutMs) {
                val stdout = channel.inputStream
                val stderr = channel.errStream
                cancellableBlocking(channel::disconnect) {
                    channel.connect(channelTimeoutMs)
                }
                coroutineScope {
                    val stdoutRead = async {
                        cancellableBlocking(channel::disconnect) { stdout.readLimited(maxCommandOutputBytes) }
                    }
                    val stderrRead = async {
                        cancellableBlocking(channel::disconnect) { stderr.readLimited(maxCommandOutputBytes) }
                    }
                    val stdoutText = stdoutRead.await()
                    val stderrText = stderrRead.await()
                    CommandResult(
                        exitCode = channel.exitStatus,
                        stdout = stdoutText,
                        stderr = stderrText,
                    )
                }
            }
        } finally {
            channel.disconnect()
        }
    }

    override suspend fun execStream(command: String): DuplexByteStream = withContext(Dispatchers.IO) {
        val channel = (session.openChannel("exec") as ChannelExec).apply {
            setCommand(command)
            setErrStream(DiscardingOutputStream)
        }
        try {
            val input = channel.inputStream
            val output = channel.outputStream
            cancellableBlocking(channel::disconnect) {
                channel.connect(channelTimeoutMs)
            }
            JschChannelStream(channel, input, output)
        } catch (failure: Throwable) {
            channel.disconnect()
            throw failure
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        session.disconnect()
    }
}

private fun InputStream.readLimited(limit: Int): String {
    val bytes = readNBytes(limit + 1)
    require(bytes.size <= limit) { "SSH command output exceeds $limit bytes" }
    return bytes.toString(Charsets.UTF_8)
}

private class JschChannelStream(
    private val channel: ChannelExec,
    private val input: InputStream,
    private val output: OutputStream,
) : DuplexByteStream {
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        cancellableBlocking(channel::disconnect) {
            input.read(buffer, offset, length)
        }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) =
        cancellableBlocking(channel::disconnect) {
            output.write(buffer, offset, length)
            output.flush()
        }

    override suspend fun close() = withContext(Dispatchers.IO) {
        channel.disconnect()
    }
}

private class DuplexStreamProxy(
    private val stream: DuplexByteStream,
) : Proxy {
    private val input = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            while (true) {
                when (read(one)) {
                    -1 -> return -1
                    0 -> continue
                    else -> return one[0].toInt() and 0xFF
                }
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            runBlocking { stream.read(buffer, offset, length) }
    }
    private val output = object : OutputStream() {
        override fun write(value: Int) {
            write(byteArrayOf(value.toByte()))
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            runBlocking { stream.write(buffer, offset, length) }
        }
    }

    override fun connect(socketFactory: SocketFactory?, host: String?, port: Int, timeout: Int) = Unit
    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output
    override fun getSocket(): Socket? = null
    override fun close() = runBlocking { stream.close() }
}

private object DiscardingOutputStream : OutputStream() {
    override fun write(value: Int) = Unit
    override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
}

private suspend fun <T> cancellableBlocking(
    onCancel: () -> Unit,
    operation: () -> T,
): T = withContext(Dispatchers.IO) {
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching(onCancel) }
        try {
            val result = operation()
            if (continuation.isActive) continuation.resume(result)
        } catch (failure: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
    }
}
