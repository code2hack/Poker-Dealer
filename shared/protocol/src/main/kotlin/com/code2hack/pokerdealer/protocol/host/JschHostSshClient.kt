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
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

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
    private val maxCommandOutputBytes: Int = 1_048_576,
) : HostSshClient {
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
        val session = jsch.getSession(authentication.username, host.id, 22).apply {
            setProxy(DuplexStreamProxy(tcpStream))
            setConfig("StrictHostKeyChecking", "yes")
            setConfig("PreferredAuthentications", "publickey")
        }
        try {
            session.connect(connectTimeoutMs)
            JschHostSshSession(session, channelTimeoutMs, maxCommandOutputBytes)
        } catch (failure: JSchChangedHostKeyException) {
            session.disconnect()
            throw HostIdentityException("SSH host key changed for ${host.id}", failure)
        } catch (failure: JSchUnknownHostKeyException) {
            session.disconnect()
            throw HostIdentityException("SSH host key is not pinned for ${host.id}", failure)
        } catch (failure: JSchRevokedHostKeyException) {
            session.disconnect()
            throw HostIdentityException("SSH host key is revoked for ${host.id}", failure)
        } catch (failure: Throwable) {
            session.disconnect()
            throw failure
        }
    }
}

private class JschHostSshSession(
    private val session: Session,
    private val channelTimeoutMs: Int,
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
            val stdout = channel.inputStream
            val stderr = channel.errStream
            withContext(Dispatchers.IO) { channel.connect(channelTimeoutMs) }
            coroutineScope {
                val stdoutRead = async(Dispatchers.IO) { stdout.readLimited(maxCommandOutputBytes) }
                val stderrRead = async(Dispatchers.IO) { stderr.readLimited(maxCommandOutputBytes) }
                val stdoutText = stdoutRead.await()
                val stderrText = stderrRead.await()
                CommandResult(
                    exitCode = channel.exitStatus,
                    stdout = stdoutText,
                    stderr = stderrText,
                )
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
            channel.connect(channelTimeoutMs)
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
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = withContext(Dispatchers.IO) {
        input.read(buffer, offset, length)
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) = withContext(Dispatchers.IO) {
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
