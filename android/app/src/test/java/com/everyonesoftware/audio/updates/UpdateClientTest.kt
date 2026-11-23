package com.everyonesoftware.audio.updates

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class UpdateClientTest {
    private val payload = "signed apk fixture".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private val base = "https://github.com/everyonesoftware/music/releases/download/v0.1.42/"
    private fun manifest() = """{"count":42,"version":"0.1.42","tag":"v0.1.42","sha":"${"a".repeat(40)}","apk":"Music-0.1.42.apk","bytes":${payload.size},"sha256":"$hash"}"""
    private val tagPath = "/repos/everyonesoftware/music/releases/tags/v0.1.42"
    private val indexPath = "/everyonesoftware/music/releases/latest/download/latest-versions.json"
    private fun index() = """{"schema":1,"android":"v0.1.42","windows":"v0.1.80"}"""
    private fun metadata(draft: Boolean = false, prerelease: Boolean = false) = """{"tag_name":"v0.1.42","draft":$draft,"prerelease":$prerelease,"published_at":"2026-01-01T00:00:00Z","body":"Release notes","assets":[{"name":"release.json","browser_download_url":"${base}release.json"},{"name":"Music-0.1.42.apk","browser_download_url":"${base}Music-0.1.42.apk","size":${payload.size}}]}"""
    private fun client(server: MockWebServer) = UpdateClient(UpdateTestConfig(server.url("/")))
    private fun response(body: String) = MockResponse().setBody(body)
    private fun redirect(location: String) = MockResponse().setResponseCode(302).setHeader("Location", location)
    private suspend fun failure(block: suspend () -> Unit): UpdateException {
        try { block() } catch (e: UpdateException) {
            var cause: Throwable? = e
            while (cause != null) {
                assertEquals("Update unavailable. Please try again.", cause.message)
                assertTrue(cause is UpdateException)
                cause = cause.cause
            }
            return e
        }
        throw AssertionError("Expected safe update failure")
    }
    private suspend fun release(server: MockWebServer): UpdateRelease {
        server.enqueue(response(index())); server.enqueue(response(metadata())); server.enqueue(response(manifest()))
        return requireNotNull(client(server).latest(1))
    }
    private fun temporary() = File.createTempFile("music-update-", ".apk").also { it.delete() }

    @Test fun rateLimitsPreserveOnlyBoundedRetryDelayNotRawHeaders() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "3600"))
            assertEquals(3_600_000L, failure { client(server).latest(1) }.retryAfterMs)
            server.enqueue(MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0")
                .setHeader("X-RateLimit-Reset", (System.currentTimeMillis() / 1000 + 3600).toString()))
            val delay = failure { client(server).latest(1) }.retryAfterMs!!
            assertTrue(delay in 3_590_000L..3_600_000L)
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "private-invalid-header"))
            assertNull(failure { client(server).latest(1) }.retryAfterMs)
        }
    }

    @Test fun distinguishesRateLimitsTransientFailuresAndInvalidMetadataWithoutLeakingResponse() = runBlocking<Unit> {
        val responses = listOf(
            MockResponse().setResponseCode(429) to UpdateFailureKind.RATE_LIMITED,
            MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0") to UpdateFailureKind.RATE_LIMITED,
            MockResponse().setResponseCode(403).setHeader("Retry-After", "60") to UpdateFailureKind.RATE_LIMITED,
            MockResponse().setResponseCode(503) to UpdateFailureKind.TRANSIENT,
            MockResponse().setResponseCode(404) to UpdateFailureKind.INVALID_METADATA,
            response("{private-invalid-json") to UpdateFailureKind.INVALID_METADATA,
        )
        for ((reply, expected) in responses) {
            MockWebServer().use { server ->
                server.enqueue(reply.setHeader("X-Private", "https://user:secret@example.test"))
                assertEquals(expected, failure { client(server).latest(1) }.kind)
            }
        }
    }

    @Test fun discoversIndexedAndroidVersionWithoutReadingReleaseInventory() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(response(index()))
            server.enqueue(response(metadata()))
            server.enqueue(response(manifest()))
            assertEquals(42, client(server).latest(1)!!.count)
            assertEquals(listOf(
                indexPath,
                tagPath,
                "/everyonesoftware/music/releases/download/v0.1.42/release.json",
            ), List(3) { server.takeRequest(1, TimeUnit.SECONDS)?.path })
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun followsLatestRedirectThroughCanonicalIndexAssetToCdn() = runBlocking<Unit> {
        MockWebServer().use { server ->
            // The release that carries the index need not carry the current Android APK.
            server.enqueue(redirect("/everyonesoftware/music/releases/download/v0.1.80/latest-versions.json"))
            server.enqueue(redirect("https://release-assets.githubusercontent.com/index-fixture?sig=example"))
            server.enqueue(response(index()))
            server.enqueue(response(metadata()))
            server.enqueue(response(manifest()))
            assertEquals(42, client(server).latest(1)!!.count)
            assertEquals(listOf(indexPath, "/everyonesoftware/music/releases/download/v0.1.80/latest-versions.json",
                "/index-fixture?sig=example", tagPath, "/everyonesoftware/music/releases/download/v0.1.42/release.json"),
                List(5) { server.takeRequest(1, TimeUnit.SECONDS)?.path })
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun rejectsInvalidIndexEvenWhenInstalledBuildIsNewer() = runBlocking<Unit> {
        val invalid = listOf("[]", "{}", "{", """{"schema":1,"windows":"v0.1.42"}""",
            index().replace("\"schema\":1", "\"schema\":2"),
            index().replace("\"schema\":1", "\"schema\":\"1\""),
            index().replace("\"schema\":1", "\"schema\":1.0"),
            index().replace("\"v0.1.42\"", "null"),
            index().replace("\"v0.1.42\"", "42")) +
            listOf("v0.1.042", "v0.1.0", "v0.1.2100000001", "v0.1.999999999999999999",
                "windows-v0.1.42", "v0.1.42?x=1", "v0.1.42/other", "https://evil.invalid/").map {
                index().replace("v0.1.42", it)
            }
        MockWebServer().use { server ->
            for (body in invalid) {
                server.enqueue(response(body))
                assertEquals(UpdateFailureKind.INVALID_METADATA, failure { client(server).latest(Long.MAX_VALUE) }.kind)
                assertEquals(indexPath, server.takeRequest(1, TimeUnit.SECONDS)?.path)
            }
            assertEquals(invalid.size, server.requestCount)
        }
    }

    @Test fun rejectsMissingOrInvalidWindowsTagBeforeReportingCurrentOrLookingUpAndroid() = runBlocking<Unit> {
        val invalid = listOf(
            """{"schema":1,"android":"v0.1.42"}""",
            index().dropLast(1) + """, "other":true}""",
        ) + listOf("null", "80", "v0.1.80", "[]", "{}", "\"windows-v0.1.80\"", "\"v0.1.080\"", "\"v0.1.0\"",
            "\"v0.1.65535\"", "\"v0.1.80?x=1\"").map { index().replace("\"v0.1.80\"", it) }
        assertInvalidIndexWithoutFollowup(invalid)
    }

    @Test fun rejectsDuplicateIndexFieldsIncludingEscapedNames() = runBlocking<Unit> {
        val duplicates = listOf(
            """"schema":2""", """"schema":1""", """"android":"v0.1.41"""",
            """"\u0061ndroid":"v0.1.42""", """"windows":"v0.1.79""",
        ).map { "{$it,${index().drop(1)}" }
        assertInvalidIndexWithoutFollowup(duplicates)
    }

    @Test fun acceptsReorderedIndexWithWindowsAtItsVersionLimit() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(response("""{ "windows": "v0.1.65534", "\u0061ndroid": "v0.1.42", "schema": 1 }"""))
            assertNull(client(server).latest(42))
            assertEquals(indexPath, server.takeRequest(1, TimeUnit.SECONDS)?.path)
            assertEquals(1, server.requestCount)
        }
    }

    private suspend fun assertInvalidIndexWithoutFollowup(bodies: List<String>) {
        for (installed in listOf(42L, 1L)) {
            MockWebServer().use { server ->
                for (body in bodies) {
                    server.enqueue(response(body))
                    assertEquals(body, UpdateFailureKind.INVALID_METADATA, failure { client(server).latest(installed) }.kind)
                    assertEquals(indexPath, server.takeRequest(1, TimeUnit.SECONDS)?.path)
                }
                assertEquals(bodies.size, server.requestCount)
            }
        }
    }

    @Test fun boundsIndexBytesAndRejectsInvalidUtf8() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val atLimit = index().padEnd(16 * 1024)
            server.enqueue(response(atLimit))
            assertNull(client(server).latest(42))
            server.enqueue(response(atLimit + " "))
            server.enqueue(MockResponse().setChunkedBody(atLimit + " ", 256))
            val malformed = okio.Buffer().writeUtf8(index().dropLast(1) + ",\"future\":\"")
                .writeByte(0xff).writeUtf8("\"}")
            server.enqueue(MockResponse().setBody(malformed))
            repeat(3) {
                assertEquals(UpdateFailureKind.INVALID_METADATA, failure { client(server).latest(1) }.kind)
            }
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun indexedReleaseMustBeTheExactPublishedStableTagWithUnambiguousAssets() = runBlocking<Unit> {
        val extraApk = metadata().replace("\"assets\":[", "\"assets\":[{\"name\":\"other.apk\",\"browser_download_url\":\"${base}other.apk\"},")
        val duplicateManifest = metadata().replace("\"assets\":[", "\"assets\":[{\"name\":\"release.json\",\"browser_download_url\":\"${base}release.json\"},")
        val invalid = listOf(metadata(draft = true), metadata(prerelease = true),
            metadata().replace("\"2026-01-01T00:00:00Z\"", "null"), metadata().replace("v0.1.42", "v0.1.41"),
            metadata().replace("release.json", "other.json"), metadata().replace(".apk", ".zip"),
            metadata().replace("Music-0.1.42.apk", "wrong.apk"), extraApk, duplicateManifest, "[]")
        MockWebServer().use { server ->
            for (body in invalid) {
                server.enqueue(response(index())); server.enqueue(response(body))
                assertEquals(UpdateFailureKind.INVALID_METADATA, failure { client(server).latest(1) }.kind)
                assertEquals(indexPath, server.takeRequest(1, TimeUnit.SECONDS)?.path)
                assertEquals(tagPath, server.takeRequest(1, TimeUnit.SECONDS)?.path)
            }
            assertEquals(invalid.size * 2, server.requestCount)
        }
    }

    @Test fun missingOrRateLimitedIndexedReleaseDoesNotFallBack() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(response(index())); server.enqueue(MockResponse().setResponseCode(404))
            assertEquals(UpdateFailureKind.INVALID_METADATA, failure { client(server).latest(1) }.kind)
            server.enqueue(response(index())); server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
            val limited = failure { client(server).latest(1) }
            assertEquals(UpdateFailureKind.RATE_LIMITED, limited.kind)
            assertEquals(60_000L, limited.retryAfterMs)
            assertEquals(listOf(indexPath, tagPath, indexPath, tagPath), List(4) { server.takeRequest(1, TimeUnit.SECONDS)?.path })
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun cancellationDuringIndexedTagRequestIsPrompt() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(response(index()))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.IO) { client(server).latest(1) }
            withContext(Dispatchers.IO) {
                assertEquals(indexPath, server.takeRequest(3, TimeUnit.SECONDS)?.path)
                assertEquals(tagPath, server.takeRequest(3, TimeUnit.SECONDS)?.path)
            }
            withTimeout(3000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
    }

    @Test fun discoversExactPublishedReleaseAndSendsNoCredentials() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val found = release(server)
            assertEquals(UpdateRelease(42, "0.1.42", "Music-0.1.42.apk", payload.size.toLong(), hash, "${base}Music-0.1.42.apk", sourceSha = "a".repeat(40)), found)
            for (path in listOf(indexPath, tagPath, "/everyonesoftware/music/releases/download/v0.1.42/release.json")) {
                val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals(path, request.path)
                assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
                assertEquals("identity", request.getHeader("Accept-Encoding"))
            }
        }
    }
    @Test fun currentDownloadsMarkdownIsNeverUsedAsChangelogNotes() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(response(index()))
            server.enqueue(response(metadata().replace("Release notes", "## Current downloads | Platform | Download | Android | APK |")))
            server.enqueue(response(manifest()))
            val found = client(server).latest(1)!!
            assertEquals("", found.notes)
            assertEquals("a".repeat(40), found.sourceSha)
        }
    }

    @Test fun neverOffersEqualOrOlderBuild() = runBlocking<Unit> {
        MockWebServer().use { server ->
            for (installed in listOf(42L, 43L)) {
                server.enqueue(response(index())); assertNull(client(server).latest(installed))
                assertEquals(indexPath, server.takeRequest(1, TimeUnit.SECONDS)?.path)
            }
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun indexHttpErrorsAreSafeAndNeverReportCurrent() = runBlocking<Unit> {
        MockWebServer().use { server ->
            for (status in listOf(204, 206, 401, 403, 404, 429, 500)) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("secret https://private.invalid/token"))
                failure { client(server).latest(100) }
                assertEquals(indexPath, server.takeRequest(1, TimeUnit.SECONDS)?.path)
            }
        }
    }
    @Test fun validatesManifestIdentityHashSizeAndCommit() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val invalid = listOf(
                manifest().replace("\"count\":42", "\"count\":41"),
                manifest().replace("0.1.42", "0.1.042"),
                manifest().replace("Music-0.1.42.apk", "../Music-0.1.42.apk"),
                manifest().replace(hash, "x".repeat(64)),
                manifest().replace("\"bytes\":${payload.size}", "\"bytes\":0"),
                manifest().replace("\"bytes\":${payload.size}", "\"bytes\":1073741825"),
                manifest().replace("a".repeat(40), "bad"),
                manifest().replace("\"count\":42", "\"count\":\"42\""), "{}", "not json")
            for (body in invalid) {
                server.enqueue(response(index())); server.enqueue(response(metadata())); server.enqueue(response(body))
                failure { client(server).latest(1) }
            }
        }
    }
    @Test fun rejectsUntrustedOrAmbiguousAssetUrls() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val invalid = listOf(
                metadata().replace(base, "https://evil.invalid/"),
                metadata().replace(base, base.replace("everyonesoftware/music", "someone/music")),
                metadata().replace(base, base.replace("everyonesoftware/music", "everyonesoftware/audio")),
                metadata().replace(base, base.replace("https:", "http:")),
                metadata().replace("${base}release.json", "${base}release.json?token=secret"),
                metadata().replace("\"name\":\"release.json\"", "\"name\":\"../release.json\""))
            for (body in invalid) {
                server.enqueue(response(index())); server.enqueue(response(body)); failure { client(server).latest(1) }
            }
            assertEquals(invalid.size * 2, server.requestCount)
        }
    }
    @Test fun boundsTagResponseAndManifest() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(response(index()))
            server.enqueue(response(" ".repeat(1024 * 1024 + 1))); failure { client(server).latest(1) }
            server.enqueue(response(index())); server.enqueue(response(metadata()))
            server.enqueue(response(manifest().padEnd(1024 * 1024 + 1)))
            failure { client(server).latest(1) }
        }
    }
    @Test fun downloadsAndVerifiesSizeChecksumAndProgress() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val found = release(server); val target = temporary()
            try {
                server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
                val progress = mutableListOf<Pair<Long, Long>>()
                client(server).download(found, target) { received, total -> progress += received to total }
                assertArrayEquals(payload, target.readBytes())
                assertEquals(payload.size.toLong() to payload.size.toLong(), progress.last())
                assertTrue(progress.all { it.first <= it.second })
            } finally { target.delete() }
        }
    }
    @Test fun deletesCorruptTruncatedAndOversizedDownloads() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val found = release(server)
            for (body in listOf("x".repeat(payload.size), "short", "x".repeat(payload.size + 1))) {
                val target = temporary()
                server.enqueue(response(body).setChunkedBody(body, 3))
                failure { client(server).download(found, target) { _, _ -> } }
                assertFalse(target.exists())
            }
            val target = temporary()
            server.enqueue(response("short").setHeader("Content-Length", payload.size).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
            failure { client(server).download(found, target) { _, _ -> } }; assertFalse(target.exists())
        }
    }
    @Test fun downloadRejectsTamperedReleaseBeforeNetwork() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val found = release(server); val before = server.requestCount
            for (bad in listOf(found.copy(downloadUrl = "http://evil.invalid/secret"), found.copy(apk = "../evil.apk"), found.copy(bytes = -1), found.copy(sha256 = "bad"))) {
                failure { client(server).download(bad, temporary()) { _, _ -> } }
            }
            assertEquals(before, server.requestCount)
        }
    }
    @Test fun indexRedirectRequiresCanonicalAssetThenOnlyAllowlistedCdn() = runBlocking<Unit> {
        val path = "/everyonesoftware/music/releases/download/v0.1.80/latest-versions.json"
        MockWebServer().use { server ->
            val invalid = listOf("https://evil.invalid$path", "https://github.com.evil.invalid$path",
                "http://github.com$path", "https://user:secret@github.com$path", "https://github.com:8443$path",
                "$path?query=private", "$path#fragment", path.replace("everyonesoftware", "someone"),
                path.replace("v0.1.80", "v0.1.080"), path.replace("latest-versions.json", "release.json"),
                "https://release-assets.githubusercontent.com/cdn-not-yet")
            for (location in invalid) {
                server.enqueue(redirect(location))
                failure { client(server).latest(1) }
                assertEquals(indexPath, server.takeRequest(1, TimeUnit.SECONDS)?.path)
            }
            assertEquals(invalid.size, server.requestCount)
            server.enqueue(redirect(path)); server.enqueue(redirect("https://evil.invalid/index"))
            failure { client(server).latest(1) }
            assertEquals(invalid.size + 2, server.requestCount)
            server.enqueue(redirect(path))
            repeat(5) { server.enqueue(redirect("https://objects.githubusercontent.com/index")) }
            failure { client(server).latest(1) }
            assertEquals(invalid.size + 8, server.requestCount)
        }
    }
    @Test fun permitsCanonicalAssetRedirectToCdnButNotApiRedirectToCdn() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(response(index())); server.enqueue(response(metadata()))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://release-assets.githubusercontent.com/github-production-release-asset/fixture?sig=test"))
            server.enqueue(response(manifest()))
            assertEquals(42, client(server).latest(1)!!.count)
            for (location in listOf("https://objects.githubusercontent.com/fixture", "$tagPath?query=private",
                tagPath.replace("v0.1.42", "v0.1.43"), tagPath.replace("everyonesoftware", "someone"))) {
                server.enqueue(response(index())); server.enqueue(redirect(location))
                failure { client(server).latest(1) }
            }
            assertEquals(12, server.requestCount)
        }
    }
    @Test fun cancellationClosesCallAndRemovesPartialFile() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val found = release(server); val target = temporary()
            server.enqueue(response("x".repeat(payload.size)).throttleBody(1, 1, TimeUnit.SECONDS))
            val started = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.IO) { client(server).download(found, target) { n, _ -> if (n > 0) started.complete(Unit) } }
            withTimeout(3000) { started.await() }
            withTimeout(3000) { job.cancelAndJoin() }
            assertFalse(target.exists())
        }
    }
    @Test fun cancellationWhileWaitingForHeadersIsPrompt() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.IO) { client(server).latest(1) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
            withTimeout(3000) { job.cancelAndJoin() }
        }
    }
    @Test fun rejectsDownloadStatusesAndDeclaredOversizeWithoutKeepingFile() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val found = release(server)
            for (status in listOf(204, 206, 404, 403, 429, 500)) {
                server.enqueue(MockResponse().setResponseCode(status))
                val target = temporary()
                failure { client(server).download(found, target) { _, _ -> } }
                assertFalse(target.exists())
            }
            server.enqueue(response("x").setHeader("Content-Length", found.bytes + 1))
            val target = temporary()
            failure { client(server).download(found, target) { _, _ -> } }
            assertFalse(target.exists())
        }
    }
    @Test fun preservesPreexistingTarget() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val found = release(server)
            val target = temporary().also { it.writeText("keep existing") }
            try {
                failure { client(server).download(found, target) { _, _ -> } }
                assertEquals("keep existing", target.readText())
                assertEquals(3, server.requestCount)
            } finally { target.delete() }
        }
    }
    @Test fun verifiesDownloadAfterAllowedCdnRedirect() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val found = release(server); val target = temporary()
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "https://objects.githubusercontent.com/fixture?signature=test"))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
            try {
                client(server).download(found, target) { _, _ -> }
                assertArrayEquals(payload, target.readBytes())
            } finally { target.delete() }
        }
    }
    @Test fun offlineErrorsHaveNoTransportDetails() = runBlocking<Unit> {
        val server = MockWebServer(); server.start(); val client = client(server); server.shutdown()
        failure { client.latest(1) }
    }
}
