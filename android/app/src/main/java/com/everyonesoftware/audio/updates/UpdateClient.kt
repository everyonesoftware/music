package com.everyonesoftware.audio.updates

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
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
import kotlinx.serialization.Serializable
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
        val candidates = inventory()
            .filter { !it.draft && !it.prerelease && !it.publishedAt.isNullOrBlank() }
            .filter { it.assets.any { asset -> asset.name.endsWith(".apk", ignoreCase = true) } }
        check(candidates.map { tagCount(it.tag) }.distinct().size == candidates.size)
        val published = candidates.maxByOrNull { tagCount(it.tag) } ?: return@safe null
        val count = tagCount(published.tag)
        // A valid older stable tag cannot become an available downgrade.
        if (count.toLong() <= installedCode) return@safe null
        loadPublication(published)
    }

    private suspend fun loadPublication(published: PublishedRelease): UpdateRelease {
        val count = tagCount(published.tag)
        val prefix = assetPrefix(count)
        val manifestAsset = published.assets.single { it.name == "release.json" }
        check(manifestAsset.url == prefix + "release.json")
        val apkName = "Music-0.1.$count.apk"
        check(published.assets.count { it.name.endsWith(".apk", ignoreCase = true) } == 1)
        val apkAsset = published.assets.single { it.name == apkName }
        check(apkAsset.url == prefix + apkName)
        val manifestBytes = requireNotNull(fetch(manifestAsset.url, api = false) { readBounded(it, MAX_MANIFEST_JSON) })
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
            manifest.sha256, apkAsset.url, sourceSha = manifest.sha)
        validate(release)
        check(apkAsset.size == release.bytes)
        return release
    }

    /** History failure never invalidates discovery or the independently verified APK download. */
    suspend fun changelog(installedCode: Long, offered: UpdateRelease): UpdateChangelog = safe {
        validate(offered)
        check(isSha(offered.sourceSha) && installedCode in 1 until offered.count.toLong())
        // Exhaust the inventory before resolving either endpoint; later pages may be ambiguous.
        val publications = inventory().filter { !it.draft && !it.prerelease && !it.publishedAt.isNullOrBlank() }
            .filter { it.assets.any { asset -> asset.name.endsWith(".apk", ignoreCase = true) } }
        val installedPublication = publications.single { tagCount(it.tag).toLong() == installedCode }
        val offeredPublication = publications.single { tagCount(it.tag) == offered.count }
        val installed = loadPublication(installedPublication)
        val publishedOffer = loadPublication(offeredPublication)
        check(publishedOffer == offered.copy(notes = ""))
        for ((publication, release) in listOf(installedPublication to installed, offeredPublication to publishedOffer)) {
            check(publication.targetCommitish == release.sourceSha)
            check(publication.assets.map { it.name.lowercase(java.util.Locale.ROOT) }.distinct().size == publication.assets.size)
            check(publication.assets.filter { it.name == "release.json" || it.name == release.apk }.all { it.state == "uploaded" })
            verifyTag(publication.tag, release.sourceSha)
        }
        val commits = compare(installed.sourceSha, publishedOffer.sourceSha)
        val improvements = mutableListOf<String>()
        val fixes = mutableListOf<String>()
        for (commit in commits) {
            if (!relevant(commit)) continue
            val subject = stripVerified(commit.subject)
            val fix = Regex("^(?:fix|bugfix|hotfix)(?:\\([^)]*\\))?!?:|^fix(?:es|ed)?\\b", RegexOption.IGNORE_CASE).containsMatchIn(subject)
            val text = subject.replace(Regex("^[a-z]+(?:\\([^)]*\\))?!?:\\s*", RegexOption.IGNORE_CASE), "")
            check(text.isNotBlank())
            (if (fix) fixes else improvements).add(text)
        }
        UpdateChangelog(installed.sourceSha, publishedOffer.sourceSha, improvements.toList(), fixes.toList(), commits)
    }

    private suspend fun verifyTag(tag: String, expectedSha: String) {
        tagCount(tag)
        val reference = api<GitReference>(API + "git/ref/tags/$tag").value
        check(reference.ref == "refs/tags/$tag")
        var obj = reference.objectValue
        val seen = mutableSetOf<String>()
        while (obj.type == "tag") {
            check(isSha(obj.sha) && seen.add(obj.sha) && seen.size <= 16)
            val annotated = api<AnnotatedTag>(API + "git/tags/${obj.sha}").value
            check(annotated.sha == obj.sha)
            obj = annotated.objectValue
        }
        check(obj.type == "commit" && obj.sha == expectedSha)
    }

    private suspend fun compare(from: String, to: String): List<UpdateChangeCommit> {
        check(isSha(from) && isSha(to) && from != to)
        val commits = mutableListOf<UpdateChangeCommit>()
        val seen = mutableSetOf<String>()
        var expected: Int? = null
        for (page in 1..MAX_COMPARE_PAGES) {
            val response = api<Comparison>(API + "compare/$from...$to?per_page=100&page=$page")
            val range = response.value
            val total = range.total
            check(range.status == "ahead" && range.behind == 0 && range.ahead == total &&
                range.base.sha == from && range.mergeBase.sha == from)
            check(total in 1..MAX_COMPARE_PAGES * 100 && (expected == null || expected == total))
            expected = total
            check(range.commits.size == minOf(100, total - commits.size))
            for (entry in range.commits) {
                val subject = entry.commit.message.substringBefore('\n').removeSuffix("\r")
                check(isSha(entry.sha) && entry.sha != from && seen.add(entry.sha) && subject.isNotBlank())
                commits.add(UpdateChangeCommit(entry.sha, subject))
            }
            if (commits.size == total) {
                check(!response.hasNext && commits.last().sha == to)
                return commits.toList()
            }
        }
        error("History limit")
    }

    private suspend fun relevant(commit: UpdateChangeCommit): Boolean {
        // Do not hide shared/unknown scopes. File evidence is needed before excluding an
        // explicitly docs/CI/Windows subject, including renames crossing platform boundaries.
        val unrelated = Regex("^(?:(?:docs|ci)(?:\\([^)]*\\))?!?:|[a-z]+\\(windows(?:-[^)]*)?\\)!?:|windows:)", RegexOption.IGNORE_CASE)
        if (!unrelated.containsMatchIn(stripVerified(commit.subject))) return true
        val paths = mutableSetOf<String>()
        var relevant = false
        for (page in 1..MAX_FILE_PAGES) {
            val response = api<CommitDetail>(API + "commits/${commit.sha}?per_page=100&page=$page")
            check(response.value.sha == commit.sha && response.value.files.size <= 100)
            for (file in response.value.files) {
                check(file.filename.isNotBlank() && paths.add(file.filename))
                val currentRelevant = relevantPath(file.filename)
                val previousRelevant = file.previous?.let { relevantPath(it) } == true
                relevant = relevant || currentRelevant || previousRelevant
            }
            if (response.value.files.size < 100) {
                check(!response.hasNext)
                return relevant
            }
        }
        error("File inventory limit")
    }

    private fun relevantPath(path: String): Boolean {
        check(path.isNotBlank() && !path.startsWith("/") && path.split('/').none { it == ".." || it.isEmpty() })
        return !path.startsWith("apps/windows/") && !path.startsWith("windows/") &&
            !path.startsWith("docs/") && !path.startsWith(".github/") &&
            !path.endsWith(".md", ignoreCase = true) && !path.endsWith(".mdx", ignoreCase = true) &&
            path !in setOf(".gitignore", ".gitattributes")
    }

    private fun stripVerified(subject: String) = subject.replace(Regex("^(?:\\[verified\\]\\s*)+", RegexOption.IGNORE_CASE), "")

    private suspend inline fun <reified T> api(url: String): ApiPage<T> = requireNotNull(fetch(url, api = true) {
        ApiPage(json.decodeFromString<T>(readBounded(it, MAX_HISTORY_JSON).toString(Charsets.UTF_8)),
            it.headers.values("Link").any { link -> HAS_NEXT.containsMatchIn(link) })
    })

    private suspend fun inventory(): List<PublishedRelease> {
        val releases = mutableListOf<PublishedRelease>()
        for (page in 1..MAX_RELEASE_PAGES) {
            currentCoroutineContext().ensureActive()
            val entries = requireNotNull(fetch(releasesUrl(page), api = true) {
                val entries = json.decodeFromString<List<PublishedRelease>>(readBounded(it, MAX_RELEASE_JSON).toString(Charsets.UTF_8))
                // A short page is exhaustion only when GitHub does not advertise a continuation.
                check(entries.size == 100 || it.headers.values("Link").none { link ->
                    Regex("""rel\s*=\s*"?next\b""", RegexOption.IGNORE_CASE).containsMatchIn(link)
                })
                entries
            })
            check(entries.size <= 100)
            releases.addAll(entries)
            if (entries.size < 100) return releases
        }
        error("Release inventory limit")
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
                requireNotNull(fetch(release.downloadUrl, api = false) { response ->
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
                })
                currentCoroutineContext().ensureActive()
            }
        } catch (error: Throwable) {
            if (owned) target.delete()
            throw error
        }
    }

    private fun validate(release: UpdateRelease) {
        check(release.count in 1..2_100_000_000)
        check(release.version == "0.1.${release.count}")
        check(release.apk == "Music-${release.version}.apk")
        check(release.bytes in 1..MAX_APK_BYTES)
        check(Regex("[0-9a-f]{64}").matches(release.sha256))
        check(release.downloadUrl == assetPrefix(release.count) + release.apk)
    }

    private fun tagCount(tag: String): Int {
        check(Regex("v0\\.1\\.[1-9][0-9]{0,9}").matches(tag))
        return requireNotNull(tag.removePrefix("v0.1.").toIntOrNull()).also { check(it <= 2_100_000_000) }
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
        api: Boolean,
        consume: (Response) -> T,
    ): T? {
        var url = initial.toHttpUrl()
        repeat(MAX_REDIRECTS + 1) { hop ->
            checkAllowed(url, api)
            val transport = testConfig?.origin?.newBuilder()
                ?.encodedPath(url.encodedPath)?.encodedQuery(url.encodedQuery)?.build() ?: url
            val request = Request.Builder().url(transport)
                .header("Accept", if (api) "application/vnd.github+json" else "application/octet-stream")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "Music-Android-Updater")
                .build()
            val result = exchange(request) { response ->
                when {
                    response.code in REDIRECT_STATUSES -> {
                        check(hop < MAX_REDIRECTS)
                        val next = requireNotNull(url.resolve(requireNotNull(response.header("Location"))))
                        checkAllowed(next, api)
                        if (api) check(next.toString() == initial)
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

    private fun checkAllowed(url: HttpUrl, api: Boolean) {
        check(url.scheme == "https" && url.port == 443 && url.username.isEmpty() &&
            url.password.isEmpty() && url.fragment == null)
        if (api) {
            // Every API path/query is fixed here; server-provided URLs are never requested.
            check(url.host == "api.github.com")
            val path = url.encodedPath.removePrefix("/repos/everyonesoftware/music/")
            check(url.encodedPath == "/repos/everyonesoftware/music/$path")
            val sha = "[0-9a-f]{40}"
            val allowed = when {
                path == "releases" -> (1..MAX_RELEASE_PAGES).any { url.toString() == releasesUrl(it) }
                Regex("compare/$sha\\.\\.\\.$sha").matches(path) -> (1..MAX_COMPARE_PAGES).any { url.query == "per_page=100&page=$it" }
                Regex("commits/$sha").matches(path) -> (1..MAX_FILE_PAGES).any { url.query == "per_page=100&page=$it" }
                Regex("git/ref/tags/v0\\.1\\.[1-9][0-9]{0,9}").matches(path) -> url.query == null
                Regex("git/tags/$sha").matches(path) -> url.query == null
                else -> false
            }
            check(allowed)
        } else {
            check(url.host in ASSET_HOSTS)
            if (url.host == "github.com") {
                check(url.query == null)
                check(Regex("/everyonesoftware/music/releases/download/v0\\.1\\.[1-9][0-9]{0,9}/(?:release\\.json|Music-0\\.1\\.[1-9][0-9]{0,9}\\.apk)").matches(url.encodedPath))
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

    private data class ApiPage<T>(val value: T, val hasNext: Boolean)
    @Serializable private data class GitObject(val type: String, val sha: String)
    @Serializable private data class GitReference(val ref: String, @kotlinx.serialization.SerialName("object") val objectValue: GitObject)
    @Serializable private data class AnnotatedTag(val sha: String, @kotlinx.serialization.SerialName("object") val objectValue: GitObject)
    @Serializable private data class Revision(val sha: String)
    @Serializable private data class Message(val message: String)
    @Serializable private data class HistoryCommit(val sha: String, val commit: Message)
    @Serializable private data class Comparison(
        val status: String,
        @kotlinx.serialization.SerialName("ahead_by") val ahead: Int,
        @kotlinx.serialization.SerialName("behind_by") val behind: Int,
        @kotlinx.serialization.SerialName("total_commits") val total: Int,
        @kotlinx.serialization.SerialName("base_commit") val base: Revision,
        @kotlinx.serialization.SerialName("merge_base_commit") val mergeBase: Revision,
        val commits: List<HistoryCommit>,
    )
    @Serializable private data class ChangedFile(val filename: String, @kotlinx.serialization.SerialName("previous_filename") val previous: String? = null)
    @Serializable private data class CommitDetail(val sha: String, val files: List<ChangedFile>)
    private data class FetchResult<T>(val value: T? = null, val next: HttpUrl? = null)
    @Serializable private data class PublishedRelease(
        @kotlinx.serialization.SerialName("tag_name") val tag: String,
        val draft: Boolean,
        val prerelease: Boolean,
        @kotlinx.serialization.SerialName("published_at") val publishedAt: String?,
        @kotlinx.serialization.SerialName("target_commitish") val targetCommitish: String? = null,
        val body: String? = null,
        val assets: List<Asset>,
    )
    @Serializable private data class Asset(
        val name: String,
        @kotlinx.serialization.SerialName("browser_download_url") val url: String,
        val size: Long? = null,
        val state: String? = null,
    )
    @Serializable private data class Manifest(
        val count: Int, val version: String, val tag: String, val sha: String,
        val apk: String, val sha256: String, val bytes: Long,
    )

    private companion object {
        const val MAX_RELEASE_PAGES = 10
        fun releasesUrl(page: Int) = "https://api.github.com/repos/everyonesoftware/music/releases?per_page=100&page=$page"
        const val MAX_RELEASE_JSON = 1024 * 1024
        const val MAX_MANIFEST_JSON = 64 * 1024
        const val API = "https://api.github.com/repos/everyonesoftware/music/"
        const val MAX_COMPARE_PAGES = 10
        const val MAX_FILE_PAGES = 30
        const val MAX_HISTORY_JSON = 4 * 1024 * 1024
        val HAS_NEXT = Regex("""rel\s*=\s*"?next\b""", RegexOption.IGNORE_CASE)
        fun isSha(value: String) = Regex("[0-9a-f]{40}").matches(value)
        const val MAX_APK_BYTES = 150L * 1024 * 1024
        const val MAX_REDIRECTS = 5
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        val ASSET_HOSTS = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
        fun assetPrefix(count: Int) = "https://github.com/everyonesoftware/music/releases/download/v0.1.$count/"
    }
}
