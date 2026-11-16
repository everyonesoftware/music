package com.everyonesoftware.audio.data

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class OfflineCollectionCacheTest {
    @Test fun sharedCollectionAndExplicitOwnersRetainBytesUntilLastRemovalAfterRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "collection-cache-${System.nanoTime()}")
        val credentials = Credentials(Connection("collection-test", "Test", "https://music.example", "alice"), "secret")
        val song = Song("one", "One", "Artist")
        var cache = SongCache(context, dir) { credentials }
        try {
            val source = cache.factory(credentials, song, DataSource.Factory { ByteArrayDataSource(ByteArray(4096)) }).createDataSource()
            source.open(DataSpec.Builder().setUri("https://music.example/song").build())
            val buffer = ByteArray(1024)
            while (source.read(buffer, 0, buffer.size) != -1) { }
            source.close()
            cache.setSettings(cache.state.value.settings.copy(offline = true))
            val album = OfflineCollection("album", "a", "Album", songs = listOf(song, song))
            val playlist = OfflineCollection("playlist", "p", "Playlist", songs = listOf(song))
            cache.downloadCollection(credentials, album)
            cache.downloadCollection(credentials, playlist)
            cache.pin(credentials, listOf(song))
            cache.close()
            cache = SongCache(context, dir) { credentials }
            assertEquals(listOf(song, song), cache.collection(credentials, "album", "a")!!.songs)
            cache.clearCache()
            assertTrue(cache.complete(credentials, song.id))
            cache.removeDownload(credentials, song.id)
            assertTrue("Individual removal broke collection pin", cache.complete(credentials, song.id))
            cache.removeCollection(credentials, "album", "a")
            assertTrue("Another collection still needs audio", cache.complete(credentials, song.id))
            cache.removeCollection(credentials, "playlist", "p")
            assertFalse("Last owner removal retained inactive audio", cache.complete(credentials, song.id))
            assertTrue(cache.collections(credentials).isEmpty())
        } finally { cache.close(); dir.deleteRecursively() }
    }

    @Test fun accountSwitchRejectsStaleCollectionWritesAndAccountRemovalPurgesSnapshots() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "collection-account-${System.nanoTime()}")
        val a = Credentials(Connection("a", "A", "https://music.example", "alice"), "secret")
        val b = a.copy(connection = a.connection.copy(id = "b", username = "bob"))
        var active: Credentials? = a
        val cache = SongCache(context, dir) { active }
        val collection = OfflineCollection("playlist", "p", "Playlist", songs = listOf(Song("s", "Song", "Artist")))
        try {
            cache.setSettings(cache.state.value.settings.copy(offline = true))
            cache.downloadCollection(a, collection)
            active = b
            cache.accountChanged()
            try { cache.downloadCollection(a, collection.copy(name = "Late")); fail("Stale write accepted") } catch (_: IllegalArgumentException) { }
            assertTrue(cache.collections(b).isEmpty())
            active = a
            cache.accountChanged()
            assertEquals(listOf(collection), cache.collections(a))
            cache.removeConnection("a")
            assertTrue(OfflineIndex(File(dir, "metadata")).collections(a.connection).isEmpty())
            try { cache.downloadCollection(a, collection); fail("Removed account resurrected") } catch (_: IllegalArgumentException) { }
        } finally { cache.close(); dir.deleteRecursively() }
    }
}
