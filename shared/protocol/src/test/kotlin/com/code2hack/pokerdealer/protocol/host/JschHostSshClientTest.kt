package com.code2hack.pokerdealer.protocol.host

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class JschHostSshClientTest {
    @Test
    fun `raw pinned key restricts negotiation to its key family`() {
        assertEquals(
            listOf("ssh-ed25519"),
            pinnedServerHostKeyAlgorithms("ssh-ed25519", marker = null),
        )
        assertEquals(
            listOf("ecdsa-sha2-nistp256"),
            pinnedServerHostKeyAlgorithms("ecdsa-sha2-nistp256", marker = null),
        )
    }

    @Test
    fun `rsa pin uses SHA-2 host signatures and never enables legacy ssh-rsa`() {
        val algorithms = pinnedServerHostKeyAlgorithms("ssh-rsa", marker = null)

        assertEquals(listOf("rsa-sha2-512", "rsa-sha2-256"), algorithms)
        assertFalse("ssh-rsa" in algorithms)
    }

    @Test
    fun `certificate authority pin negotiates certificate algorithms only`() {
        assertEquals(
            listOf("ssh-ed25519-cert-v01@openssh.com"),
            pinnedServerHostKeyAlgorithms("ssh-ed25519", marker = "@cert-authority"),
        )
        assertEquals(
            listOf("rsa-sha2-512-cert-v01@openssh.com", "rsa-sha2-256-cert-v01@openssh.com"),
            pinnedServerHostKeyAlgorithms("ssh-rsa", marker = "@cert-authority"),
        )
    }

    @Test
    fun `unsupported pin type fails closed before negotiation`() {
        assertEquals(emptyList<String>(), pinnedServerHostKeyAlgorithms("ssh-dss", marker = null))
    }
}
