package com.code2hack.pokerdealer.protocol

import java.security.MessageDigest
import java.util.Base64

/** Transport-neutral retained byte utilities used at the Codex app-server image-input boundary. */
object PhotoAssetCodec {
    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun decode(encoded: String): ByteArray = Base64.getDecoder().decode(encoded)

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun dataUrl(mimeType: String, bytes: ByteArray): String =
        "data:$mimeType;base64,${encode(bytes)}"
}
