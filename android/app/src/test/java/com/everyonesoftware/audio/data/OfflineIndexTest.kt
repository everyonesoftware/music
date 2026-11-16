package com.everyonesoftware.audio.data

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class OfflineIndexTest {
    @Test fun `keys isolate server account and representation without retaining secrets`() {
        val a = Credentials(Connection("a", "A", "https://music.example", "alice"), "secret")
        val b = a.copy(connection = a.connection.copy(username = "bob"))
        val key = OfflineIndex.key(a.connection, "song/1")
        assertNotEquals(key, OfflineIndex.key(b.connection, "song/1"))
        assertNotEquals(key, OfflineIndex.key(a.connection, "song/1", "mp3"))
        assertTrue(key.matches(Regex("[a-f0-9]{64}")))
    }
    @Test fun `priority is current next recent partials then further queue and never other account`() {
        val songs = (0..5).map { Song("$it", "$it", "Artist") }
        val partial = OfflineEntry("partial", "account", Song("recent", "Recent", "Artist"))
        val other = partial.copy(key = "other", connectionId = "other", song = Song("foreign", "Foreign", "Other"))
        assertEquals(listOf("0", "1", "recent", "2", "3"),
            OfflinePlan.songs(songs, 0, 3, listOf(partial, other), "account").map { it.id })
        assertEquals(listOf("0", "recent"), OfflinePlan.songs(songs, 0, 0, listOf(partial), "account").map { it.id })
    }
    @Test fun `partial recovery is bounded so history cannot starve upcoming queue`() {
        val queue = (0..4).map { Song("queue$it", "Title", "Artist") }
        val history = (0..100).map { OfflineEntry("key$it", "account", Song("old$it", "Old", "Artist"), touched = it.toLong()) }
        val plan = OfflinePlan.songs(queue, 0, 3, history, "account")
        assertEquals(listOf("queue0", "queue1", "old100", "old99", "old98", "queue2", "queue3"), plan.map { it.id })
    }
    @Test fun `repeat all wraps prefetch and repeat one stays on the current song`() {
        val queue = (0..3).map { Song("$it", "Title", "Artist") }
        assertEquals(listOf("3", "0", "1", "2"), OfflinePlan.songs(queue, 3, 3, emptyList(), "a", repeatMode = 2).map { it.id })
        assertEquals(listOf("2"), OfflinePlan.songs(queue, 2, 3, emptyList(), "a", repeatMode = 1).map { it.id })
    }
    @Test fun `background remembering is not listening history and downloads can be unpinned`() {
        val dir = Files.createTempDirectory("offline-history").toFile()
        try {
            val index = OfflineIndex(dir)
            index.remember("key", "account", Song("song", "Title", "Artist"), pinned = true)
            assertNull(index.entries().single().playedAt)
            index.markPlayed("key")
            index.unpin("key")
            val restored = OfflineIndex(dir).entries().single()
            assertNotNull(restored.playedAt)
            assertFalse(restored.pinned)
        } finally { dir.deleteRecursively() }
    }
    @Test fun `invalid storage settings do not replace durable preferences`() {
        val dir = Files.createTempDirectory("offline-settings").toFile()
        try {
            val index = OfflineIndex(dir)
            try { index.settings = OfflineSettings(limitBytes = 0); fail("Accepted invalid limit") } catch (_: IllegalArgumentException) { }
            assertEquals(2_000_000_000L, OfflineIndex(dir).settings.limitBytes)
        } finally { dir.deleteRecursively() }
    }
    @Test fun `metadata and settings survive restart without marking partials complete`() {
        val dir = Files.createTempDirectory("offline-index").toFile()
        try {
            val index = OfflineIndex(dir)
            index.remember("key", "account", Song("song", "Title", "Artist"), pinned = true)
            index.settings = OfflineSettings(limitBytes = 500_000_000, offline = true)
            val restored = OfflineIndex(dir)
            assertEquals("Title", restored.entries().single().song.title)
            assertTrue(restored.entries().single().pinned)
            assertTrue(restored.settings.offline)
            assertEquals(500_000_000L, restored.settings.limitBytes)
        } finally { dir.deleteRecursively() }
    }
}
