package com.everyonesoftware.audio.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** No cause: transport exceptions often contain authenticated URLs. */
class NavidromeException(message: String, internal val retryableTransport: Boolean = false,
    internal val writeDenied: Boolean = false, internal val itemNotFound: Boolean = false) : IOException(message)

internal class PlaylistWriteUnconfirmed : IOException(
    "The server didn’t confirm the change. Check your playlists before trying again to avoid duplicates.")
internal class PlaylistDeleteUnconfirmed : IOException("The server didn’t confirm the deletion.")

class NavidromeClient(
    private val credentials: Credentials,
    http: OkHttpClient = secureClient(),
    allowLocalHttp: Boolean = false,
    private val networkPolicy: MusicNetworkPolicy? = null,
) {
    private val base = validateBaseUrl(credentials.connection.baseUrl, allowLocalHttp).toHttpUrlOrNull()!!
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .cache(null).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).build().let { networkPolicy?.guard(it) ?: it }
    private val readHttp = this.http.newBuilder().retryOnConnectionFailure(true).build()
    private val artworkHttp = readHttp.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun ping() { api("ping") }
    suspend fun artists(): List<Artist> {
        val artists = api("getArtists").obj("artists").items("index").flatMap { index -> index.items("artist").map(::parseArtist) }
        if (artists.map { it.id }.distinct().size != artists.size) throw invalidResponse()
        return artists
    }
    private fun parseArtist(value: JsonObject, albumCount: Int = value.number("albumCount")) =
        Artist(value.required("id"), value.required("name"), value.text("coverArt"), albumCount)
    /** Retain valid album metadata and confirmed occurrences, but never certify a partial queue. */
    suspend fun artist(id: String): ArtistContents {
        val result = api("getArtist", "id" to id).obj("artist")
        val artist = parseArtist(result, albumCount = 0)
        if (artist.id != id) throw invalidResponse()
        val listed = result["album"]
        val summaries = listed as? JsonArray ?: JsonArray(emptyList())
        var complete = (listed == null || listed == JsonNull || listed is JsonArray) &&
            result.countMatches("albumCount", summaries.size)
        val seen = mutableSetOf<String>()
        val albums = buildList {
            for (entry in summaries) {
                currentCoroutineContext().ensureActive()
                val value = entry as? JsonObject
                val summary = value?.let {
                    try { parseArtistAlbumSummary(it) } catch (_: NavidromeException) { null }
                }
                if (value == null || summary == null || !seen.add(summary.id)) {
                    complete = false
                    continue
                }
                val detail = try {
                    val (album, countVerified) = readAlbum(summary.id)
                    if (!value.countMatches("songCount", album.songs.size)) {
                        complete = false
                        if (!countVerified) throw invalidResponse()
                    }
                    album
                } catch (_: NavidromeException) { null }
                if (detail == null) complete = false
                add(if (detail == null) ArtistAlbum(summary, null) else ArtistAlbum(detail.album, detail.songs))
            }
        }
        currentCoroutineContext().ensureActive()
        return ArtistContents(artist.copy(albumCount = albums.size), albums, complete)
    }
    suspend fun libraries(): List<MusicLibrary> = api("getMusicFolders").obj("musicFolders").items("musicFolder").map {
        MusicLibrary(it.required("id"), it.required("name"))
    }
    suspend fun playlists(): List<Playlist> = api("getPlaylists").obj("playlists").items("playlist").map {
        Playlist(it.required("id"), it.required("name"), it.number("songCount"), it.text("coverArt"), it.validUntil(), it.canDeletePlaylist())
    }
    suspend fun searchSongs(query: String): List<Song> = api("search3", "query" to query,
        "artistCount" to "0", "albumCount" to "0", "songCount" to "100")
        .obj("searchResult3").items("song").map {
            Song(it.required("id"), it.required("title"), it.text("artist") ?: "Unknown artist", it.text("album") ?: "",
                it.text("coverArt"), it.number("duration"), it.number("userRating"))
        }

    suspend fun albums(): List<Album> {
        val albums = mutableListOf<Album>()
        val seen = mutableSetOf<String>()
        val size = 100
        do {
            val page = api("getAlbumList2", "type" to "alphabeticalByName", "size" to size.toString(), "offset" to albums.size.toString())
                .obj("albumList2").items("album").map(::parseAlbum)
            // Fail rather than loop forever if a server ignores the offset or repeats albums.
            if (page.size > size || page.any { !seen.add(it.id) }) throw invalidResponse()
            albums.addAll(page)
        } while (page.size == size)
        return albums
    }
    suspend fun album(id: String): AlbumContents = readAlbum(id).first

    internal suspend fun verifiedAlbum(id: String): AlbumContents = readAlbum(id).let { (contents, counted) ->
        if (!counted) throw invalidResponse()
        contents
    }

    private suspend fun readAlbum(id: String): Pair<AlbumContents, Boolean> {
        val result = api("getAlbum", "id" to id).obj("album")
        val album = parseAlbum(result)
        if (album.id != id) throw invalidResponse()
        val songs = result.items("song").map {
            Song(it.required("id"), it.required("title"), it.text("artist") ?: album.artist, album.name,
                it.text("coverArt") ?: album.coverArtId, it.number("duration"), it.number("userRating"))
        }
        result.validateCount("songCount", songs.size)
        return AlbumContents(album.copy(songCount = songs.size), songs) to result.containsKey("songCount")
    }
    private fun JsonObject.validateCount(key: String, actual: Int) {
        if (!countMatches(key, actual)) throw invalidResponse()
    }
    private fun JsonObject.countMatches(key: String, actual: Int): Boolean {
        val value = this[key] ?: return true
        return (value as? JsonPrimitive)?.intOrNull == actual
    }
    private fun parseArtistAlbumSummary(value: JsonObject): Album {
        fun displayText(key: String): String? = (value[key] as? JsonPrimitive)
            ?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
        return Album(
            displayText("id") ?: throw invalidResponse(), displayText("name") ?: throw invalidResponse(),
            displayText("artist") ?: "Unknown artist", displayText("coverArt"),
            year = (value["year"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: 0,
        )
    }
    private fun parseAlbum(value: JsonObject): Album = Album(
        value.required("id"), value.required("name"), value.text("artist") ?: "Unknown artist",
        value.text("coverArt"), value.number("songCount"), value.number("year"))
    suspend fun playlist(id: String): List<Song> = playlistContents(id).songs
    internal suspend fun writablePlaylists(): List<Playlist> {
        val ids = hashSetOf<String>()
        return api("getPlaylists").obj("playlists").items("playlist").mapNotNull {
            val id = it.requiredString("id")
            if (!ids.add(id)) throw invalidResponse()
            if (it.playlistReadOnly() || !(it["owner"] == null || it.playlistOwnerMatches())) null
            else Playlist(id, it.requiredString("name"), it.number("songCount"))
        }
    }

    private fun JsonObject.playlistReadOnly() = listOfNotNull(this["readonly"], this["readOnly"]).any { it != JsonPrimitive(false) }
    private fun JsonObject.playlistOwnerMatches(): Boolean {
        val username = credentials.connection.username
        val owner = this["owner"] as? JsonPrimitive ?: return false
        // Navidrome authenticates usernames with SQLite NOCASE (ASCII only).
        return owner.isString && owner.content.length == username.length && username.indices.all { index ->
            asciiLowercase(owner.content[index]) == asciiLowercase(username[index])
        }
    }
    private fun JsonObject.canDeletePlaylist() = !playlistReadOnly() && playlistOwnerMatches() &&
        (this["id"] as? JsonPrimitive)?.isString == true && (this["name"] as? JsonPrimitive)?.isString == true

    private fun asciiLowercase(char: Char): Char = if (char in 'A'..'Z') char.lowercaseChar() else char

    internal suspend fun deletionTarget(id: String): Playlist? = try {
        val result = readWithRecovery { apiOnce("getPlaylist", arrayOf("id" to id), strict = true) }.obj("playlist")
        if (result.requiredString("id") != id) throw invalidResponse()
        Playlist(id, result.requiredString("name"), result.number("songCount"), result.text("coverArt"),
            result.validUntil(), result.canDeletePlaylist())
    } catch (error: NavidromeException) {
        if (error.itemNotFound) null else throw error
    }

    internal suspend fun deletePlaylist(id: String) {
        require(id.isNotBlank() && id.length <= 1024) { "Invalid playlist. Refresh playlists and retry." }
        try {
            val result = apiOnce("deletePlaylist", emptyArray(), oneShot(FormBody.Builder().add("id", id).build()), strict = true)
            if ("playlist" in result) throw invalidResponse()
        } catch (error: NavidromeException) {
            if (error.writeDenied) throw error
            throw PlaylistDeleteUnconfirmed()
        }
    }

    internal suspend fun createPlaylist(name: String, songIds: List<String>): Playlist {
        val title = name.trim()
        require(title.isNotBlank() && title.length <= MAX_PLAYLIST_NAME && title.none { it.isISOControl() }) { "Enter a valid playlist name." }
        return playlistWrite("createPlaylist", "name" to title, "songId", songIds) { root ->
            val playlist = root.obj("playlist")
            Playlist(playlist.requiredString("id"), playlist.requiredString("name"),
                playlist.number("songCount"), playlist.text("coverArt"), playlist.validUntil())
        }
    }

    internal suspend fun appendToPlaylist(id: String, songIds: List<String>) {
        require(id.isNotBlank() && songIds.isNotEmpty()) { "Choose a playlist and at least one song." }
        playlistWrite("updatePlaylist", "playlistId" to id, "songIdToAdd", songIds) { Unit }
    }

    private suspend fun <T> playlistWrite(method: String, field: Pair<String, String>, songField: String,
        songIds: List<String>, decode: (JsonObject) -> T): T {
        require(songIds.size <= 10_000 && songIds.all { it.isNotBlank() && it.length <= 1024 }) {
            "This selection is too large or contains invalid songs."
        }
        val form = FormBody.Builder().add(field.first, field.second).apply {
            songIds.forEach { add(songField, it) }
        }.build()
        require(form.contentLength() <= 1024L * 1024) { "This selection is too large to save at once." }
        return try { decode(apiOnce(method, emptyArray(), oneShot(form))) }
        catch (error: NavidromeException) {
            if (error.writeDenied) throw error
            throw PlaylistWriteUnconfirmed()
        }
    }
    // OkHttp can follow certain HTTP responses even with connection retries disabled.
    private fun oneShot(form: FormBody) = object : RequestBody() {
        override fun isOneShot() = true
        override fun contentType() = form.contentType()
        override fun contentLength() = form.contentLength()
        override fun writeTo(sink: okio.BufferedSink) = form.writeTo(sink)
    }

    suspend fun playlistContents(id: String): PlaylistContents {
        val result = api("getPlaylist", "id" to id).obj("playlist")
        return PlaylistContents(playlistSongs(result), result.validUntil())
    }

    internal suspend fun verifiedPlaylist(id: String): Pair<Playlist, List<Song>> {
        val result = api("getPlaylist", "id" to id).obj("playlist")
        if (result.requiredString("id") != id) throw invalidResponse()
        val songs = playlistSongs(result)
        if (result["songCount"] == null) throw invalidResponse()
        result.validateCount("songCount", songs.size)
        return Playlist(id, result.requiredString("name"), songs.size, result.text("coverArt"), result.validUntil()) to songs
    }

    private fun playlistSongs(result: JsonObject): List<Song> = result.items("entry").map {
        parseSong(it)
    }

    internal suspend fun verifiedSong(id: String): Song = api("getSong", "id" to id).obj("song").let {
        if (it.requiredString("id") != id) throw invalidResponse()
        parseSong(it)
    }

    private fun parseSong(value: JsonObject) = Song(value.requiredString("id"), value.requiredString("title"),
        value.text("artist") ?: "Unknown artist", value.text("album") ?: "", value.text("coverArt"),
        value.number("duration"), value.number("userRating"))
    /** Explicit retries are safe: setRating is idempotent; never return an unverified requested value. */
    suspend fun rateSong(id: String, rating: Int): Int {
        require(rating in 0..5) { "Rating must be between 0 and 5." }
        api("setRating", "id" to id, "rating" to rating.toString())
        val song = api("getSong", "id" to id).obj("song")
        if (song.required("id") != id) throw invalidResponse()
        return song.number("userRating").also {
            if (it !in 0..5) throw invalidResponse()
        }
    }
    fun streamUri(songId: String): String = endpoint("stream").addQueryParameter("id", songId).build().toString()

    /** Accept unsigned app media references, never arbitrary remote URLs. */
    fun authenticatedMediaUri(unsignedUri: String): String {
        val url = unsignedUri.toHttpUrlOrNull()
        require(url != null && url.scheme == base.scheme && url.host == base.host && url.port == base.port &&
            url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
            url.encodedPath in setOf(base.encodedPath + "rest/stream.view", base.encodedPath + "rest/getCoverArt.view") &&
            url.queryParameterNames == setOf("id") && url.queryParameterValues("id").size == 1 &&
            !url.queryParameter("id").isNullOrBlank()) { "Invalid media reference. Reopen this playlist." }
        return authenticated(url!!.newBuilder()).build().toString()
    }
    /** Ephemeral URL; callers must disable Coil disk caching. */
    fun coverArtUri(id: String): String = authenticatedMediaUri(endpoint("getCoverArt").addQueryParameter("id", id).build().toString())

    /** Bounded authenticated bytes; signed URLs never leave this download operation. */
    suspend fun coverArt(id: String): ByteArray = readWithRecovery { coverArtOnce(id) }

    private suspend fun coverArtOnce(id: String): ByteArray {
        val request = Request.Builder().url(authenticated(endpoint("getCoverArt")
            .addQueryParameter("id", id).addQueryParameter("size", "1024")).build())
            .header("Cache-Control", "no-store").build()
        return suspendCancellableCoroutine { continuation ->
            val call = artworkHttp.newCall(request)
            // Keep cancellation attached while the callback reads the body, not just until headers arrive.
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        transportFailure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        try {
                            response.use {
                                if (!it.isSuccessful) throw NavidromeException(when (it.code) {
                                    401, 403 -> "Authentication failed. Check your username and password."
                                    in 300..399 -> "The server redirected this request. Check its configured HTTPS address."
                                    else -> "The music server is unavailable. Retry shortly."
                                })
                                val body = it.body ?: throw invalidResponse()
                                if (body.contentLength() > MAX_COVER_ART_BYTES) throw invalidResponse()
                                val source = body.source()
                                if (source.request(MAX_COVER_ART_BYTES + 1)) throw invalidResponse()
                                source.readByteArray()
                            }
                        } catch (safe: NavidromeException) { throw safe }
                        catch (transport: IOException) { throw transportFailure(transport) }
                        catch (_: Exception) { throw invalidResponse() }
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }
    }

    private fun endpoint(name: String) = base.newBuilder().addPathSegment("rest").addPathSegment("$name.view")
    private fun authenticated(builder: HttpUrl.Builder): HttpUrl.Builder {
        val salt = ByteArray(16).also { random.nextBytes(it) }.hex()
        val token = MessageDigest.getInstance("MD5").digest((credentials.password + salt).toByteArray(Charsets.UTF_8)).hex()
        return builder.addQueryParameter("u", credentials.connection.username)
            .addQueryParameter("t", token).addQueryParameter("s", salt)
            .addQueryParameter("v", "1.16.1").addQueryParameter("c", "EveryoneMusic").addQueryParameter("f", "json")
    }
    /** Replay only known reads, once. Never reset the streaming client or replay a mutation. */
    private suspend fun <T> readWithRecovery(enabled: Boolean = true, read: suspend () -> T): T {
        try { return read() }
        catch (error: NavidromeException) {
            if (!enabled || !error.retryableTransport || networkPolicy?.offline == true) throw error
            currentCoroutineContext().ensureActive()
            delay(250)
            if (networkPolicy?.offline == true) throw error
            return read()
        }
    }

    private suspend fun api(name: String, vararg parameters: Pair<String, String>): JsonObject =
        readWithRecovery(name in READ_APIS) { apiOnce(name, parameters) }

    private suspend fun apiOnce(name: String, parameters: Array<out Pair<String, String>>, body: RequestBody? = null,
        strict: Boolean = false): JsonObject {
        val builder = endpoint(name)
        parameters.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        val request = Request.Builder().url(authenticated(builder).build()).header("Accept", "application/json")
            .header("Cache-Control", "no-store").apply { if (body != null) post(body) }.build()
        // Cancellation stays connected until the bounded body has been completely consumed.
        return suspendCancellableCoroutine { continuation ->
            val call = (if (name in READ_APIS) readHttp else http).newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(transportFailure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use { parse(it, strict) } }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }
    }
    private fun parse(response: Response, strict: Boolean): JsonObject {
        if (!response.isSuccessful) throw NavidromeException(when (response.code) {
            401, 403 -> "Authentication failed. Check your username and password."
            in 300..399 -> "The server redirected this request. Check its configured HTTPS address."
            else -> "The music server is unavailable. Retry shortly."
        }, writeDenied = response.code in setOf(401, 403))
        try {
            val body = response.body ?: throw invalidResponse()
            if (body.contentLength() > MAX_RESPONSE) throw invalidResponse()
            val source = body.source()
            if (source.request(MAX_RESPONSE + 1)) throw invalidResponse()
            val payload = source.readUtf8()
            if (strict) validateUniqueJsonKeys(payload)
            val root = json.parseToJsonElement(payload).jsonObject["subsonic-response"]?.jsonObject ?: throw invalidResponse()
            val status = if (strict) root.requiredString("status") else root.text("status")
            if (status != "ok" || root["error"] != null) {
                // A playlist payload contradicts a failure response, including not-found and denial.
                if (strict && "playlist" in root) throw invalidResponse()
                val code = if (strict) (root["error"]?.jsonObject?.get("code") as? JsonPrimitive)
                    ?.takeUnless { it.isString }?.intOrNull else root["error"]?.jsonObject?.number("code")
                throw NavidromeException(when (code) {
                    40, 41, 42, 43, 44 -> "Authentication failed. Check your username and password."
                    50 -> "Your account cannot access this music. Check server permissions."
                    70 -> "This item is no longer available. Refresh the playlist."
                    else -> "The server rejected this request. Check server compatibility and retry."
                }, writeDenied = status == "failed" && (code in setOf(40, 41, 42, 43, 44, 50) ||
                    strict && code in setOf(10, 20, 30)), itemNotFound = status == "failed" && code == 70)
            }
            return root
        } catch (safe: NavidromeException) { throw safe }
        catch (transport: IOException) { throw transportFailure(transport) }
        catch (_: Exception) { throw invalidResponse() }
    }
    /** JsonObject is last-key-wins. Reject conflicting keys before interpreting destructive responses. */
    private fun validateUniqueJsonKeys(payload: String) {
        val stack = mutableListOf<MutableSet<String>?>()
        var i = 0
        while (i < payload.length) {
            when (val token = payload[i++]) {
                '{', '[' -> {
                    if (stack.size >= 64) throw invalidResponse()
                    stack.add(if (token == '{') mutableSetOf() else null)
                }
                '}', ']' -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                '"' -> {
                    val start = i - 1
                    while (i < payload.length) {
                        when (payload[i++]) {
                            '\\' -> i++
                            '"' -> break
                        }
                    }
                    var next = i
                    while (next < payload.length && payload[next].isWhitespace()) next++
                    if (next < payload.length && payload[next] == ':') {
                        val key = json.decodeFromString<String>(payload.substring(start, i))
                        if (stack.lastOrNull()?.add(key) != true) throw invalidResponse()
                    }
                }
            }
        }
    }

    private fun JsonObject.obj(key: String): JsonObject = this[key]?.let {
        it as? JsonObject ?: throw invalidResponse()
    } ?: throw invalidResponse()
    private fun JsonObject.items(key: String): List<JsonObject> = when (val value = this[key]) {
        null, JsonNull -> emptyList()
        is JsonArray -> value.map { it as? JsonObject ?: throw invalidResponse() }
        else -> throw invalidResponse()
    }
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    // Optional OpenSubsonic metadata: malformed/unsupported timestamps must not hide playable songs.
    private fun JsonObject.validUntil(): Long? = text("validUntil")?.let {
        try { java.time.Instant.parse(it).toEpochMilli() } catch (_: Exception) { null }
    }
    private fun JsonObject.required(key: String): String = text(key)?.takeIf { it.isNotBlank() } ?: throw invalidResponse()
    private fun JsonObject.requiredString(key: String): String = (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString && it.content.isNotBlank() }?.content ?: throw invalidResponse()
    private fun JsonObject.number(key: String): Int = when (val value = this[key]) {
        null, JsonNull -> 0
        is JsonPrimitive -> value.intOrNull ?: throw invalidResponse()
        else -> throw invalidResponse()
    }
    companion object {
        internal const val MAX_PLAYLIST_NAME = 80
        private val READ_APIS = setOf("ping", "getMusicFolders", "getPlaylists", "search3", "getAlbumList2", "getAlbum", "getPlaylist", "getSong", "getArtists", "getArtist")
        private fun transportFailure(error: IOException) = NavidromeException(
            "Cannot reach the music server. Check your connection and retry.",
            retryableTransport = error !is javax.net.ssl.SSLHandshakeException && error !is javax.net.ssl.SSLPeerUnverifiedException)
        internal const val MAX_COVER_ART_BYTES = 2L * 1024 * 1024
        private const val MAX_RESPONSE = 8L * 1024 * 1024
        private val random = SecureRandom()
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun invalidResponse() = NavidromeException("The music server returned an invalid response. Check compatibility and retry.")
        private fun secureClient() = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
        fun validateBaseUrl(raw: String, allowLocalHttp: Boolean = false): String {
            val uri = try { java.net.URI(raw.trim()) } catch (_: Exception) { null }
            val url = raw.trim().toHttpUrlOrNull()
            val unsafePath = uri?.rawPath?.split('/')?.any {
                it.replace("%2e", ".", ignoreCase = true) in setOf(".", "..")
            } ?: true
            require(uri != null && uri.rawUserInfo == null && !unsafePath && !raw.contains('\\') &&
                raw.none { it.isISOControl() } &&
                url != null && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
                (url.scheme == "https" || (allowLocalHttp && url.scheme == "http" && url.host in setOf("localhost", "127.0.0.1", "10.0.2.2")))) {
                "Use an HTTPS server address without credentials, query parameters or a fragment."
            }
            return url!!.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build().toString()
        }
    }
}
