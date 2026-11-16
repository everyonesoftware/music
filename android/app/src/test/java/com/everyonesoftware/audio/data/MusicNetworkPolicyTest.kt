package com.everyonesoftware.audio.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class MusicNetworkPolicyTest {
    @Test fun `Navidrome browse and cover bytes honor the shared offline gate`() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.start()
            val policy = MusicNetworkPolicy().apply { offline = true }
            val credentials = Credentials(Connection("test", "Test", server.url("/").toString(), "alice"), "secret")
            val client = NavidromeClient(credentials, allowLocalHttp = true, networkPolicy = policy)
            try { client.playlists(); fail("Browse contacted server offline") } catch (_: IOException) { }
            try { client.coverArt("cover"); fail("Artwork contacted server offline") } catch (_: IOException) { }
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun `offline mode rejects new API and artwork requests before the server`() {
        MockWebServer().use { server ->
            server.start()
            val policy = MusicNetworkPolicy()
            val http = policy.guard(OkHttpClient())
            policy.offline = true
            for (path in listOf("/rest/getPlaylist.view", "/rest/getCoverArt.view", "/rest/stream.view")) {
                try { http.newCall(Request.Builder().url(server.url(path)).build()).execute(); fail("Network allowed offline") }
                catch (_: IOException) { }
            }
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun `enabling offline cancels in flight body reads and can recover online`() {
        MockWebServer().use { server ->
            server.start()
            val policy = MusicNetworkPolicy()
            val http = policy.guard(OkHttpClient())
            server.enqueue(MockResponse().setBody("delayed").setBodyDelay(2, TimeUnit.SECONDS))
            val call = http.newCall(Request.Builder().url(server.url("/stream")).build())
            val response = call.execute()
            policy.offline = true
            assertTrue(call.isCanceled())
            try { response.body!!.bytes(); fail("Continued body read offline") } catch (_: IOException) { } finally { response.close() }
            policy.offline = false
            server.enqueue(MockResponse().setBody("online"))
            http.newCall(Request.Builder().url(server.url("/stream")).build()).execute().use { assertEquals("online", it.body!!.string()) }
        }
    }
}
