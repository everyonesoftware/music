package com.everyonesoftware.audio.playback

import com.everyonesoftware.audio.data.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CarLibraryTest {
    private class Source : CarLibrary.Source {
        override var credentials: Credentials? = Credentials(Connection("c", "Fixture", "https://example.invalid", "user"), "password")
        override var session: ListeningSession? = null
        override var offline = false
        var reads = 0
        var afterSongs: () -> Unit = {}
        var beforeSongs: suspend () -> Unit = {}
        var afterAlbums: () -> Unit = {}
        var complete = true
        val artworkRequests = mutableListOf<String?>()
        val artwork = BrowseArtworkRegistry("app.artwork") { credentials }
        val artworkUris = mutableListOf<String>()
        var listedPlaylists = listOf(Playlist("p", "Playlist", 3, "playlist-cover"))
        var listedAlbums = listOf(Album("a", "Album", "Artist", coverArtId = "album-cover", songCount = 3))
        var tracks = listOf(Song("one", "One", "Artist"), Song("two", "Two", "Artist"), Song("one", "One", "Artist"))
        override suspend fun bootstrap() = Unit
        override suspend fun playlists(credentials: Credentials, reconcileCounts: Boolean): List<Playlist> {
            reads++
            return listedPlaylists
        }
        override suspend fun albums(credentials: Credentials): List<Album> {
            afterAlbums()
            return listedAlbums
        }
        override suspend fun songs(credentials: Credentials, id: String, kind: String): List<Song> {
            reads++
            beforeSongs()
            afterSongs()
            return tracks
        }
        override suspend fun playbackSongs(credentials: Credentials, id: String, kind: String): List<Song> {
            if (offline) return OfflineCollection(kind, id, "Saved", songs = tracks).playableSongs { complete }
            return songs(credentials, id, kind)
        }
        override fun artworkUri(credentials: Credentials, coverArtId: String?): String? {
            artworkRequests.add(coverArtId)
            return artwork.register(credentials, coverArtId)?.also { artworkUris.add(it) }
        }
    }

    @Test fun catalogPaginationRegistersOnlyReturnedCovers() = runTest {
        for (parent in listOf(CarMediaIds.PLAYLISTS, CarMediaIds.ALBUMS)) {
            val source = Source().apply {
                listedPlaylists = (0 until 512).map { Playlist("p$it", "Playlist $it", 1, "cover-$it") }
                listedAlbums = (0 until 512).map { Album("a$it", "Album $it", "Artist", "cover-$it", 1) }
            }
            val library = CarLibrary(source)
            val first = library.children(parent, 0, 100)
            assertEquals(100, first.size)
            assertEquals((0 until 100).map { "cover-$it" }, source.artworkRequests)
            val firstUris = source.artworkUris.toList()
            source.artworkRequests.clear()
            assertEquals(12, library.children(parent, 5, 100).size)
            assertEquals((500 until 512).map { "cover-$it" }, source.artworkRequests)
            assertEquals(512, library.children(parent, 0, Int.MAX_VALUE).size)
            repeat(3) { library.children(parent, 0, 100) }
            firstUris.forEachIndexed { index, uri -> assertEquals("cover-$index", source.artwork.resolve(uri)?.coverArtId) }
            source.artworkRequests.clear()
            assertTrue(library.children(parent, Int.MAX_VALUE, Int.MAX_VALUE).isEmpty())
            assertTrue(source.artworkRequests.isEmpty())
        }
    }

    @Test fun queueSeparatesCollapsedPlayedCurrentAndUpcomingWithoutInferringPrefix() = runTest {
        val source = Source().apply { session = SessionRules.start("c", "p", "Playlist", tracks, shuffle = false).copy(index = 2) }
        val library = CarLibrary(source)
        val rows = library.children(CarMediaIds.QUEUE)
        assertEquals(listOf("Played", "One", "Up next"), rows.map { it.mediaMetadata.title })
        assertEquals(true, rows.first().mediaMetadata.isBrowsable)
        assertTrue(library.children(rows.first().mediaId).isEmpty())
        assertEquals("Now playing", rows[1].mediaMetadata.subtitle)
        assertTrue(library.children(rows.last().mediaId).isEmpty())
    }

    @Test fun playedBrowseUsesActualVisitsAndRealPerSongOpaqueArtwork() = runTest {
        val source = Source().apply {
            tracks = tracks.mapIndexed { index, song -> song.copy(coverArtId = "cover-$index") }
            session = SessionRules.start("c", "p", "Playlist", tracks, shuffle = false).copy(index = 1, played = listOf(2, 0, 2))
        }
        val library = CarLibrary(source)
        val folders = library.children(CarMediaIds.QUEUE)
        val played = library.children(folders.first().mediaId)
        assertEquals(3, played.map { it.mediaId }.distinct().size)
        assertEquals(listOf(2, 0, 2), played.map { CarMediaIds.parse(it.mediaId)!!.song!!.toInt() })
        assertEquals(listOf("cover-1", "cover-2", "cover-0", "cover-2"), source.artworkRequests)
        assertEquals(listOf("cover-1", "cover-2", "cover-0", "cover-2"), source.artworkUris.map { source.artwork.resolve(it)!!.coverArtId })
        val selected = library.resolve(played.first().mediaId).session
        assertEquals(source.session!!.queueId, selected.queueId)
        assertEquals(source.session!!.played, selected.played)
        source.session = source.session!!.copy(queueId = "replacement")
        try { library.children(folders.first().mediaId); fail("stale history folder") } catch (_: IllegalArgumentException) { }
    }

    @Test fun queuedOccurrenceIdSurvivesInSessionShuffleWithoutConfusingIdenticalSongs() = runTest {
        val source = Source().apply { session = SessionRules.start("c", "p", "Playlist", tracks, shuffle = false).copy(index = 2, played = listOf(0)) }
        val library = CarLibrary(source)
        val original = CarMediaIds.queued("c", source.session!!.queueId, 2)
        source.session = SessionRules.toggleShuffle(source.session!!)
        val selected = library.resolve(original).session
        assertEquals(0, selected.index)
        assertEquals(source.session!!.queueId, selected.queueId)
        assertEquals(2, selected.occurrences[selected.index])
        assertEquals(listOf(0), selected.played)
    }

    @Test fun searchCountDoesNotRegisterArtwork() = runTest {
        val source = Source().apply {
            listedPlaylists = (0 until 512).map { Playlist("p$it", "Match $it", 1, "cover-$it") }
        }
        assertEquals(512, CarLibrary(source).searchCount("Match"))
        assertTrue(source.artworkRequests.isEmpty())
        val library = CarLibrary(source)
        assertEquals(100, library.search(" match ", 0, 100).size)
        assertEquals((0 until 100).map { "cover-$it" }, source.artworkRequests)
        source.artworkRequests.clear()
        assertEquals(12, library.search("Match", 5, 100).size)
        assertEquals((500 until 512).map { "cover-$it" }, source.artworkRequests)
        source.artworkRequests.clear()
        assertTrue(library.search("Match", Int.MAX_VALUE, Int.MAX_VALUE).isEmpty())
        assertTrue(source.artworkRequests.isEmpty())
    }

    @Test fun collectionMetadataRequestsOnlyItsRealCoverWithoutLoadingSongs() = runTest {
        val source = Source()
        val library = CarLibrary(source)
        library.children(CarMediaIds.PLAYLISTS)
        library.children(CarMediaIds.ALBUMS)
        assertEquals(listOf("playlist-cover", "album-cover"), source.artworkRequests)
        assertEquals(1, source.reads)
        assertNull(source.session)
    }

    @Test fun playlistRowsOpenOrderedContentsWithoutBrowseSideEffects() = runTest {
        val source = Source().apply { session = SessionRules.start("c", "old", "Old", tracks) }
        val before = source.session
        val library = CarLibrary(source)
        val row = library.children(CarMediaIds.PLAYLISTS).single()
        assertEquals(true, row.mediaMetadata.isBrowsable)
        assertEquals(false, row.mediaMetadata.isPlayable)
        assertTrue(row.mediaMetadata.supportedCommands.isEmpty())
        val contents = library.children(row.mediaId)
        assertEquals(listOf("Play", "Shuffle play", "One", "Two", "One"), contents.map { it.mediaMetadata.title })
        assertTrue(contents.all { it.mediaMetadata.isPlayable == true && it.mediaMetadata.isBrowsable == false })
        assertEquals(5, contents.map { it.mediaId }.distinct().size)
        assertNotNull(contents[0].mediaMetadata.extras)
        assertNotNull(contents[1].mediaMetadata.extras)
        assertEquals(before, source.session)
    }

    @Test fun selectingPlaylistStartsFreshOrderedQueueIncludingDuplicatesWithoutCommittingIt() = runTest {
        val source = Source().apply { session = SessionRules.start("c", "old", "Old", tracks).copy(index = 1, positionMs = 1234, repeatMode = 2) }
        val before = source.session
        val library = CarLibrary(source)
        val id = CarMediaIds.playlist("c", "p")
        val first = library.resolve(id)
        val second = library.resolve(id)
        assertEquals(source.tracks, first.session.queue)
        assertFalse(first.session.shuffle)
        assertEquals(0, first.session.index)
        assertEquals(0L, first.session.positionMs)
        assertEquals("playlist", first.session.sourceType)
        assertNotEquals(before!!.queueId, first.session.queueId)
        assertNotEquals(first.session.queueId, second.session.queueId)
        assertEquals(before, first.previous)
        assertEquals(before, source.session)
    }

    @Test fun offlineShuffleStillShufflesTheWholeSnapshotAndCreatesFreshQueues() = runTest {
        val source = Source().apply { offline = true }
        val library = CarLibrary(source)
        val first = library.resolve(CarMediaIds.shuffle("c", "p")).session
        val second = library.resolve(CarMediaIds.shuffle("c", "p")).session
        assertTrue(first.shuffle)
        assertTrue(second.shuffle)
        assertEquals(source.tracks.groupingBy { it }.eachCount(), first.queue.groupingBy { it }.eachCount())
        assertEquals(3, first.queue.size)
        assertNotEquals(first.queueId, second.queueId)
        assertNull(source.session)
    }

    @Test fun partialOfflineContentsRemainBrowsableButEveryPlaybackEntryIsRejected() = runTest {
        val source = Source().apply { offline = true; complete = false; session = SessionRules.start("c", "old", "Old", tracks) }
        val before = source.session
        val library = CarLibrary(source)
        for (id in listOf(CarMediaIds.playlist("c", "p"), CarMediaIds.album("c", "a"))) {
            val rows = library.children(id)
            assertEquals(5, rows.size)
            for (row in rows) {
                try { library.resolve(row.mediaId); fail("started partial collection") } catch (_: IllegalArgumentException) { }
            }
        }
        try { library.resolveSearch("Playlist"); fail("voice started partial collection") } catch (_: IllegalArgumentException) { }
        assertEquals(before, source.session)
    }

    @Test fun albumContentsActionsAndDuplicateSelectionsStartCompleteFreshQueues() = runTest {
        val source = Source()
        val library = CarLibrary(source)
        val album = library.children(CarMediaIds.ALBUMS).single()
        assertEquals("Album", album.mediaMetadata.title)
        assertEquals(true, album.mediaMetadata.isBrowsable)
        assertEquals(false, album.mediaMetadata.isPlayable)
        assertTrue(album.mediaMetadata.supportedCommands.isEmpty())
        assertEquals("album", CarMediaIds.parse(album.mediaId)!!.kind)
        val contents = library.children(album.mediaId)
        assertEquals(listOf("Play", "Shuffle play", "One", "Two", "One"), contents.map { it.mediaMetadata.title })
        assertEquals(5, contents.map { it.mediaId }.distinct().size)
        for (row in contents) assertEquals(row, library.item(row.mediaId))
        val selected = library.resolve(contents.last().mediaId).session
        assertEquals(source.tracks, selected.queue)
        assertEquals(2, selected.index)
        assertEquals("album", selected.sourceType)
        val shuffled = library.resolve(contents[1].mediaId).session
        assertTrue(shuffled.shuffle)
        assertEquals(source.tracks.groupingBy { it }.eachCount(), shuffled.queue.groupingBy { it }.eachCount())
        val ordered = library.resolve(contents[0].mediaId).session
        assertFalse(ordered.shuffle)
        assertEquals(source.tracks, ordered.queue)
        assertNotEquals(shuffled.queueId, ordered.queueId)
        assertEquals(album, library.item(album.mediaId))
        val selection = library.resolve(album.mediaId).session
        assertEquals(source.tracks, selection.queue)
        assertEquals("album", selection.sourceType)
        assertEquals("a", selection.playlistId)
        assertFalse(selection.shuffle)
        assertNull(source.session)
    }

    @Test fun collectionCommandsStartFreshOrderedOrShuffledQueuesForBothKinds() = runTest {
        val source = Source().apply { offline = true }
        val library = CarLibrary(source)
        for ((id, kind) in listOf(CarMediaIds.playlist("c", "p") to "playlist", CarMediaIds.album("c", "a") to "album")) {
            val first = library.resolve(id, CarLibrary.SHUFFLE_COLLECTION).session
            val second = library.resolve(id, CarLibrary.SHUFFLE_COLLECTION).session
            val ordered = library.resolve(id, CarLibrary.PLAY_COLLECTION).session
            assertTrue(first.shuffle)
            assertTrue(second.shuffle)
            assertEquals(source.tracks.groupingBy { it }.eachCount(), first.queue.groupingBy { it }.eachCount())
            assertEquals(source.tracks, ordered.queue)
            assertFalse(ordered.shuffle)
            assertEquals(kind, ordered.sourceType)
            assertNotEquals(first.queueId, second.queueId)
            assertNotEquals(second.queueId, ordered.queueId)
        }
        val legacy = library.resolve(CarMediaIds.playlist("c", "p"), CarLibrary.SHUFFLE_PLAYLIST).session
        assertTrue(legacy.shuffle)
        assertNull(source.session)
    }

    @Test fun noCustomMediaItemCommandButtonsAreAdvertised() {
        assertTrue(CarLibrary.browseActions().isEmpty())
    }

    @Test fun legacyTrackAndShuffleIdsRemainLoadableWithoutReintroducingContents() = runTest {
        val source = Source()
        val library = CarLibrary(source)
        val trackId = CarMediaIds.track("c", "p", 2, "one")
        val shuffleId = CarMediaIds.shuffle("c", "p")
        assertEquals("One", library.item(trackId).mediaMetadata.title)
        assertEquals(shuffleId, library.item(shuffleId).mediaId)
        assertEquals(2, library.resolve(trackId).session.index)
        assertEquals(source.tracks, library.resolve(trackId).session.queue)
        assertFalse(library.resolve(trackId).session.shuffle)
        assertTrue(library.resolve(shuffleId).session.shuffle)
        assertEquals(5, library.children(CarMediaIds.playlist("c", "p")).size)
        assertNull(source.session)
    }

    @Test fun staleAccountsQueuesTracksAndInvalidCollectionCommandsAreRejectedWithoutReadsOrMutation() = runTest {
        val source = Source().apply { session = SessionRules.start("c", "p", "Playlist", tracks) }
        val library = CarLibrary(source)
        val before = source.session
        val other = CarMediaIds.playlist("other", "p")
        try { library.resolve(other, CarLibrary.PLAY_COLLECTION); fail("accepted another account") } catch (_: IllegalArgumentException) { }
        try { library.item(other); fail("exposed another account") } catch (_: IllegalArgumentException) { }
        try { library.children(other); fail("browsed another account") } catch (_: IllegalArgumentException) { }
        val stale = CarMediaIds.queued("c", "old-queue", 0)
        try { library.resolve(stale); fail("accepted stale queue") } catch (_: IllegalArgumentException) { }
        try { library.item(stale); fail("exposed stale queue") } catch (_: IllegalArgumentException) { }
        for ((id, command) in listOf(CarMediaIds.queued("c", before!!.queueId, 0) to CarLibrary.PLAY_COLLECTION,
            CarMediaIds.album("c", "a") to CarLibrary.SHUFFLE_PLAYLIST,
            CarMediaIds.playlist("c", "p") to "arbitrary.command")) {
            try { library.resolve(id, command); fail("accepted invalid command") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, source.reads)
        try { library.resolve(CarMediaIds.track("c", "p", 1, "one")); fail("accepted changed occurrence") } catch (_: IllegalArgumentException) { }
        assertEquals(before, source.session)
    }

    @Test fun accountChangesDuringCollectionReadsRejectLateSelectionsAndBrowseResults() = runTest {
        for (kind in listOf("playlist", "album")) {
            val source = Source().apply { session = SessionRules.start("c", "old", "Old", tracks) }
            val before = source.session
            source.afterSongs = { source.credentials = source.credentials!!.copy(password = "changed") }
            val id = if (kind == "album") CarMediaIds.album("c", "a") else CarMediaIds.playlist("c", "p")
            try { CarLibrary(source).resolve(id, CarLibrary.SHUFFLE_COLLECTION); fail("accepted late selection") } catch (_: IllegalArgumentException) { }
            assertEquals(before, source.session)
        }
        val source = Source()
        source.afterAlbums = { source.credentials = null }
        try { CarLibrary(source).children(CarMediaIds.ALBUMS); fail("returned old account rows") } catch (_: IllegalArgumentException) { }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun contentsLoadingEmptyAndErrorsNeverReplaceThePausedQueue() = runTest {
        val source = Source().apply { session = SessionRules.start("c", "old", "Old", tracks).copy(index = 1, positionMs = 1234) }
        val before = source.session
        val library = CarLibrary(source)
        val gate = CompletableDeferred<Unit>()
        source.beforeSongs = { gate.await() }
        val loading = async { library.children(CarMediaIds.album("c", "a")) }
        runCurrent()
        assertFalse(loading.isCompleted)
        assertEquals(before, source.session)
        gate.complete(Unit)
        assertEquals(5, loading.await().size)
        source.beforeSongs = { throw IllegalArgumentException("Collection unavailable") }
        try { library.children(CarMediaIds.album("c", "a")); fail("returned failed contents") } catch (_: IllegalArgumentException) { }
        try { library.resolve(CarMediaIds.albumPlay("c", "a")); fail("played failed contents") } catch (_: IllegalArgumentException) { }
        source.beforeSongs = {}
        source.tracks = emptyList()
        val actions = library.children(CarMediaIds.album("c", "a"))
        assertEquals(listOf("Play", "Shuffle play"), actions.map { it.mediaMetadata.title })
        for (action in actions) {
            try { library.resolve(action.mediaId); fail("played empty contents") } catch (_: IllegalArgumentException) { }
        }
        source.listedPlaylists = emptyList()
        try { library.children(CarMediaIds.playlist("c", "missing")); fail("returned missing contents") } catch (_: IllegalArgumentException) { }
        assertEquals(before, source.session)
    }

    @Test fun queueBrowsingAndOccurrenceSelectionPreserveQueueIdentityAndDuplicates() = runTest {
        val source = Source().apply { session = SessionRules.start("c", "p", "Playlist", tracks, shuffle = false).copy(positionMs = 4000, repeatMode = 2) }
        val before = source.session!!
        val library = CarLibrary(source)
        val rows = library.children(CarMediaIds.QUEUE)
        assertEquals(3, rows.map { it.mediaId }.distinct().size)
        assertEquals(listOf("Played", "One", "Up next"), rows.map { it.mediaMetadata.title })
        val upcoming = library.children(rows[2].mediaId)
        assertEquals(listOf("Two", "One"), upcoming.map { it.mediaMetadata.title })
        val selected = library.resolve(upcoming[1].mediaId).session
        assertEquals(before.copy(index = 2, positionMs = 0), selected)
        assertEquals(before, source.session)
        assertEquals(0, source.reads)
    }

    @Test fun voiceStillUsesUniquePlaylistNamesAndBlankQueryResumes() = runTest {
        val source = Source().apply { session = SessionRules.start("c", "p", "Playlist", tracks) }
        val library = CarLibrary(source)
        assertEquals(source.session, library.resolveSearch("").session)
        assertEquals("p", library.resolveSearch("Playlist").session.playlistId)
        source.listedPlaylists = listOf(Playlist("p", "Same"), Playlist("p2", "Same"))
        try { library.resolveSearch("Same"); fail("accepted ambiguous voice name") } catch (_: IllegalArgumentException) { }
        source.listedPlaylists = emptyList()
        try { library.resolve(CarMediaIds.playlist("c", "p"), CarLibrary.PLAY_COLLECTION); fail("accepted removed collection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun rootHasPlaylistsAlbumsQueueWithoutReadingAccountOrChangingPlayback() = runTest {
        val source = Source().apply { credentials = null }
        val library = CarLibrary(source)
        assertEquals(listOf("Playlists", "Albums", "Queue"), library.children(CarMediaIds.ROOT).map { it.mediaMetadata.title.toString() })
        assertEquals(0, source.reads)
        assertNull(source.session)
    }
}
