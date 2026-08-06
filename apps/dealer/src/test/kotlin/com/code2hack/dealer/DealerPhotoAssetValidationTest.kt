package com.code2hack.dealer

import com.code2hack.pokerdealer.protocol.PhotoAssetCodec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DealerPhotoAssetValidationTest {
    @Test
    fun `asset validation requires exact length digest and format`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
        val digest = PhotoAssetCodec.sha256(bytes)

        assertTrue(
            validatePhotoAsset(
                bytes = bytes,
                mimeType = "image/jpeg",
                expectedLength = bytes.size.toLong(),
                expectedSha256 = digest,
                detectFormat = PhotoImageFormat::headerMime,
            ),
        )
        assertFalse(
            validatePhotoAsset(
                bytes = bytes,
                mimeType = "image/jpeg",
                expectedLength = bytes.size.toLong() + 1,
                expectedSha256 = digest,
                detectFormat = PhotoImageFormat::headerMime,
            ),
        )
        assertFalse(
            validatePhotoAsset(
                bytes = bytes,
                mimeType = "image/jpeg",
                expectedLength = bytes.size.toLong(),
                expectedSha256 = PhotoAssetCodec.sha256(bytes + 1),
                detectFormat = PhotoImageFormat::headerMime,
            ),
        )
    }

    @Test
    fun `unsupported format is rejected without conversion`() {
        val bytes = "not an image".encodeToByteArray()

        assertNull(PhotoImageFormat.headerMime(bytes))
        assertFalse(
            validatePhotoAsset(
                bytes = bytes,
                mimeType = "image/jpeg",
                expectedLength = bytes.size.toLong(),
                expectedSha256 = PhotoAssetCodec.sha256(bytes),
                detectFormat = PhotoImageFormat::headerMime,
            ),
        )
    }

    @Test
    fun `storage gate preserves the native low-storage margin`() {
        assertTrue(photoStorageHasRoom(1_000, 400, lowStorageBytes = 600))
        assertFalse(photoStorageHasRoom(1_000, 401, lowStorageBytes = 600))
        assertFalse(photoStorageHasRoom(100, 101))
        assertFalse(photoStorageHasRoom(100, -1))
    }
}
