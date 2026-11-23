package com.everyonesoftware.audio.updates

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Validated release metadata. Download revalidates this public value before trusting it. */
data class UpdateRelease(
    val count: Int,
    val version: String,
    val apk: String,
    val bytes: Long,
    val sha256: String,
    val downloadUrl: String,
    val notes: String = "",
    val sourceSha: String = "",
    val changelog: JsonObject? = null,
)

/** Deliberately carries neither a URL nor an underlying transport/parser exception. */
class UpdateException(val kind: UpdateFailureKind = UpdateFailureKind.TRANSIENT, val retryAfterMs: Long? = null) : IOException("Update unavailable. Please try again.")

/** JVM-only seam: canonical URLs are still validated, then transported to this exact loopback origin. */
internal data class UpdateTestConfig(val origin: HttpUrl)

/** Standalone, unauthenticated GitHub client. Never accepts an account's OkHttpClient. */
class UpdateClient private constructor(private val testConfig: UpdateTestConfig?) {
    constructor() : this(null)
    internal constructor(testConfig: UpdateTestConfig, @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit) : this(testConfig as UpdateTestConfig?)

    init {
        testConfig?.origin?.let {
            require(it.scheme == "http" && it.host in setOf("127.0.0.1", "localhost", "::1") &&
                it.username.isEmpty() && it.password.isEmpty() && it.encodedPath == "/" &&
                it.query == null && it.fragment == null) { "Invalid test origin" }
        }
    }

    private val http = OkHttpClient.Builder()
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES).cache(null)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = false }

    suspend fun latest(installedCode: Long): UpdateRelease? = safe {
        val tag = fetch(INDEX_URL, RequestKind.INDEX) { response ->
            val bytes = readBounded(response, MAX_INDEX_JSON)
            val text = try {
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                throw UpdateException(UpdateFailureKind.INVALID_METADATA)
            }
            val index = Json.decodeFromString(ReleaseIndexReader, text)
            tagCount(index.windows, MAX_WINDOWS_VERSION_CODE)
            index.android
        }
        val count = tagCount(tag)
        if (count.toLong() <= installedCode) return@safe null

        val published = fetch("$TAG_API_PREFIX$tag", RequestKind.TAG) {
            json.decodeFromString<PublishedRelease>(readBounded(it, MAX_RELEASE_JSON).toString(Charsets.UTF_8))
        }
        check(published.tag == tag && !published.draft && !published.prerelease && !published.publishedAt.isNullOrBlank())
        loadPublication(published, count)
    }

    private suspend fun loadPublication(published: PublishedRelease, count: Int): UpdateRelease {
        val prefix = assetPrefix(count)
        val manifestAsset = published.assets.single { it.name == "release.json" }
        check(manifestAsset.url == prefix + "release.json")
        val apkName = "Music-0.1.$count.apk"
        check(published.assets.count { it.name.endsWith(".apk", ignoreCase = true) } == 1)
        val apkAsset = published.assets.single { it.name == apkName }
        check(apkAsset.url == prefix + apkName)
        val manifestBytes = fetch(manifestAsset.url, RequestKind.ASSET) { readBounded(it, MAX_MANIFEST_JSON) }
        val manifestObject = json.parseToJsonElement(manifestBytes.toString(Charsets.UTF_8)) as JsonObject
        // JSON numeric strings are accepted by some decoders; the release schema requires numbers.
        for (field in listOf("count", "bytes")) {
            val value = manifestObject[field] as JsonPrimitive
            check(!value.isString && Regex("[1-9][0-9]*").matches(value.content))
        }
        val manifest = json.decodeFromJsonElement<Manifest>(manifestObject)
        check(manifest.tag == published.tag && manifest.count == count)
        check(Regex("[0-9a-f]{40}").matches(manifest.sha))
        val release = UpdateRelease(manifest.count, manifest.version, manifest.apk, manifest.bytes,
            manifest.sha256, apkAsset.url, sourceSha = manifest.sha,
            changelog = manifestObject["changelog"] as? JsonObject)
        validate(release)
        check(apkAsset.size == release.bytes)
        return release
    }

    /** Uses only the offered manifest fetched by discovery. No history network fallback. */
    suspend fun changelog(installedCode: Long, offered: UpdateRelease): UpdateChangelog = safe {
        validate(offered)
        requireNotNull(offered.localChangelog(installedCode))
    }

    /** Writes only to a new coordinator-owned temporary file; never replaces an existing file. */
    suspend fun download(release: UpdateRelease, target: File, onProgress: (Long, Long) -> Unit) {
        var owned = false
        try {
            safe {
                validate(release)
                withContext(Dispatchers.IO) {
                    check(target.createNewFile())
                    owned = true
                }
                fetch(release.downloadUrl, RequestKind.ASSET) { response ->
                    val body = requireNotNull(response.body)
                    val declared = body.contentLength()
                    check(declared == -1L || declared == release.bytes)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var received = 0L
                    target.outputStream().use { output ->
                        body.byteStream().use { input ->
                            onProgress(0, release.bytes)
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                check(received + read <= release.bytes)
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                received += read
                                onProgress(received, release.bytes)
                            }
                        }
                    }
                    check(received == release.bytes)
                    val expected = release.sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    check(MessageDigest.isEqual(expected, digest.digest()))
                    Unit
                }
                currentCoroutineContext().ensureActive()
            }
        } catch (error: Throwable) {
            if (owned) target.delete()
            throw error
        }
    }

    private fun validate(release: UpdateRelease) {
        check(release.count in 1..MAX_ANDROID_VERSION_CODE)
        check(release.version == "0.1.${release.count}")
        check(release.apk == "Music-${release.version}.apk")
        check(release.bytes in 1..MAX_APK_BYTES)
        check(Regex("[0-9a-f]{64}").matches(release.sha256))
        check(release.downloadUrl == assetPrefix(release.count) + release.apk)
    }

    private fun tagCount(tag: String, maximum: Int = MAX_ANDROID_VERSION_CODE): Int {
        check(Regex("v0\\.1\\.[1-9][0-9]{0,9}").matches(tag))
        return requireNotNull(tag.removePrefix("v0.1.").toIntOrNull()).also { check(it <= maximum) }
    }

    private fun readBounded(response: Response, maximum: Int): ByteArray {
        val body = requireNotNull(response.body)
        check(body.contentLength() <= maximum)
        return body.byteStream().use { input ->
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                check(result.size() + read <= maximum)
                result.write(buffer, 0, read)
            }
            result.toByteArray()
        }
    }

    /** Redirects are checked on logical HTTPS URLs before any test transport rewriting. */
    private suspend fun <T : Any> fetch(
        initial: String,
        kind: RequestKind,
        consume: (Response) -> T,
    ): T {
        var url = initial.toHttpUrl()
        checkAllowed(url, initial, kind, previous = null)
        repeat(MAX_REDIRECTS + 1) { hop ->
            val transport = testConfig?.origin?.newBuilder()
                ?.encodedPath(url.encodedPath)?.encodedQuery(url.encodedQuery)?.build() ?: url
            val request = Request.Builder().url(transport)
                .header("Accept", if (kind == RequestKind.TAG) "application/vnd.github+json" else "application/octet-stream")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "Music-Android-Updater")
                .build()
            val result = exchange(request) { response ->
                when {
                    response.code in REDIRECT_STATUSES -> {
                        check(hop < MAX_REDIRECTS)
                        val next = requireNotNull(url.resolve(requireNotNull(response.header("Location"))))
                        checkAllowed(next, initial, kind, previous = url)
                        FetchResult<T>(next = next)
                    }
                    else -> {
                        if (response.code == 429 || (response.code == 403 &&
                                (response.header("X-RateLimit-Remaining") == "0" || response.header("Retry-After") != null))) {
                            throw UpdateException(UpdateFailureKind.RATE_LIMITED, rateLimitDelay(response))
                        }
                        if (response.code in 500..599 || response.code == 408) throw UpdateException(UpdateFailureKind.TRANSIENT)
                        check(response.code == 200)
                        check(response.header("Content-Encoding").let { it == null || it.equals("identity", true) })
                        FetchResult(value = consume(response))
                    }
                }
            }
            result.value?.let { return it }
            url = requireNotNull(result.next)
        }
        error("Redirect limit")
    }

    private fun rateLimitDelay(response: Response): Long? {
        val now = response.receivedResponseAtMillis
        val retryAfter = response.header("Retry-After")?.let { value ->
            value.toLongOrNull()?.takeIf { it >= 0 }?.coerceAtMost(86_400L)?.times(1000L)
                ?: runCatching {
                    (java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli() - now).coerceIn(0L, 86_400_000L)
                }.getOrNull()
        }
        val reset = response.header("X-RateLimit-Reset")?.toLongOrNull()?.takeIf { it >= 0 }?.let {
            (it.coerceAtMost(now / 1000L + 86_400L) * 1000L - now).coerceIn(0L, 86_400_000L)
        }
        return listOfNotNull(retryAfter, reset).maxOrNull()
    }

    private fun checkAllowed(url: HttpUrl, initial: String, kind: RequestKind, previous: HttpUrl?) {
        check(url.scheme == "https" && url.port == 443 && url.username.isEmpty() &&
            url.password.isEmpty() && url.fragment == null)
        when (kind) {
            RequestKind.INDEX -> when {
                previous == null -> check(url.toString() == INDEX_URL)
                previous.toString() == INDEX_URL -> {
                    check(url.host == "github.com" && url.query == null)
                    val path = url.encodedPath
                    check(path.startsWith(ASSET_PATH_PREFIX) && path.endsWith("/$INDEX_NAME"))
                    tagCount(path.removePrefix(ASSET_PATH_PREFIX).removeSuffix("/$INDEX_NAME"))
                }
                else -> check(url.host in CDN_HOSTS)
            }
            RequestKind.TAG -> {
                check(url.toString() == initial && initial.startsWith(TAG_API_PREFIX))
                tagCount(initial.removePrefix(TAG_API_PREFIX))
            }
            RequestKind.ASSET -> if (url.host == "github.com") {
                check(url.toString() == initial && ASSET_PATH.matches(url.encodedPath))
            } else {
                check(previous != null && url.host in CDN_HOSTS)
            }
        }
    }

    /** A cancellation child cancels the actual socket, including a blocked response-body read.
     * coroutineScope waits for IO and file cleanup before returning cancellation to the caller. */
    private suspend fun <T> exchange(request: Request, consume: (Response) -> T): T = coroutineScope {
        val call = http.newCall(request)
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine<Nothing> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
            }
        }
        try {
            withContext(Dispatchers.IO) { call.execute().use(consume) }
        } finally {
            cancellation.cancelAndJoin()
        }
    }

    private suspend fun <T> safe(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: UpdateException) {
        currentCoroutineContext().ensureActive()
        throw failure
    } catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        throw UpdateException(if (failure is IOException) UpdateFailureKind.TRANSIENT else UpdateFailureKind.INVALID_METADATA)
    }

    private enum class RequestKind { INDEX, TAG, ASSET }
    private data class ReleaseIndex(val android: String, val windows: String)

    // Read fields before materializing an object map, which would silently overwrite duplicate keys.
    private object ReleaseIndexReader : DeserializationStrategy<ReleaseIndex> {
        override val descriptor = buildClassSerialDescriptor("ReleaseIndex") {
            element<JsonPrimitive>("schema")
            element<String>("android")
            element<String>("windows")
        }

        override fun deserialize(decoder: Decoder): ReleaseIndex = decoder.decodeStructure(descriptor) {
            val seen = mutableSetOf<Int>()
            var android: String? = null
            var windows: String? = null
            while (true) {
                val field = decodeElementIndex(descriptor)
                if (field == CompositeDecoder.DECODE_DONE) break
                check(seen.add(field))
                when (field) {
                    0 -> {
                        val schema = decodeSerializableElement(descriptor, field, JsonPrimitive.serializer())
                        check(!schema.isString && schema.content == "1")
                    }
                    1 -> android = decodeStringElement(descriptor, field)
                    2 -> windows = decodeStringElement(descriptor, field)
                    else -> error("Unknown release index field")
                }
            }
            check(seen.size == 3)
            ReleaseIndex(requireNotNull(android), requireNotNull(windows))
        }
    }

    private data class FetchResult<T>(val value: T? = null, val next: HttpUrl? = null)
    @Serializable private data class PublishedRelease(
        @kotlinx.serialization.SerialName("tag_name") val tag: String,
        val draft: Boolean,
        val prerelease: Boolean,
        @kotlinx.serialization.SerialName("published_at") val publishedAt: String?,
        val assets: List<Asset>,
    )
    @Serializable private data class Asset(
        val name: String,
        @kotlinx.serialization.SerialName("browser_download_url") val url: String,
        val size: Long? = null,
    )
    @Serializable private data class Manifest(
        val count: Int, val version: String, val tag: String, val sha: String,
        val apk: String, val sha256: String, val bytes: Long,
    )

    private companion object {
        const val INDEX_NAME = "latest-versions.json"
        const val INDEX_URL = "https://github.com/everyonesoftware/music/releases/latest/download/$INDEX_NAME"
        const val TAG_API_PREFIX = "https://api.github.com/repos/everyonesoftware/music/releases/tags/"
        const val ASSET_PATH_PREFIX = "/everyonesoftware/music/releases/download/"
        const val MAX_ANDROID_VERSION_CODE = 2_100_000_000
        const val MAX_WINDOWS_VERSION_CODE = 65_534
        const val MAX_INDEX_JSON = 16 * 1024
        const val MAX_RELEASE_JSON = 1024 * 1024
        const val MAX_MANIFEST_JSON = 1024 * 1024
        const val MAX_APK_BYTES = 150L * 1024 * 1024
        const val MAX_REDIRECTS = 5
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        val CDN_HOSTS = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")
        val ASSET_PATH = Regex("${ASSET_PATH_PREFIX}v0\\.1\\.[1-9][0-9]{0,9}/(?:release\\.json|Music-0\\.1\\.[1-9][0-9]{0,9}\\.apk)")
        fun assetPrefix(count: Int) = "https://github.com${ASSET_PATH_PREFIX}v0.1.$count/"
    }
}
