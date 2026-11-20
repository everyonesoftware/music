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
            val result = client(server).artist("a")
            assertFalse(result.complete)
            assertTrue(result.songs.isEmpty())
        }
        MockWebServer().use { server ->
            server.enqueue(ok("\"album\":{\"id\":\"b\",\"name\":\"B\",\"songCount\":null}"))
            assertTrue(runCatching { client(server).album("b") }.exceptionOrNull() is NavidromeException)
        }
    }

    @Test fun contradictoryArtistAlbumCountKeepsListedAlbumsButIsIncomplete() = runBlocking {
        for (fields in listOf(
            "\"albumCount\":2,\"album\":[{\"id\":\"b\",\"name\":\"B\"}]",
            "\"albumCount\":1",
            "\"albumCount\":0,\"album\":[{\"id\":\"b\",\"name\":\"B\"}]",
            "\"albumCount\":-1"
        )) MockWebServer().use { server ->
            server.enqueue(ok("\"artist\":{\"id\":\"a\",\"name\":\"Artist\",$fields}"))
            server.enqueue(ok("\"album\":{\"id\":\"b\",\"name\":\"B\",\"song\":[]}"))
            val result = client(server).artist("a")
            assertFalse("Contradictory album count: $fields", result.complete)
            val listed = fields.contains("\"album\":")
            assertEquals(if (listed) listOf("b") else emptyList<String>(), result.albums.map { it.album.id })
            assertEquals(if (listed) 2 else 1, server.requestCount)
        }
    }

    @Test fun artistAlbumSummaryCountMustMatchTracksWhenDetailCountIsOmitted() = runBlocking {
        for (count in listOf(2, 0, -1)) MockWebServer().use { server ->
            server.enqueue(ok("\"artist\":{\"id\":\"a\",\"name\":\"Artist\",\"albumCount\":2,\"album\":[{\"id\":\"b\",\"name\":\"B\",\"songCount\":1},{\"id\":\"c\",\"name\":\"C\",\"songCount\":$count}]}"))
            server.enqueue(ok("\"album\":{\"id\":\"b\",\"name\":\"B\",\"song\":[{\"id\":\"one\",\"title\":\"One\"}]}"))
            server.enqueue(ok("\"album\":{\"id\":\"c\",\"name\":\"C\",\"song\":[{\"id\":\"two\",\"title\":\"Two\"}]}"))
            val result = client(server).artist("a")
            assertFalse("Contradictory summary count: $count", result.complete)
            assertEquals(listOf("one"), result.songs.map { it.id })
            assertEquals("C", result.albums.last().album.name)
            assertNull(result.albums.last().songs)
        }
    }

    @Test fun verifiedAlbumRemainsPlayableWhenItsArtistSummaryDisagrees() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(""""artist":{"id":"a","name":"Artist","album":[{"id":"b","name":"B","songCount":2}]}"""))
            server.enqueue(ok(""""album":{"id":"b","name":"B","songCount":1,"song":[{"id":"one","title":"One"}]}"""))
            val result = client(server).artist("a")
            assertFalse(result.complete)
            assertEquals(listOf("one"), result.albums.single().songs?.map { it.id })
        }
    }

    @Test fun matchingExplicitAndOmittedCountsRemainCompatible() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok("\"artist\":{\"id\":\"a\",\"name\":\"Artist\",\"albumCount\":0}"))
            assertTrue(client(server).artist("a").songs.isEmpty())
            server.enqueue(ok("\"artist\":{\"id\":\"a\",\"name\":\"Artist\",\"albumCount\":1,\"album\":[{\"id\":\"b\",\"name\":\"B\",\"songCount\":0}]}"))
            server.enqueue(ok("\"album\":{\"id\":\"b\",\"name\":\"B\"}"))
            val result = client(server).artist("a")
            assertTrue(result.complete)
            assertEquals(emptyList<Song>(), result.albums.single().songs)
        }
    }

    @Test fun middleAlbumFailureKeepsCardsAndConfirmedOccurrencesInServerOrder() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(""""artist":{"id":"a","name":"Artist","album":[{"id":"b","name":"B"},{"id":"c","name":"C"},{"id":"d","name":"D"}]}"""))
            server.enqueue(ok(""""album":{"id":"b","name":"B","song":[{"id":"one","title":"One"},{"id":"one","title":"One"}]}"""))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(ok(""""album":{"id":"d","name":"D","song":[{"id":"two","title":"Two"}]}"""))
            val result = client(server).artist("a")
            assertEquals(listOf("b", "c", "d"), result.albums.map { it.album.id })
            assertFalse(result.complete)
            assertEquals("C", result.albums[1].album.name)
            assertNull(result.albums[1].songs)
            assertEquals(listOf("one", "one", "two"), result.songs.map { it.id })
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun wrongArtistIdentityOrRootRequestFailureIsStillAHardFailure() = runBlocking {
        for (response in listOf(ok(""""artist":{"id":"wrong","name":"Artist","album":[{"id":"b","name":"B"}]}"""),
            MockResponse().setResponseCode(503))) MockWebServer().use { server ->
            server.enqueue(response)
            assertTrue(runCatching { client(server).artist("a") }.exceptionOrNull() is NavidromeException)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun malformedRowsDuplicatesAndWrongAlbumIdentityDoNotHideHealthyAlbums() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(""""artist":{"id":"a","name":"Artist","albumCount":"invalid","album":[null,{"name":"Missing id"},{"id":"b","name":"B"},{"id":"b","name":"Repeated"},{"id":"c","name":"C"}]}"""))
            server.enqueue(ok(""""album":{"id":"wrong","name":"Wrong","song":[{"id":"bad","title":"Wrong"}]}"""))
            server.enqueue(ok(""""album":{"id":"c","name":"C","song":[{"id":"good","title":"Good"}]}"""))
            val result = client(server).artist("a")
            assertFalse(result.complete)
            assertEquals(listOf("b", "c"), result.albums.map { it.album.id })
            assertEquals("B", result.albums.first().album.name)
            assertNull(result.albums.first().songs)
            assertEquals(listOf("good"), result.songs.map { it.id })
            assertEquals(3, server.requestCount)
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

    @Test fun malformedOptionalAlbumSummaryMetadataDoesNotDropValidIdentity() = runBlocking {
        for (failed in listOf(false, true)) MockWebServer().use { server ->
            server.enqueue(ok(""""artist":{"id":"a","name":"Artist","albumCount":1,"album":[{"id":"b","name":"B","year":{"broken":true},"artist":17,"coverArt":true,"songCount":1}]}"""))
            server.enqueue(if (failed) MockResponse().setResponseCode(503) else
                ok(""""album":{"id":"b","name":"B","song":[{"id":"one","title":"One"}]}"""))
            val result = client(server).artist("a")
            val album = result.albums.single()
            assertEquals(Album("b", "B", songCount = if (failed) 0 else 1), album.album)
            assertEquals(!failed, result.complete)
            assertEquals(if (failed) null else listOf("one"), album.songs?.map { it.id })
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun malformedAlbumDetailYearStillFailsStrictParsing() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(""""album":{"id":"b","name":"B","year":{},"song":[]}"""))
            assertTrue(runCatching { client(server).album("b") }.exceptionOrNull() is NavidromeException)
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
    @Test fun truncatedAlbumIsVisibleWithoutItsUnconfirmedTracks() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(""""artist":{"id":"a","name":"Artist","album":[{"id":"b","name":"Album"}]}"""))
            server.enqueue(ok(""""album":{"id":"b","name":"Album","songCount":2,"song":[{"id":"one","title":"Only one"}]}"""))
            val result = client(server).artist("a")
            assertFalse(result.complete)
            assertNull(result.albums.single().songs)
            assertTrue(result.songs.isEmpty())
        }
    }
    @Test fun aggregatesFullAlbumsPreservingDuplicateOccurrences() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(""""artist":{"id":"a","name":"Alpha","albumCount":2,"album":[{"id":"second","name":"Second","songCount":3},{"id":"first","name":"First"}]}"""))
            server.enqueue(ok(""""album":{"id":"second","name":"Second","song":[{"id":"s2","title":"Two"},{"id":"s1","title":"One"},{"id":"s2","title":"Two"}]}"""))
            server.enqueue(ok(""""album":{"id":"first","name":"First","song":[{"id":"s3","title":"Three"}]}"""))
            val result = client(server).artist("a")
            assertEquals("Alpha", result.artist.name)
            assertTrue(result.complete)
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
