package com.everyonesoftware.audio.data

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class OfflineCollectionsTest {
    private val connection = Connection("a", "A", "https://music.example", "alice")
    private val song = Song("one", "One", "Artist", coverArtId = "song-cover")

    @Test fun `collection removal retains shared and explicit owners and releases orphan pins`() {
        val dir = Files.createTempDirectory("owners").toFile()
        try {
            val index = OfflineIndex(dir)
            val a = OfflineCollection("album", "a", "Album", songs = listOf(song, song.copy(id = "only-a")))
            val b = OfflineCollection("playlist", "b", "Playlist", songs = listOf(song))
            index.saveCollection(connection, a)
            index.saveCollection(connection, b)
            val key = OfflineIndex.key(connection, song.id)
            index.remember(key, connection.id, song, pinned = true)
            index.removeCollection(connection, "album", "a")
            assertFalse(index.entries().first { it.song.id == "only-a" }.pinned)
            index.unpin(key)
            assertTrue(index.entries().first { it.key == key }.pinned)
            index.removeCollection(connection, "playlist", "b")
            assertFalse(OfflineIndex(dir).entries().first { it.key == key }.pinned)
        } finally { dir.deleteRecursively() }
    }
    @Test fun `failed snapshot refresh leaves durable and live previous snapshot unchanged`() {
        val dir = Files.createTempDirectory("atomic-collections").toFile()
        try {
            val index = OfflineIndex(dir)
            val a = OfflineCollection("album", "a", "Before", songs = listOf(song))
            index.saveCollection(connection, a)
            java.io.File(dir, "catalog.tmp").mkdir()
            try { index.saveCollection(connection, a.copy(name = "After")); fail("Expected storage failure") } catch (_: java.io.IOException) { }
            assertEquals(listOf(a), index.collections(connection))
            assertEquals(listOf(a), OfflineIndex(dir).collections(connection))
        } finally { dir.deleteRecursively() }
    }
    @Test fun `partial collection never silently filters unavailable or duplicate tracks`() {
        val a = OfflineCollection("playlist", "a", "Mix", songs = listOf(song, song.copy(id = "missing"), song))
        assertEquals(2, a.availableCount { it == song.id })
        try { a.playableSongs { it == song.id }; fail("Partial collection played") } catch (_: IllegalArgumentException) { }
        assertEquals(a.songs, a.playableSongs { true })
    }
    @Test fun `account purge removes snapshots and pins but leaves another account intact`() {
        val dir = Files.createTempDirectory("collection-purge").toFile()
        try {
            val index = OfflineIndex(dir)
            val a = OfflineCollection("album", "a", "Album", songs = listOf(song))
            val other = connection.copy(id = "b", username = "bob")
            index.saveCollection(connection, a)
            index.saveCollection(other, a)
            index.removeConnection(connection.id)
            assertTrue(index.collections(connection).isEmpty())
            assertEquals(listOf(a), OfflineIndex(dir).collections(other))
            assertEquals(listOf("b"), index.entries().map { it.connectionId })
        } finally { dir.deleteRecursively() }
    }
    @Test fun `snapshot survives restart with metadata exact order duplicates and account isolation`() {
        val dir = Files.createTempDirectory("collections").toFile()
        try {
            val index = OfflineIndex(dir)
            val snapshot = OfflineCollection("playlist", "mix", "My mix", "Artist", 2026, "cover", listOf(song, song.copy(id = "two"), song))
            index.saveCollection(connection, snapshot)
            assertEquals(listOf(snapshot), OfflineIndex(dir).collections(connection))
            assertTrue(index.collections(connection.copy(username = "bob")).isEmpty())
            assertTrue(index.collections(connection.copy(baseUrl = "https://other.example")).isEmpty())
            assertEquals(listOf("one", "two", "one"), snapshot.songs.map { it.id })
        } finally { dir.deleteRecursively() }
    }
}
