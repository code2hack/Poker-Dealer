package com.code2hack.dealer

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Movie
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.code2hack.pokerdealer.protocol.PhotoAssetCodec

internal data class DealerPhotoImage(
    val mimeType: String,
    val bytes: ByteArray,
)

/** Exact, backup-excluded photo bytes. The database stores only draft asset IDs. */
internal class DealerPhotoAssetStore private constructor(
    private val root: File,
    private val storageManager: StorageManager?,
) {
    constructor(context: Context) : this(
        context.noBackupFilesDir.resolve("photo-assets"),
        context.getSystemService(StorageManager::class.java),
    )

    internal constructor(root: File) : this(root, null)

    init {
        require(root.isDirectory || root.mkdirs()) { "Unable to create Dealer photo storage" }
    }

    suspend fun begin(assetId: String, expectedBytes: Long): Boolean = withContext(Dispatchers.IO) {
        val file = staged(assetId)
        if (!hasSpace(expectedBytes)) return@withContext false
        file.delete()
        file.createNewFile()
    }

    suspend fun append(assetId: String, offset: Long, bytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            if (offset < 0 || !hasSpace(bytes.size.toLong())) return@withContext false
            val file = staged(assetId)
            if (!file.isFile || file.length() != offset) return@withContext false
            RandomAccessFile(file, "rw").use { output ->
                output.seek(offset)
                output.write(bytes)
                output.fd.sync()
            }
            true
        }

    suspend fun commit(
        assetId: String,
        mimeType: String,
        expectedLength: Long,
        expectedSha256: String,
    ): DealerPhotoImage? = withContext(Dispatchers.IO) {
        val file = staged(assetId)
        if (!file.isFile || file.length() != expectedLength) {
            file.delete()
            return@withContext null
        }
        val bytes = file.readBytes()
        if (!validatePhotoAsset(bytes, mimeType, expectedLength, expectedSha256)) {
            file.delete()
            return@withContext null
        }
        val destination = stored(assetId)
        if (destination.exists()) destination.delete()
        if (!file.renameTo(destination)) {
            file.delete()
            return@withContext null
        }
        DealerPhotoImage(mimeType, bytes)
    }

    suspend fun read(assetId: String): DealerPhotoImage? = withContext(Dispatchers.IO) {
        val file = stored(assetId)
        if (!file.isFile) return@withContext null
        val bytes = file.readBytes()
        PhotoImageFormat.detect(bytes)?.let { DealerPhotoImage(it, bytes) }
    }

    suspend fun delete(assetId: String) = withContext(Dispatchers.IO) {
        stored(assetId).delete()
        staged(assetId).delete()
    }

    /** Removes an asset only after the matching draft update commits, restoring it on failure. */
    suspend fun deleteAfter(assetId: String, updateDraft: suspend () -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            recoverDeletionResiduesInPlace()
            val source = stored(assetId)
            if (!source.isFile) return@withContext false
            val deleting = root.resolve("${safeName(assetId)}.deleting")
            if (deleting.exists() && !deleting.delete()) return@withContext false
            if (!source.renameTo(deleting)) return@withContext false
            try {
                updateDraft()
                deleting.delete()
                true
            } catch (failure: Throwable) {
                if (!deleting.renameTo(source)) {
                    throw IllegalStateException("Unable to restore photo asset $assetId", failure)
                }
                throw failure
            }
        }

    suspend fun purgeExcept(assetIds: Set<String>) = withContext(Dispatchers.IO) {
        recoverDeletionResiduesInPlace()
        root.listFiles().orEmpty().filter { file ->
            assetIdFor(file) !in assetIds
        }.forEach(File::delete)
    }

    private fun recoverDeletionResiduesInPlace() {
        root.listFiles().orEmpty()
            .filter { it.name.endsWith(DELETING_SUFFIX) }
            .forEach { residue ->
                val assetId = residue.name.removeSuffix(DELETING_SUFFIX)
                if (!assetId.matches(ASSET_ID)) {
                    if (!residue.delete()) {
                        throw IOException("Unable to remove invalid photo deletion residue ${residue.name}")
                    }
                    return@forEach
                }
                val source = stored(assetId)
                if (source.exists()) {
                    if (!residue.delete()) {
                        throw IOException("Unable to remove duplicate photo deletion residue ${residue.name}")
                    }
                } else if (!residue.renameTo(source)) {
                    throw IOException("Unable to restore photo asset $assetId from deletion residue")
                }
            }
    }

    private fun assetIdFor(file: File): String = when {
        file.name.endsWith(STAGING_SUFFIX) -> file.name.removeSuffix(STAGING_SUFFIX)
        file.name.endsWith(DELETING_SUFFIX) -> file.name.removeSuffix(DELETING_SUFFIX)
        else -> file.name
    }

    private fun hasSpace(bytes: Long): Boolean {
        if (bytes < 0) return false
        val allocatable = storageManager?.let { manager ->
            runCatching {
                manager.getAllocatableBytes(manager.getUuidForPath(root))
            }.getOrNull()
        } ?: StatFs(root.path).availableBytes
        return photoStorageHasRoom(allocatable, bytes)
    }

    private fun stored(assetId: String) = root.resolve(safeName(assetId))

    private fun staged(assetId: String) = root.resolve("${safeName(assetId)}.staging")

    private fun safeName(assetId: String): String {
        require(assetId.matches(ASSET_ID)) { "Invalid photo asset id" }
        return assetId
    }

    private companion object {
        val ASSET_ID = Regex("[A-Za-z0-9._-]{1,128}")
        const val STAGING_SUFFIX = ".staging"
        const val DELETING_SUFFIX = ".deleting"
    }
}

internal object PhotoImageFormat {
    fun headerMime(bytes: ByteArray): String? = when {
        bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) -> "image/jpeg"
        bytes.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".encodeToByteArray()) &&
            bytes.copyOfRange(8, 12).contentEquals("WEBP".encodeToByteArray()) -> "image/webp"
        bytes.size >= 6 && (bytes.copyOfRange(0, 6).contentEquals("GIF87a".encodeToByteArray()) ||
            bytes.copyOfRange(0, 6).contentEquals("GIF89a".encodeToByteArray())) -> "image/gif"
        else -> null
    }

    fun detect(bytes: ByteArray): String? = headerMime(bytes)?.takeIf { mime ->
        val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        bounds.outWidth > 0 && bounds.outHeight > 0 &&
            (mime != "image/gif" || Movie.decodeByteArray(bytes, 0, bytes.size)?.duration() == 0)
    }

}

internal fun validatePhotoAsset(
    bytes: ByteArray,
    mimeType: String,
    expectedLength: Long,
    expectedSha256: String,
    detectFormat: (ByteArray) -> String? = PhotoImageFormat::detect,
): Boolean = bytes.size.toLong() == expectedLength &&
    PhotoAssetCodec.sha256(bytes) == expectedSha256 &&
    detectFormat(bytes) == mimeType

internal fun photoStorageHasRoom(
    availableBytes: Long,
    requiredBytes: Long,
    lowStorageBytes: Long = 0L,
): Boolean = requiredBytes >= 0L && lowStorageBytes >= 0L &&
    availableBytes >= requiredBytes && availableBytes - requiredBytes >= lowStorageBytes

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
