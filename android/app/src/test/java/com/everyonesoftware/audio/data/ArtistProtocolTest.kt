package com.everyonesoftware.audio.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test

class ArtistProtocolTest {
    @Test fun explicitNullCountsAreInvalidRatherThanOmitted() = runBlocking {
        for (body in listOf(
            "\"artist\":{\"id\":\"a\",\"name\":\"Artist\",\"albumCount\":null}",
            "\"artist\":{\"id\":\"a\",\"name\":\"Artist\",\"album\":[{\"id\":\"b\",\"name\":\"B\",\"songCount\":null}]}"
        )) MockWebServer().use { server ->
            server.enqueue(ok(body))
            server.enqueue(ok("\"album\":{\"id\":\"b\",\"name\":\"B\"}"))
            assertTrue(runCatching { client(server).artist("a") }.exceptionOrNull() is NavidromeException)
        }
        MockWebServer().use { server ->
            server.enqueue(ok("\"album\":{\"id\":\"b\",\"name\":\"B\",\"songCount\":null}"))
            assertTrue(runCatching { client(server).album("b") }.exceptionOrNull() is NavidromeException)
        }
    }

    @Test fun explicitArtistAlbumCountMustMatchTheCompleteAlbumArray() = runBlocking {
        for (fields in listOf(
            "\"albumCount\":2,\"album\":[{\"id\":\"b\",\"name\":\"B\"}]",
            "\"albumCount\":1",
            "\"albumCount\":0,\"album\":[{\"id\":\"b\",\"name\":\"B\"}]",
            "\"albumCount\":-1"
        )) MockWebServer().use { server ->
            server.enqueue(ok("\"artist\":{\"id\":\"a\",\"name\":\"Artist\",$fields}"))
            server.enqueue(ok("\"album\":{\"id\":\"b\",\"name\":\"B\",\"song\":[]}"))
            val failure = runCatching { client(server).artist("a") }.exceptionOrNull()
            assertTrue("Explicit album count must reject $fields atomically", failure is NavidromeException)
            assertEquals("The music server returned an invalid response. Check compatibility and retry.", failure?.message)
            assertEquals("Do not resolve albums from an incomplete artist", 1, server.requestCount)
        }
    }

    @Test fun artistAlbumSummaryCountMustMatchTracksWhenDetailCountIsOmitted() = runBlocking {
        for (count in listOf(2, 0, -1)) MockWebServer().use { server ->
            server.enqueue(ok("\"artist\":{\"id\":\"a\",\"name\":\"Artist\",\"albumCount\":2,\"album\":[{\"id\":\"b\",\"name\":\"B\",\"songCount\":1},{\"id\":\"c\",\"name\":\"C\",\"songCount\":$count}]}"))
            server.enqueue(ok("\"album\":{\"id\":\"b\",\"name\":\"B\",\"song\":[{\"id\":\"one\",\"title\":\"One\"}]}"))
            server.enqueue(ok("\"album\":{\"id\":\"c\",\"name\":\"C\",\"song\":[{\"id\":\"two\",\"title\":\"Two\"}]}"))
            val failure = runCatching { client(server).artist("a") }.exceptionOrNull()
            assertTrue("A later truncated album must reject the whole artist for declared $count", failure is NavidromeException)
            assertEquals("The music server returned an invalid response. Check compatibility and retry.", failure?.message)
        }
    }

    @Test fun matchingExplicitAndOmittedCountsRemainCompatible() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok("\"artist\":{\"id\":\"a\",\"name\":\"Artist\",\"albumCount\":0}"))
            assertTrue(client(server).artist("a").songs.isEmpty())
            server.enqueue(ok("\"artist\":{\"id\":\"a\",\"name\":\"Artist\",\"albumCount\":1,\"album\":[{\"id\":\"b\",\"name\":\"B\",\"songCount\":0}]}"))
            server.enqueue(ok("\"album\":{\"id\":\"b\",\"name\":\"B\"}"))
            assertEquals(1, client(server).artist("a").albums.size)
        }
    }

    @Test fun laterAlbumFailureRejectsTheWholeAggregate() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(""""artist":{"id":"a","name":"Artist","album":[{"id":"b","name":"B"},{"id":"c","name":"C"}]}"""))
            server.enqueue(ok(""""album":{"id":"b","name":"B","song":[{"id":"one","title":"One"}]}"""))
            server.enqueue(MockResponse().setResponseCode(503))
            assertTrue(runCatching { client(server).artist("a") }.exceptionOrNull() is NavidromeException)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun rejectsWrongArtistOrAlbumIdentityAndDuplicateAlbumSummaries() = runBlocking {
        for (body in listOf(
            """"artist":{"id":"wrong","name":"Artist","album":[]}""",
            """"artist":{"id":"a","name":"Artist","album":[{"id":"b","name":"B"},{"id":"b","name":"Again"}]}""",
            """"artist":{"id":"a","name":"Artist","album":[{"id":"b","name":"B"}]}"""
        )) MockWebServer().use { server ->
            server.enqueue(ok(body))
            server.enqueue(ok(""""album":{"id":"wrong","name":"Wrong","song":[]}"""))
            assertTrue(runCatching { client(server).artist("a") }.exceptionOrNull() is NavidromeException)
        }
    }

    @Test fun emptyArtistsAndEmptyArtistAreSuccessfulEmptyResults() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(""""artists":{}"""))
            server.enqueue(ok(""""artist":{"id":"a","name":"Artist"}"""))
            val client = client(server)
            assertTrue(client.artists().isEmpty())
            val contents = client.artist("a")
            assertTrue(contents.albums.isEmpty())
            assertTrue(contents.songs.isEmpty())
        }
    }

    @Test fun malformedOrDuplicateId3IndexFailsRatherThanHidingArtists() = runBlocking {
        for (body in listOf(""""artists":{"index":{}}""", """"artists":{"index":[{"artist":[{"name":"No id"}]}]}""",
            """"artists":{"index":[{"artist":[{"id":"a","name":"A"},{"id":"a","name":"Again"}]}]}""")) MockWebServer().use { server ->
            server.enqueue(ok(body))
            assertTrue(runCatching { client(server).artists() }.exceptionOrNull() is NavidromeException)
        }
    }

    @Test fun artistReadUsesExistingBoundedSafeTransportRecovery() = runBlocking {
        MockWebServer().use { server ->
            val attempts = java.util.concurrent.atomic.AtomicInteger()
            val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                if (attempts.incrementAndGet() == 1) throw java.io.IOException("Fixture transport")
                chain.proceed(chain.request())
            }.build()
            server.enqueue(ok(""""artist":{"id":"a","name":"Artist"}"""))
            val client = NavidromeClient(Credentials(Connection("a", "Fixture", server.url("/").toString(), "u"), "secret"), http, allowLocalHttp = true)
            assertEquals("Artist", client.artist("a").artist.name)
            assertEquals(2, attempts.get())
        }
    }
    @Test fun rejectsIncompleteAlbumInsteadOfReturningAPartialArtistQueue() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(""""artist":{"id":"a","name":"Artist","album":[{"id":"b","name":"Album"}]}"""))
            server.enqueue(ok(""""album":{"id":"b","name":"Album","songCount":2,"song":[{"id":"one","title":"Only one"}]}"""))
            val failure = runCatching { client(server).artist("a") }.exceptionOrNull()
            assertTrue("A truncated full album must fail the whole aggregate", failure is NavidromeException)
        }
    }
    @Test fun aggregatesFullAlbumsPreservingDuplicateOccurrences() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(""""artist":{"id":"a","name":"Alpha","albumCount":2,"album":[{"id":"second","name":"Second","songCount":3},{"id":"first","name":"First"}]}"""))
            server.enqueue(ok(""""album":{"id":"second","name":"Second","song":[{"id":"s2","title":"Two"},{"id":"s1","title":"One"},{"id":"s2","title":"Two"}]}"""))
            server.enqueue(ok(""""album":{"id":"first","name":"First","song":[{"id":"s3","title":"Three"}]}"""))
            val result = client(server).artist("a")
            assertEquals("Alpha", result.artist.name)
            assertEquals(listOf("second", "first"), result.albums.map { it.album.id })
            assertEquals(listOf("s2", "s1", "s2", "s3"), result.songs.map { it.id })
            assertEquals(listOf(3, 1), result.albums.map { it.album.songCount })
            assertEquals(listOf("getArtist.view", "getAlbum.view", "getAlbum.view"), List(3) { server.takeRequest().requestUrl!!.encodedPath.substringAfterLast('/') })
        }
    }
    private fun client(server: MockWebServer) = NavidromeClient(Credentials(Connection("account", "Fixture", server.url("/music/").toString(), "user"), "secret"), allowLocalHttp = true)
    private fun ok(fields: String) = MockResponse().setBody("""{"subsonic-response":{"status":"ok",$fields}}""")

    @Test fun readsEveryId3ArtistIndexInServerOrder() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(""""artists":{"index":[{"name":"B","artist":[{"id":"b","name":"Beta","coverArt":"portrait","albumCount":2}]},{"name":"A","artist":[{"id":"a","name":"Alpha"}]}]}"""))
            assertEquals(listOf(Artist("b", "Beta", "portrait", 2), Artist("a", "Alpha")), client(server).artists())
            val url = server.takeRequest().requestUrl!!
            assertEquals("/music/rest/getArtists.view", url.encodedPath)
            assertNull("ID3 catalog must not redefine selected-library preferences", url.queryParameter("musicFolderId"))
            assertNotNull(url.queryParameter("t"))
            assertNull(url.queryParameter("p"))
        }
    }
}
