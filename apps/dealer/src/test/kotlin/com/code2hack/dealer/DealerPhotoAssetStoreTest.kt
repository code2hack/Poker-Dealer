package com.code2hack.dealer

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DealerPhotoAssetStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `deleteAfter removes the asset only after the draft update`() = runBlocking {
        val root = temporaryFolder.newFolder()
        val store = DealerPhotoAssetStore(root)
        val asset = writeAsset(root, "asset-1", byteArrayOf(1, 2, 3))
        var updated = false

        assertTrue(store.deleteAfter("asset-1") { updated = true })

        assertTrue(updated)
        assertFalse(asset.exists())
        assertFalse(root.resolve("asset-1.deleting").exists())
    }

    @Test
    fun `deleteAfter restores the asset when the draft update fails`() = runBlocking {
        val root = temporaryFolder.newFolder()
        val store = DealerPhotoAssetStore(root)
        val bytes = byteArrayOf(4, 5, 6)
        val asset = writeAsset(root, "asset-2", bytes)

        val failure = runCatching {
            store.deleteAfter("asset-2") { error("draft write failed") }
        }.exceptionOrNull()

        assertTrue(failure?.message?.contains("draft write failed") == true)
        assertArrayEquals(bytes, asset.readBytes())
        assertFalse(root.resolve("asset-2.deleting").exists())
    }

    @Test
    fun `purge recovers a deletion residue before retaining the referenced asset`() = runBlocking {
        val root = temporaryFolder.newFolder()
        val store = DealerPhotoAssetStore(root)
        val bytes = byteArrayOf(7, 8, 9)
        root.resolve("asset-3.deleting").writeBytes(bytes)

        store.purgeExcept(setOf("asset-3"))

        assertArrayEquals(bytes, root.resolve("asset-3").readBytes())
        assertFalse(root.resolve("asset-3.deleting").exists())
    }

    @Test
    fun `purge removes unreferenced committed and crashed assets`() = runBlocking {
        val root = temporaryFolder.newFolder()
        val store = DealerPhotoAssetStore(root)
        writeAsset(root, "orphan", byteArrayOf(1))
        root.resolve("crashed.deleting").writeBytes(byteArrayOf(2))

        store.purgeExcept(emptySet())

        assertFalse(root.resolve("orphan").exists())
        assertFalse(root.resolve("crashed").exists())
        assertFalse(root.resolve("crashed.deleting").exists())
    }

    @Test
    fun `purge retains an uncertain asset referenced by the draft`() = runBlocking {
        val root = temporaryFolder.newFolder()
        val store = DealerPhotoAssetStore(root)
        val bytes = byteArrayOf(3, 1, 4)
        val asset = writeAsset(root, "uncertain.with.dots", bytes)

        store.purgeExcept(setOf("uncertain.with.dots"))

        assertArrayEquals(bytes, asset.readBytes())
    }

    private fun writeAsset(root: File, assetId: String, bytes: ByteArray): File =
        root.resolve(assetId).also { it.writeBytes(bytes) }
}
