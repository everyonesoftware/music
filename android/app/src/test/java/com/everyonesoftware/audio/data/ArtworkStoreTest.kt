package com.everyonesoftware.audio.data

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ArtworkStoreTest {
    private val connection = Connection("account", "Music", "https://music.example", "alice")

    @Test fun `authoritative download snapshot removes obsolete collection pins after reopen`() {
        val root = Files.createTempDirectory("artwork-reconcile").toFile()
        try {
            var store = ArtworkStore(root)
            store.replaceOwner(connection, "collection:playlist:removed", listOf("old"))
            store.put(connection, "old", byteArrayOf(1))
            store.syncOwners(connection, mapOf("collection:album:kept" to listOf("shared")))
            store.put(connection, "shared", byteArrayOf(2))
            store = ArtworkStore(root)
            store.clearAutomatic()
            assertNull(store.read(connection, "old"))
            assertNotNull(store.read(connection, "shared"))
        } finally { root.deleteRecursively() }
    }

    @Test fun `metadata write failure does not deny intact artwork or discard durable owner`() {
        val root = Files.createTempDirectory("artwork-readonly").toFile()
        try {
            val store = ArtworkStore(root)
            store.replaceOwner(connection, "song:first", listOf("cover"))
            store.put(connection, "cover", byteArrayOf(1, 2))
            assertTrue(java.io.File(root, "artwork.tmp").mkdir())
            assertArrayEquals(byteArrayOf(1, 2), store.read(connection, "cover"))
            try { store.removeOwner(connection, "song:first"); fail("Failed metadata removal reported success") }
            catch (_: java.io.IOException) { }
            assertEquals(2L, ArtworkStore(root).usage().second)
        } finally { root.deleteRecursively() }
    }

    @Test fun `recently viewed artwork is kept and image ids cannot be authenticated urls`() {
        val root = Files.createTempDirectory("artwork-lru").toFile()
        try {
            val store = ArtworkStore(root, automaticLimit = 4)
            store.put(connection, "first", byteArrayOf(1, 1))
            store.put(connection, "second", byteArrayOf(2, 2))
            store.read(connection, "first")
            store.put(connection, "third", byteArrayOf(3, 3))
            assertNotNull(store.read(connection, "first"))
            assertNull(store.read(connection, "second"))
            try { store.put(connection, "https://music.example/art?t=secret", byteArrayOf(1)); fail("Accepted signed URL") }
            catch (_: IllegalArgumentException) { }
        } finally { root.deleteRecursively() }
    }

    @Test fun `account removal purges bytes and durable pin intentions without touching another account`() {
        val root = Files.createTempDirectory("artwork-purge").toFile()
        try {
            val other = connection.copy(id = "other", username = "bob")
            val store = ArtworkStore(root)
            store.replaceOwner(connection, "song:a", listOf("cover"))
            store.put(connection, "cover", byteArrayOf(1))
            store.put(other, "cover", byteArrayOf(2))
            store.removeConnection(connection.id)
            val reopened = ArtworkStore(root)
            assertNull(reopened.read(connection, "cover"))
            assertArrayEquals(byteArrayOf(2), reopened.read(other, "cover"))
            assertEquals(0L, reopened.usage().second)
        } finally { root.deleteRecursively() }
    }

    @Test fun `corrupt or oversized artwork is not exposed as a local image`() {
        val root = Files.createTempDirectory("artwork-integrity").toFile()
        try {
            val store = ArtworkStore(root)
            store.put(connection, "cover", byteArrayOf(1, 2, 3))
            val file = store.file(connection, "cover")!!
            file.writeBytes(byteArrayOf(7, 8, 9))
            assertNull(ArtworkStore(root).file(connection, "cover"))
            file.writeBytes(byteArrayOf(1))
            assertNull(store.read(connection, "cover"))
            try { store.put(connection, "huge", ByteArray(2 * 1024 * 1024 + 1)); fail("Oversized image accepted") }
            catch (_: IllegalArgumentException) { }
        } finally { root.deleteRecursively() }
    }

    @Test fun `collection artwork pins survive clear and shared owners release independently`() {
        val root = Files.createTempDirectory("artwork-pins").toFile()
        try {
            var store = ArtworkStore(root, automaticLimit = 4)
            store.replaceOwner(connection, "collection:playlist:one", listOf("shared"))
            store.replaceOwner(connection, "collection:album:two", listOf("shared"))
            store.put(connection, "shared", byteArrayOf(1, 2, 3, 4, 5))
            store.put(connection, "automatic", byteArrayOf(6, 7))
            store = ArtworkStore(root, automaticLimit = 4)
            assertEquals(5L, store.usage().second)
            store.removeOwner(connection, "collection:playlist:one")
            store.clearAutomatic()
            assertNotNull(store.read(connection, "shared"))
            assertNull(store.read(connection, "automatic"))
            store.removeOwner(connection, "collection:album:two")
            store.clearAutomatic()
            assertNull(store.read(connection, "shared"))
        } finally { root.deleteRecursively() }
    }

    @Test fun `art survives reopening and is isolated by server and account`() {
        val root = Files.createTempDirectory("artwork-store").toFile()
        try {
            val bytes = byteArrayOf(1, 2, 3, 4)
            ArtworkStore(root).put(connection, "cover", bytes)
            val restored = ArtworkStore(root)
            assertArrayEquals(bytes, restored.read(connection, "cover"))
            assertNull(restored.read(connection.copy(username = "bob"), "cover"))
            assertNull(restored.read(connection.copy(baseUrl = "https://other.example"), "cover"))
            assertTrue(restored.file(connection, "cover")!!.isFile)
            assertFalse(root.walkTopDown().filter { it.isFile }.any { it.readText().contains("https://music.example") })
        } finally { root.deleteRecursively() }
    }
}
