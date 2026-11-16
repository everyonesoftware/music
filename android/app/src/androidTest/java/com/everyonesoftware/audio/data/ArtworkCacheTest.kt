package com.everyonesoftware.audio.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.everyonesoftware.audio.playback.SessionArtwork
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtworkCacheTest {
    private val credentials = Credentials(Connection("art-test", "Test", "https://music.example", "alice"), "test-secret")
    private fun image(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        return try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
        finally { bitmap.recycle() }
    }

    @Test fun reopenedArtworkWorksOfflineForBrowsingAndMediaSessionWithoutFetching() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "artwork-reopen-${System.nanoTime()}")
        val policy = MusicNetworkPolicy()
        val requests = AtomicInteger()
        var active: Credentials? = credentials
        var cache = ArtworkCache(context, policy, { active }, directory) { _, _ -> requests.incrementAndGet(); image() }
        try {
            assertNotNull(cache.load(credentials, "cover"))
            assertEquals(1, requests.get())
            cache.close()
            policy.offline = true
            cache = ArtworkCache(context, policy, { active }, directory) { _, _ -> error("Offline artwork fetched from network") }
            assertNotNull(cache.load(credentials, "cover"))
            val uri = Uri.parse(cache.cachedUri(credentials, "cover"))
            assertEquals("file", uri.scheme)
            assertTrue(File(uri.path!!).isFile)
            assertNotNull(cache.load(credentials, "cover"))
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val previous = android.os.StrictMode.getThreadPolicy()
                try {
                    android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyDeath().build())
                    repeat(10) { cache.prefetch(credentials, listOf("cover")); assertNotNull(cache.cachedUri(credentials, "cover")) }
                } finally { android.os.StrictMode.setThreadPolicy(previous) }
            }
            val session = SessionArtwork(NavidromeClient(credentials), credentials, cache)
            try { assertNotNull(session.load("cover")) } finally { session.close() }
            active = credentials.copy(connection = credentials.connection.copy(username = "bob"))
            assertNull(cache.cachedUri(credentials, "cover"))
            assertNull(cache.load(credentials, "cover"))
            assertEquals(1, requests.get())
        } finally { cache.close(); directory.deleteRecursively() }
    }

    @Test fun removedAccountRejectsLateArtworkAndDoesNotRecreateItsFiles() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "artwork-remove-${System.nanoTime()}")
        val policy = MusicNetworkPolicy()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val bytes = image()
        val cache = ArtworkCache(context, policy, { credentials }, directory) { _, _ ->
            withContext(NonCancellable) { entered.complete(Unit); finish.await(); bytes }
        }
        try {
            val load = async { runCatching { cache.load(credentials, "cover") }.getOrNull() }
            entered.await()
            cache.removeConnection(credentials.connection.id)
            finish.complete(Unit)
            assertNull(load.await())
            assertNull(cache.cachedUri(credentials, "cover"))
            assertFalse(directory.walkTopDown().any { it.extension == "image" })
        } finally { finish.complete(Unit); cache.close(); directory.deleteRecursively() }
    }

    @Test fun clearingAutomaticCacheDoesNotCancelAnExplicitArtworkDownload() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "artwork-clear-${System.nanoTime()}")
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val cache = ArtworkCache(context, MusicNetworkPolicy(), { credentials }, directory) { _, _ ->
            entered.complete(Unit); finish.await(); image()
        }
        try {
            cache.prefetch(credentials, listOf("cover"), "song:pinned")
            entered.await()
            cache.clearAutomatic()
            finish.complete(Unit)
            assertNotNull(cache.load(credentials, "cover"))
            cache.clearAutomatic()
            assertNotNull(cache.load(credentials, "cover"))
        } finally { finish.complete(Unit); cache.close(); directory.deleteRecursively() }
    }

    @Test fun mediaArtworkRetryIsNotSuppressedByBrowsingFailureCooldown() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "artwork-retry-${System.nanoTime()}")
        val requests = AtomicInteger()
        val cache = ArtworkCache(context, MusicNetworkPolicy(), { credentials }, directory) { _, _ ->
            if (requests.incrementAndGet() == 1) throw java.io.IOException("Transient fixture failure")
            image()
        }
        try {
            assertNull(cache.load(credentials, "cover"))
            assertNotNull(cache.load(credentials, "cover", retry = true))
            assertEquals(2, requests.get())
        } finally { cache.close(); directory.deleteRecursively() }
    }

    @Test fun invalidImagesNeverBecomeOfflineArtwork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "artwork-invalid-${System.nanoTime()}")
        val cache = ArtworkCache(context, MusicNetworkPolicy(), { credentials }, directory) { _, _ -> "not an image".toByteArray() }
        try {
            assertNull(cache.load(credentials, "cover"))
            assertNull(cache.cachedUri(credentials, "cover"))
            assertNotNull(cache.error.value)
        } finally { cache.close(); directory.deleteRecursively() }
    }
}
