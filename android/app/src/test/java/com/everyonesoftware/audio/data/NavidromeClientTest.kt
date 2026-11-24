package com.everyonesoftware.audio.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okio.Buffer
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class NavidromeClientTest {
    @Test fun contradictoryDeleteAcknowledgementAndReadbackDoNotConfirmDeletion() = runBlocking {
        MockWebServer().use { server ->
            val playlist = """"playlist":{"id":"target","name":"Target"}"""
            server.enqueue(ok(",$playlist"))
            server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"failed","error":{"code":70},$playlist}}"""))
            server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"failed","error":{"code":50},$playlist}}"""))
            server.enqueue(ok())
            val client = client(server)
            // Capture both interpretations before asserting so one regression cannot mask the other.
            val acknowledgement = runCatching { client.deletePlaylist("target") }
            val readback = runCatching { client.deletionTarget("target") }
            val denial = runCatching { client.deletePlaylist("target") }
            assertEquals(listOf(PlaylistDeleteUnconfirmed::class, NavidromeException::class, PlaylistDeleteUnconfirmed::class),
                listOf(acknowledgement, readback, denial).map { it.exceptionOrNull()?.let { error -> error::class } })
            assertEquals(listOf("POST", "GET", "POST"), List(3) { server.takeRequest(2, TimeUnit.SECONDS)?.method })
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun deleteUsesExactFormIdAndDoesNotReplayAnAmbiguousResponse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"failed","error":{"code":70}}}"""))
            val client = client(server)
            val id = "p +&/雪"
            rejects<PlaylistDeleteUnconfirmed> { client.deletePlaylist(id) }
            assertNull(client.deletionTarget(id))
            val post = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("POST", post.method)
            assertEquals("/music/rest/deletePlaylist.view", post.requestUrl!!.encodedPath)
            assertNull(post.requestUrl!!.queryParameter("id"))
            assertNotNull(post.requestUrl!!.queryParameter("t"))
            assertEquals("id=p%20%2B%26%2F%E9%9B%AA", post.body.readUtf8())
            val get = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("GET", get.method)
            assertEquals(id, get.requestUrl!!.queryParameter("id"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun deleteDoesNotFollowRedirectAndDistinguishesDenial() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/elsewhere")))
            server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"failed","error":{"code":50,"message":"private URL"}}}"""))
            val client = client(server)
            rejects<PlaylistDeleteUnconfirmed> { client.deletePlaylist("target") }
            val denial = rejects<NavidromeException> { client.deletePlaylist("target") }
            assertTrue(denial.writeDenied)
            assertFalse(denial.message!!.contains("private URL"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun malformedReadbackCannotConfirmDeletionOrEligibility() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(",\"playlist\":{\"id\":\"other\",\"name\":\"Target\"}"))
            server.enqueue(ok(""","playlist":{"id":"target","name":"Target","owner":"other","ow\u006eer":"a+b &雪"}"""))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"failed","error":{"code":50,"code":70}}}"""))
            server.enqueue(ok(""","playlist":{"id":"target","name":"Target","owner":"a+b &雪","readonly":"false"}"""))
            val client = client(server)
            repeat(4) { rejects<NavidromeException> { client.deletionTarget("target") } }
            assertFalse(client.deletionTarget("target")!!.canDelete)
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun playlistDeletionOffersOnlyExplicitOwnedWritablePlaylistsIncludingEmpty() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(",\"playlists\":{\"playlist\":[" +
                """{"id":"mine","name":"Mine","owner":"A+B &雪","songCount":0},{"id":"shared","name":"Shared","owner":"another"},{"id":"smart","name":"Smart","owner":"A+B &雪","readOnly":true},{"id":"unknown","name":"Legacy"}]}"""))
            assertEquals(listOf("mine"), client(server).playlists().filter { it.canDelete }.map { it.id })
        }
    }

    @Test fun playlistWritesUseOneOrderedFormPostAndReturnTheCreatedIdentity() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(",\"playlist\":{\"id\":\"empty-id\",\"name\":\"Empty & 雪\",\"songCount\":0}"))
            server.enqueue(ok(",\"playlist\":{\"id\":\"seeded-id\",\"name\":\"Seeded\",\"songCount\":3}"))
            server.enqueue(ok())
            val client = client(server)
            assertEquals("empty-id", client.createPlaylist("  Empty & 雪  ", emptyList()).id)
            assertEquals("seeded-id", client.createPlaylist("Seeded", listOf("1", "2+&", "1")).id)
            client.appendToPlaylist("target", listOf("1", "2+&", "1"))
            fun fields(method: String, expected: String) {
                val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("POST", request.method)
                assertEquals("/music/rest/$method.view", request.requestUrl!!.encodedPath)
                assertNotNull(request.requestUrl!!.queryParameter("t"))
                assertEquals(expected, request.body.readUtf8())
            }
            fields("createPlaylist", "name=Empty%20%26%20%E9%9B%AA")
            fields("createPlaylist", "name=Seeded&songId=1&songId=2%2B%26&songId=1")
            fields("updatePlaylist", "playlistId=target&songIdToAdd=1&songIdToAdd=2%2B%26&songIdToAdd=1")
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun playlistWriteWithLostAcknowledgementIsNotReplayed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(ok())
            val error = rejects<PlaylistWriteUnconfirmed> { client(server).appendToPlaylist("target", listOf("song")) }
            assertTrue(error.message!!.contains("avoid duplicates"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun playlistWritesFailClosedOnMissingIdentityAndIncompleteContents() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(",\"playlist\":{\"name\":\"No identity\"}"))
            server.enqueue(ok(",\"playlist\":{\"id\":\"target\",\"name\":\"Incomplete\",\"songCount\":1}"))
            val client = client(server)
            rejects<PlaylistWriteUnconfirmed> { client.createPlaylist("New", emptyList()) }
            rejects<NavidromeException> { client.verifiedPlaylist("target") }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun playlistDestinationsExcludeOtherOwnersAndReadOnlyEntries() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(",\"playlists\":{\"playlist\":[" +
                """{"id":"mine","name":"Mine","owner":"A+B &雪"},{"id":"shared","name":"Shared","owner":"another"},{"id":"smart","name":"Smart","owner":"A+B &雪","readonly":true},{"id":"readonly-alias","name":"Read only","owner":"A+B &雪","readonly":false,"readOnly":true},{"id":"old","name":"Legacy"}]}"""))
            assertEquals(listOf("mine", "old"), client(server).writablePlaylists().map { it.id })
        }
    }

    @Test fun playlistOwnerMatchingDoesNotFoldDistinctUnicodeUsernames() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(",\"playlists\":{\"playlist\":[{\"id\":\"other\",\"name\":\"Other\",\"owner\":\"K\"}]}"))
            val client = NavidromeClient(Credentials(Connection("id", "Music", server.url("/").toString(), "K"), "password"),
                OkHttpClient(), allowLocalHttp = true)
            assertTrue(client.writablePlaylists().isEmpty())
        }
    }

    private fun credentials(url: String) = Credentials(Connection("connection", "Music", url, "a+b &雪"), "secret &雪")
    private fun client(server: MockWebServer) = NavidromeClient(credentials(server.url("/music/").toString()), OkHttpClient(), allowLocalHttp = true)
    private fun ok(fields: String = "") = MockResponse().setBody("""{"subsonic-response":{"status":"ok","version":"1.16.1"$fields}}""")
    private inline fun <reified T : Throwable> rejects(block: () -> Unit): T {
        try { block() } catch (error: Throwable) { assertTrue(error.javaClass.name, error is T); return error as T }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    @Test fun playlistReadRecoversOnceFromAnInterruptedConnectionWithFreshAuthentication() = runBlocking {
        MockWebServer().use { server ->
            val attempts = java.util.concurrent.atomic.AtomicInteger()
            val salts = java.util.concurrent.CopyOnWriteArrayList<String>()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                salts.add(chain.request().url.queryParameter("s")!!)
                if (attempts.incrementAndGet() == 1) throw IOException("Fixture interrupted connection")
                chain.proceed(chain.request())
            }.build()
            server.enqueue(ok(",\"playlist\":{\"entry\":[{\"id\":\"song\",\"title\":\"Track\"}]}"))
            val client = NavidromeClient(credentials(server.url("/").toString()), http, allowLocalHttp = true)
            assertEquals("song", client.playlist("playlist").single().id)
            assertEquals(2, attempts.get())
            assertNotEquals(salts[0], salts[1])
        }
    }

    @Test fun artworkReadRecoversOnceFromAnInterruptedConnection() = runBlocking {
        MockWebServer().use { server ->
            val attempts = java.util.concurrent.atomic.AtomicInteger()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                if (attempts.incrementAndGet() == 1) throw IOException("Fixture interrupted connection")
                chain.proceed(chain.request())
            }.build()
            server.enqueue(MockResponse().setBody("fixture bytes"))
            val client = NavidromeClient(credentials(server.url("/").toString()), http, allowLocalHttp = true)
            assertArrayEquals("fixture bytes".toByteArray(), client.coverArt("cover"))
            assertEquals(2, attempts.get())
        }
    }

    @Test fun readRecoveryIsBoundedAndDoesNotReplayRatingsOrAuthenticationFailures() = runBlocking {
        MockWebServer().use { server ->
            val attempts = java.util.concurrent.atomic.AtomicInteger()
            val http = OkHttpClient.Builder().addInterceptor {
                attempts.incrementAndGet()
                throw IOException("Fixture interrupted connection")
            }.build()
            val client = NavidromeClient(credentials(server.url("/").toString()), http, allowLocalHttp = true)
            rejects<NavidromeException> { runBlocking { client.playlist("playlist") } }
            assertEquals("Only one retry is allowed", 2, attempts.get())
            attempts.set(0)
            rejects<NavidromeException> { runBlocking { client.rateSong("song", 3) } }
            assertEquals("A mutation must not be automatically replayed", 1, attempts.get())
            server.enqueue(MockResponse().setResponseCode(401))
            rejects<NavidromeException> { runBlocking { client(server).playlist("playlist") } }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun interruptedResponseBodiesRecoverOnceForMetadataAndArtwork() = runBlocking {
        for (artwork in listOf(false, true)) MockWebServer().use { server ->
            val content = if (artwork) "fixture artwork bytes" else
                """{"subsonic-response":{"status":"ok","playlist":{"entry":[]}}}"""
            server.enqueue(MockResponse().setBody(content)
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            server.enqueue(MockResponse().setBody(content))
            val failures = java.util.concurrent.CopyOnWriteArrayList<String>()
            val http = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) { failures.add(ioe.javaClass.simpleName) }
            }).build()
            val client = NavidromeClient(credentials(server.url("/music/").toString()), http, allowLocalHttp = true)
            try {
                if (artwork) assertArrayEquals(content.toByteArray(), client.coverArt("cover"))
                else assertTrue(client.playlist("playlist").isEmpty())
            } catch (error: NavidromeException) {
                throw AssertionError("artwork=$artwork requests=${server.requestCount} failures=$failures", error)
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun cancellationDuringReadRecoveryDoesNotSendAnotherRequest() = runBlocking {
        MockWebServer().use { server ->
            val entered = CountDownLatch(1)
            val attempts = java.util.concurrent.atomic.AtomicInteger()
            val http = OkHttpClient.Builder().addInterceptor {
                attempts.incrementAndGet(); entered.countDown()
                throw IOException("Fixture interrupted connection")
            }.build()
            val client = NavidromeClient(credentials(server.url("/").toString()), http, allowLocalHttp = true)
            val reading = async { client.playlist("playlist") }
            assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
            reading.cancelAndJoin()
            assertEquals(1, attempts.get())
        }
    }

    @Test fun baseUrlPreservesReverseProxyPath() {
        assertEquals("https://example.com/navidrome/", NavidromeClient.validateBaseUrl("https://example.com/navidrome"))
    }
    @Test fun baseUrlRejectsUnsafeInputs() {
        listOf("http://example.com", "https://u:p@example.com", "https://example.com/?token=x", "https://example.com/#x", "file:///music").forEach {
            rejects<IllegalArgumentException> { NavidromeClient.validateBaseUrl(it) }
        }
        rejects<IllegalArgumentException> { NavidromeClient.validateBaseUrl("http://localhost:1234") }
        rejects<IllegalArgumentException> { NavidromeClient.validateBaseUrl("http://192.168.1.1", true) }
        assertEquals("http://127.0.0.1:1234/", NavidromeClient.validateBaseUrl("http://127.0.0.1:1234", true))
    }
    @Test fun pingUsesFreshEncodedTokenAuthentication() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok()); server.enqueue(ok())
            val client = client(server)
            client.ping(); client.ping()
            val requests = List(2) { server.takeRequest(2, TimeUnit.SECONDS)!!.requestUrl!! }
            requests.forEach { url ->
                assertEquals("/music/rest/ping.view", url.encodedPath)
                assertEquals("a+b &雪", url.queryParameter("u"))
                assertEquals("1.16.1", url.queryParameter("v"))
                assertEquals("json", url.queryParameter("f"))
                assertNull(url.queryParameter("p"))
                val salt = url.queryParameter("s")!!
                assertTrue(salt.length >= 16)
                val token = MessageDigest.getInstance("MD5").digest(("secret &雪" + salt).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(token, url.queryParameter("t"))
            }
            assertNotEquals(requests[0].queryParameter("s"), requests[1].queryParameter("s"))
        }
    }
    @Test fun parsesNumericLibraryIdsAndEmptyCollections() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(",\"musicFolders\":{\"musicFolder\":[{\"id\":42,\"name\":\"Main\"}]}"))
            server.enqueue(ok(",\"playlists\":{}"))
            val client = client(server)
            assertEquals(listOf(MusicLibrary("42", "Main")), client.libraries())
            assertTrue(client.playlists().isEmpty())
        }
    }
    @Test fun parsesPlaylistSongsAndRatings() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(",\"playlist\":{\"entry\":[{\"id\":\"s1\",\"title\":\"Track\",\"artist\":\"Artist\",\"album\":\"Album\",\"duration\":93,\"userRating\":4,\"coverArt\":\"art\"}]}"))
            assertEquals(listOf(Song("s1", "Track", "Artist", "Album", "art", 93, 4)), client(server).playlist("p &/雪"))
            assertEquals("p &/雪", server.takeRequest().requestUrl!!.queryParameter("id"))
        }
    }
    @Test fun searchSongsUsesServerQueryAndParsesSongMetadata() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(""", "searchResult3":{"song":[{"id":"remote","title":"Only on server","artist":"Artist","album":"Album","coverArt":"cover","duration":93,"userRating":4},{"id":"minimal","title":"Minimal"}]}"""))
            val songs = client(server).searchSongs("a+b &雪")
            assertEquals(listOf(Song("remote", "Only on server", "Artist", "Album", "cover", 93, 4), Song("minimal", "Minimal", "Unknown artist")), songs)
            val url = server.takeRequest().requestUrl!!
            assertEquals("/music/rest/search3.view", url.encodedPath)
            assertEquals("a+b &雪", url.queryParameter("query"))
            assertEquals("0", url.queryParameter("artistCount"))
            assertEquals("0", url.queryParameter("albumCount"))
            assertEquals("100", url.queryParameter("songCount"))
        }
    }

    @Test fun searchSongsSupportsEmptyResultsAndRejectsMalformedSongs() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(""", "searchResult3":{}"""))
            server.enqueue(ok(""", "searchResult3":{"song":[{"id":"missing-title"}]}"""))
            server.enqueue(MockResponse().setResponseCode(503))
            val client = client(server)
            assertTrue(client.searchSongs("empty").isEmpty())
            rejects<NavidromeException> { runBlocking { client.searchSongs("malformed") } }
            rejects<NavidromeException> { runBlocking { client.searchSongs("unavailable") } }
            Unit
        }
    }

    @Test fun albumsIncludesLaterPagesInServerOrder() = runBlocking {
        MockWebServer().use { server ->
            val expected = (1..201).map { index ->
                Album("album-$index", if (index == 100) "Moonlighter" else "Album $index", "Artist", null, 0, 0)
            }
            expected.chunked(100).forEach { page ->
                server.enqueue(ok(",\"albumList2\":{\"album\":[" + page.joinToString(",") {
                    """{"id":"${it.id}","name":"${it.name}","artist":"${it.artist}"}"""
                } + "]}"))
            }
            assertEquals(expected, client(server).albums())
            assertEquals(3, server.requestCount)
            listOf(0, 100, 200).forEach { offset ->
                val url = server.takeRequest(2, TimeUnit.SECONDS)!!.requestUrl!!
                assertEquals("/music/rest/getAlbumList2.view", url.encodedPath)
                assertEquals("alphabeticalByName", url.queryParameter("type"))
                assertEquals("100", url.queryParameter("size"))
                assertEquals(offset.toString(), url.queryParameter("offset"))
            }
        }
    }

    private fun albumPage(first: Int, count: Int) = ok(",\"albumList2\":{\"album\":[" +
        (first until first + count).joinToString(",") { """{"id":"album-$it","name":"Album $it"}""" } + "]}")

    @Test fun albumsStopsAfterEmptyPageForExactPageMultiple() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(albumPage(1, 100))
            server.enqueue(albumPage(101, 100))
            server.enqueue(albumPage(201, 0))
            assertEquals((1..200).map { "album-$it" }, client(server).albums().map { it.id })
            assertEquals(3, server.requestCount)
            listOf(0, 100, 200).forEach { offset ->
                assertEquals(offset.toString(), server.takeRequest(2, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("offset"))
            }
        }
    }

    @Test fun albumsSupportsEmptyLibrary() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(albumPage(1, 0))
            assertTrue(client(server).albums().isEmpty())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun albumsDoesNotReturnPartialLibraryWhenLaterPageFails() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(albumPage(1, 100))
            server.enqueue(MockResponse().setResponseCode(503))
            rejects<NavidromeException> { runBlocking { client(server).albums() } }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun albumsRejectsRepeatedPageInsteadOfLoopingOrDuplicatingCards() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(albumPage(1, 100))
            server.enqueue(albumPage(1, 100))
            server.enqueue(albumPage(201, 0))
            rejects<NavidromeException> { runBlocking { client(server).albums() } }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun parsesAlbumListAndAlbumTracks() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(",\"albumList2\":{\"album\":[{\"id\":\"a1\",\"name\":\"Album One\",\"artist\":\"Artist\",\"coverArt\":\"art-a1\",\"songCount\":2,\"year\":2020}]}"))
            server.enqueue(ok(",\"album\":{\"id\":\"a1\",\"name\":\"Album One\",\"artist\":\"Artist\",\"coverArt\":\"art-a1\",\"songCount\":2,\"year\":2020,\"song\":[{\"id\":\"s1\",\"title\":\"Track One\",\"artist\":\"Artist\",\"album\":\"Album One\",\"coverArt\":\"art-a1\",\"duration\":180},{\"id\":\"s2\",\"title\":\"Track Two\",\"artist\":\"Artist\",\"album\":\"Album One\",\"coverArt\":\"art-a1\",\"duration\":210}]}"))
            val client = client(server)
            assertEquals(listOf(Album("a1", "Album One", "Artist", "art-a1", 2, 2020)), client.albums())
            assertEquals(listOf(Song("s1", "Track One", "Artist", "Album One", "art-a1", 180), Song("s2", "Track Two", "Artist", "Album One", "art-a1", 210)), client.album("a1").songs)
            val listRequest = server.takeRequest().requestUrl!!
            assertEquals("/music/rest/getAlbumList2.view", listRequest.encodedPath)
            assertEquals("alphabeticalByName", listRequest.queryParameter("type"))
            assertEquals("100", listRequest.queryParameter("size"))
            assertEquals("0", listRequest.queryParameter("offset"))
            assertEquals("a1", server.takeRequest().requestUrl!!.queryParameter("id"))
        }
    }
    @Test fun ratingReturnsAuthoritativeReadbackNotRequestedValue() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok()); server.enqueue(ok(",\"song\":{\"id\":\"s\",\"title\":\"T\",\"artist\":\"A\",\"userRating\":3}"))
            assertEquals(3, client(server).rateSong("s", 5))
            val set = server.takeRequest().requestUrl!!
            assertEquals("/music/rest/setRating.view", set.encodedPath)
            assertEquals("5", set.queryParameter("rating"))
            assertEquals("/music/rest/getSong.view", server.takeRequest().requestUrl!!.encodedPath)
        }
    }
    @Test fun parsesOptionalPlaylistValidityWithoutTrustingSummaryCounts() = runBlocking {
        MockWebServer().use { server ->
            val deadline = "2026-11-12T20:30:00.123Z"
            server.enqueue(ok(",\"playlists\":{\"playlist\":[{\"id\":\"p\",\"name\":\"Rated\",\"songCount\":99,\"validUntil\":\"$deadline\"}]}"))
            server.enqueue(ok(",\"playlist\":{\"songCount\":99,\"validUntil\":\"$deadline\",\"entry\":[{\"id\":\"s\",\"title\":\"Track\"}]}"))
            server.enqueue(ok(",\"playlist\":{\"validUntil\":\"unsupported timestamp\"}"))
            val client = client(server)
            val expected = java.time.Instant.parse(deadline).toEpochMilli()
            assertEquals(expected, client.playlists().single().validUntilMs)
            val result = client.playlistContents("p")
            assertEquals(expected, result.validUntilMs)
            assertEquals(1, result.songs.size)
            assertNull(client.playlistContents("p").validUntilMs)
        }
    }

    @Test fun ratingCanBeRetriedAfterUncertainReadback() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok()); server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(ok()); server.enqueue(ok(",\"song\":{\"id\":\"s\",\"userRating\":5}"))
            val client = client(server)
            rejects<NavidromeException> { runBlocking { client.rateSong("s", 5) } }
            assertEquals(5, client.rateSong("s", 5))
        }
    }
    @Test fun mediaUrisHaveNoPersistedSecretsAndRejectOtherTargets() {
        val client = NavidromeClient(credentials("https://example.com/music/"))
        val unsigned = client.streamUri("s &/雪")
        val url = unsigned.toHttpUrl()
        assertEquals("/music/rest/stream.view", url.encodedPath)
        assertEquals("s &/雪", url.queryParameter("id"))
        assertEquals(setOf("id"), url.queryParameterNames)
        assertNotNull(client.authenticatedMediaUri(unsigned).toHttpUrl().queryParameter("t"))
        assertEquals("/music/rest/getCoverArt.view", client.coverArtUri("a").toHttpUrl().encodedPath)
        listOf("https://evil.example/music/rest/stream.view?id=s", "https://example.com/rest/stream.view?id=s", "https://example.com/music/rest/ping.view", "https://example.com/music/rest/stream.view?id=s&t=old", "https://u:p@example.com/music/rest/stream.view?id=s").forEach {
            rejects<IllegalArgumentException> { client.authenticatedMediaUri(it) }
        }
    }
    @Test fun apiErrorsAreTypedAndNeverLeakServerContent() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"failed","error":{"code":40,"message":"secret &雪 https://sensitive.example"}}}"""))
            val error = rejects<NavidromeException> { runBlocking { client(server).ping() } }
            assertFalse(error.toString().contains("secret")); assertFalse(error.toString().contains("sensitive"))
            assertTrue(error.message!!.isNotBlank())
        }
    }
    @Test fun rejectsEmptyEmbeddedCredentialsAndAmbiguousBasePaths() {
        listOf("https://@example.com/", "https://example.com/music/../", "https://example.com/music\\rest").forEach {
            rejects<IllegalArgumentException> { NavidromeClient.validateBaseUrl(it) }
        }
    }
    @Test fun rejectsRatingReadbackForDifferentSong() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok()); server.enqueue(ok(",\"song\":{\"id\":\"other\",\"userRating\":5}"))
            rejects<NavidromeException> { runBlocking { client(server).rateSong("s", 5) } }
            Unit
        }
    }
    @Test fun rejectsErrorCodeEvenIfServerIncorrectlyReportsOkStatus() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(",\"error\":{\"code\":40,\"message\":\"private\"}"))
            rejects<NavidromeException> { runBlocking { client(server).ping() } }
            Unit
        }
    }
    private fun assertSafeArtworkError(error: NavidromeException) {
        // Coroutine stacktrace recovery may copy a safe exception and attach its original.
        // It must never attach an underlying transport exception or its signed URL.
        var cause: Throwable? = error
        while (cause != null) {
            assertTrue(cause.javaClass.name, cause is NavidromeException)
            assertEquals(error.message, cause.message)
            assertFalse(cause.toString().contains("private-art"))
            assertFalse(cause.toString().contains("secret"))
            assertFalse(cause.toString().contains("http://"))
            cause = cause.cause
        }
    }

    @Test fun coverArtDownloadsBytesWithFreshEncodedAuthenticationAndNoStore() = runBlocking {
        MockWebServer().use { server ->
            val bytes = byteArrayOf(0, -1, 42, -128)
            repeat(2) { server.enqueue(MockResponse().setBody(Buffer().write(bytes))) }
            val client = client(server)
            repeat(2) { assertArrayEquals(bytes, client.coverArt("art &/雪")) }
            val requests = List(2) { server.takeRequest(2, TimeUnit.SECONDS)!! }
            requests.forEach { request ->
                val url = request.requestUrl!!
                assertEquals("/music/rest/getCoverArt.view", url.encodedPath)
                assertEquals("art &/雪", url.queryParameter("id"))
                assertEquals("1024", url.queryParameter("size"))
                assertEquals("a+b &雪", url.queryParameter("u"))
                assertEquals("1.16.1", url.queryParameter("v"))
                assertEquals("EveryoneMusic", url.queryParameter("c"))
                assertNull(url.queryParameter("p"))
                val salt = url.queryParameter("s")!!
                val token = MessageDigest.getInstance("MD5").digest(("secret &雪" + salt).toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(token, url.queryParameter("t"))
                assertEquals("no-store", request.getHeader("Cache-Control"))
            }
            assertNotEquals(requests[0].requestUrl!!.queryParameter("s"), requests[1].requestUrl!!.queryParameter("s"))
        }
    }
    @Test fun coverArtAcceptsExactlyTwoMiBWithKnownAndUnknownLength() = runBlocking {
        MockWebServer().use { server ->
            val bytes = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            server.enqueue(MockResponse().setChunkedBody(Buffer().write(bytes), 8192))
            val client = client(server)
            repeat(2) { assertArrayEquals(bytes, client.coverArt("art")) }
        }
    }
    @Test fun coverArtRejectsOversizedKnownAndUnknownLengthWithoutLeakingCauses() = runBlocking {
        MockWebServer().use { server ->
            val bytes = ByteArray(2 * 1024 * 1024 + 1)
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            server.enqueue(MockResponse().setChunkedBody(Buffer().write(bytes), 8192))
            val client = client(server)
            repeat(2) {
                val error = rejects<NavidromeException> { runBlocking { client.coverArt("private-art") } }
                assertSafeArtworkError(error)
                assertFalse(error.toString().contains("private-art"))
            }
        }
    }
    @Test fun coverArtRejectsDeclaredOversizeBeforeConsumingBody() = runBlocking {
        MockWebServer().use { server ->
            // A huge declared body must fail on headers, not wait for body bytes/read timeout.
            server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", 2 * 1024 * 1024 + 1)
                .setBodyDelay(2, TimeUnit.SECONDS))
            withTimeout(1000) {
                val error = rejects<NavidromeException> { client(server).coverArt("art") }
                assertSafeArtworkError(error)
            }
        }
    }
    @Test fun coverArtRejectsStatusesAndDoesNotFollowRedirects() = runBlocking {
        MockWebServer().use { server ->
            val client = client(server)
            listOf(302, 401, 403, 503).forEach { code ->
                server.enqueue(MockResponse().setResponseCode(code).addHeader("Location", server.url("/leak"))
                    .setBody("secret &雪 https://sensitive.example/?t=private-token"))
                val error = rejects<NavidromeException> { runBlocking { client.coverArt("art") } }
                assertSafeArtworkError(error)
                assertFalse(error.toString().contains("secret"))
                assertFalse(error.toString().contains("sensitive"))
                assertFalse(error.toString().contains("private-token"))
            }
            assertEquals(4, server.requestCount)
        }
    }
    @Test fun coverArtSanitizesTransportAndBodyReadFailures() = runBlocking {
        MockWebServer().use { server ->
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                throw IOException("private transport ${chain.request().url}")
            }.build()
            val client = NavidromeClient(credentials(server.url("/music/").toString()), http, allowLocalHttp = true)
            val transport = rejects<NavidromeException> { runBlocking { client.coverArt("private-art") } }
            assertSafeArtworkError(transport)
            assertFalse(transport.toString().contains("private"))
            assertFalse(transport.toString().contains("http"))

            server.enqueue(MockResponse().setBody("private body").setHeader("Content-Length", 100)
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END))
            server.enqueue(MockResponse().setBody("private body").setHeader("Content-Length", 100)
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END))
            val body = rejects<NavidromeException> { runBlocking { client(server).coverArt("private-art") } }
            assertSafeArtworkError(body)
            assertFalse(body.toString().contains("private"))
            assertFalse(body.toString().contains("http"))
        }
    }
    @Test fun coverArtCancellationCancelsCallDuringBodyConsumptionAndUsesTenSecondTimeout() = runBlocking {
        MockWebServer().use { server ->
            val reading = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val timeout = AtomicLong()
            val http = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callStart(call: Call) { timeout.set(call.timeout().timeoutNanos()) }
                override fun responseBodyStart(call: Call) { reading.countDown() }
                override fun canceled(call: Call) { cancelled.countDown() }
            }).build()
            server.enqueue(MockResponse().setBody("x".repeat(100)).throttleBody(1, 200, TimeUnit.MILLISECONDS))
            val client = NavidromeClient(credentials(server.url("/music/").toString()), http, allowLocalHttp = true)
            val download = async { client.coverArt("art") }
            try {
                assertTrue(withContext(Dispatchers.IO) { reading.await(2, TimeUnit.SECONDS) })
                withTimeout(1000) { download.cancelAndJoin() }
                assertTrue(download.isCancelled)
                assertTrue(cancelled.await(1, TimeUnit.SECONDS))
                assertEquals(TimeUnit.SECONDS.toNanos(10), timeout.get())
            } finally { download.cancelAndJoin() }
        }
    }
    @Test fun redirectsAreNotFollowed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/leak")))
            rejects<NavidromeException> { runBlocking { client(server).ping() } }
            assertEquals(1, server.requestCount)
        }
    }
}
