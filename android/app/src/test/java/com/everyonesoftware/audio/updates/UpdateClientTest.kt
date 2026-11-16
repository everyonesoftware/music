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
    private fun metadata(draft: Boolean = false, prerelease: Boolean = false) = """[{"tag_name":"v0.1.42","draft":$draft,"prerelease":$prerelease,"published_at":"2026-01-01T00:00:00Z","body":"Release notes","assets":[{"name":"release.json","browser_download_url":"${base}release.json"},{"name":"Music-0.1.42.apk","browser_download_url":"${base}Music-0.1.42.apk","size":${payload.size}}]}]"""
    private fun item(count: Int = 42) = metadata().removeSurrounding("[", "]").replace("0.1.42", "0.1.$count")
    private fun inventory(vararg releases: String) = releases.joinToString(",", "[", "]")
    private fun client(server: MockWebServer) = UpdateClient(UpdateTestConfig(server.url("/")))
    private fun response(body: String) = MockResponse().setBody(body)
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
        server.enqueue(response(metadata())); server.enqueue(response(manifest()))
        return requireNotNull(client(server).latest(1))
    }
    private fun temporary() = File.createTempFile("music-update-", ".apk").also { it.delete() }

    @Test fun selectsHighestAndroidVersionNotPublicationOrderOrOtherPlatform() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val windows = item(80).replace("Music-0.1.80.apk", "Music-0.1.80.zip")
            server.enqueue(response(inventory(item(9), windows, item(), item(41))))
            server.enqueue(response(manifest()))
            assertEquals(42, client(server).latest(1)!!.count)
            assertEquals("/repos/everyonesoftware/music/releases?per_page=100&page=1", server.takeRequest().path)
        }
    }

    @Test fun scansBeyondFullFirstPageBeforeChoosingHighestVersion() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(response(inventory(*Array(100) { item(it + 1).replace(".apk", ".zip") })))
            server.enqueue(response(metadata()))
            server.enqueue(response(manifest()))
            assertEquals(42, client(server).latest(1)!!.count)
            assertEquals("/repos/everyonesoftware/music/releases?per_page=100&page=1", server.takeRequest().path)
            assertEquals("/repos/everyonesoftware/music/releases?per_page=100&page=2", server.takeRequest().path)
        }
    }

    @Test fun ambiguousAndroidCandidatesFailClosedInsteadOfOfferingOlderVersion() = runBlocking<Unit> {
        val extraApk = item().replace("\"assets\":[", "\"assets\":[{\"name\":\"other.apk\",\"browser_download_url\":\"${base}other.apk\"},")
        val duplicateManifest = item().replace("\"assets\":[", "\"assets\":[{\"name\":\"release.json\",\"browser_download_url\":\"${base}release.json\"},")
        for (body in listOf(inventory(item(), item(), item(41)), inventory(extraApk, item(41)), inventory(duplicateManifest, item(41)))) {
            MockWebServer().use { server ->
                server.enqueue(response(body)); server.enqueue(response(manifest()))
                failure { client(server).latest(1) }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun rejectsShortInventoryThatAdvertisesMorePages() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(response("[]").setHeader("Link", "<https://api.github.com/repos/everyonesoftware/music/releases?per_page=100&page=2>; rel=\"next\""))
            failure { client(server).latest(1) }
        }
    }

    @Test fun apiRedirectCannotSkipInventoryPagesOrChangeQuery() = runBlocking<Unit> {
        for (location in listOf("?per_page=100&page=2", "?per_page=100&page=11", "?per_page=100&page=1&token=secret", "/repos/other/music/releases?per_page=100&page=1")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", location))
                server.enqueue(response("[]"))
                failure { client(server).latest(1) }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun highestVersionCanPrecedeLaterPagesAndIgnoredReleases() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val ignored = item(900).replace("\"prerelease\":false", "\"prerelease\":true")
            server.enqueue(response(inventory(item(), *Array(99) { ignored })))
            server.enqueue(response(inventory(item(9), item(100).replace("\"draft\":false", "\"draft\":true"), item(800).replace("\"2026-01-01T00:00:00Z\"", "null"))))
            server.enqueue(response(manifest()))
            assertEquals(42, client(server).latest(1)!!.count)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun fullTenthPageIsAnErrorNotPartialSuccessOrUpToDate() = runBlocking<Unit> {
        for (installed in listOf(1L, 999L)) {
            MockWebServer().use { server ->
                repeat(10) { server.enqueue(response(inventory(*Array(100) { item().replace(".apk", ".zip") }))) }
                failure { client(server).latest(installed) }
                assertEquals(10, server.requestCount)
                repeat(10) { page -> assertEquals("/repos/everyonesoftware/music/releases?per_page=100&page=${page + 1}", server.takeRequest().path) }
            }
        }
    }

    @Test fun shortTenthPageEstablishesExhaustion() = runBlocking<Unit> {
        MockWebServer().use { server ->
            repeat(9) { server.enqueue(response(inventory(*Array(100) { item().replace(".apk", ".zip") }))) }
            server.enqueue(response(metadata())); server.enqueue(response(manifest()))
            assertEquals(42, client(server).latest(1)!!.count)
            assertEquals(11, server.requestCount)
        }
    }

    @Test fun laterPageErrorsNeverReturnPartialCandidate() = runBlocking<Unit> {
        val full = inventory(item(), *Array(99) { item().replace(".apk", ".zip") })
        for (bad in listOf(response("["), response("{}"), MockResponse().setResponseCode(404), MockResponse().setResponseCode(500), response("[]").setHeader("Content-Length", 10).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))) {
            MockWebServer().use { server ->
                server.enqueue(response(full)); server.enqueue(bad)
                failure { client(server).latest(1) }
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun invalidNewestAndroidReleaseNeverFallsBack() = runBlocking<Unit> {
        for (bad in listOf(
            item().replace("release.json", "other.json"),
            item().replace("Music-0.1.42.apk", "wrong.apk"),
            item().replace("v0.1.42", "v0.1.042"),
            item().replace("v0.1.42", "v0.1.2100000001"),
            item().replace("v0.1.42", "v0.1.999999999999999999"))) {
            MockWebServer().use { server ->
                server.enqueue(response(inventory(item(41), bad)))
                failure { client(server).latest(1) }
                assertEquals(1, server.requestCount)
            }
        }
        MockWebServer().use { server ->
            server.enqueue(response(inventory(item(41), item())))
            server.enqueue(response(manifest().replace(hash, "bad")))
            failure { client(server).latest(1) }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun oversizedPageCannotBeTreatedAsCompleteInventory() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(response(inventory(*Array(101) { item().replace(".apk", ".zip") })))
            failure { client(server).latest(1) }
        }
    }

    @Test fun cancellationDuringLaterInventoryPageIsPrompt() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(response(inventory(*Array(100) { item().replace(".apk", ".zip") })))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.IO) { client(server).latest(1) }
            withContext(Dispatchers.IO) {
                assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            }
            withTimeout(3000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
    }

    @Test fun discoversExactPublishedReleaseAndSendsNoCredentials() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val found = release(server)
            assertEquals(UpdateRelease(42, "0.1.42", "Music-0.1.42.apk", payload.size.toLong(), hash, "${base}Music-0.1.42.apk", "Release notes"), found)
            assertEquals("/repos/everyonesoftware/music/releases?per_page=100&page=1", server.takeRequest().path)
            val manifestRequest = server.takeRequest()
            assertEquals("/everyonesoftware/music/releases/download/v0.1.42/release.json", manifestRequest.path)
            assertNull(manifestRequest.getHeader("Authorization")); assertNull(manifestRequest.getHeader("Cookie"))
        }
    }
    @Test fun neverOffersEqualOrOlderBuild() = runBlocking<Unit> {
        MockWebServer().use { server ->
            for (installed in listOf(42L, 43L)) {
                server.enqueue(response(metadata())); assertNull(client(server).latest(installed))
            }
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun ignoresDraftPrereleaseAndUnpublishedRelease() = runBlocking<Unit> {
        MockWebServer().use { server ->
            for (body in listOf(metadata(draft = true), metadata(prerelease = true), metadata().replace("\"2026-01-01T00:00:00Z\"", "null"))) {
                server.enqueue(response(body)); assertNull(client(server).latest(1))
            }
        }
    }
    @Test fun emptyInventoryIsNotAnErrorButHttpErrorsAreSafe() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(response("[]")); assertNull(client(server).latest(1))
            for (status in listOf(204, 206, 401, 403, 404, 429, 500)) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("secret https://private.invalid/token"))
                failure { client(server).latest(1) }
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
                server.enqueue(response(metadata())); server.enqueue(response(body)); failure { client(server).latest(1) }
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
            for (body in invalid) { server.enqueue(response(body)); failure { client(server).latest(1) } }
        }
    }
    @Test fun boundsJsonAndNotes() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(response(" ".repeat(1024 * 1024 + 1))); failure { client(server).latest(1) }
            server.enqueue(response(metadata().replace("Release notes", "n".repeat(20000))))
            server.enqueue(response(manifest()))
            assertEquals(16000, client(server).latest(1)!!.notes.length)
            server.enqueue(response(metadata())); server.enqueue(response(" ".repeat(65537)))
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
    @Test fun manualRedirectsAreBoundedAndRejectExternalAndInsecureTargets() = runBlocking<Unit> {
        MockWebServer().use { server ->
            for (location in listOf("https://evil.invalid/secret", "http://github.com/everyonesoftware/music/releases/download/v0.1.42/release.json", "https://github.com.evil.invalid/x", "https://user:secret@github.com/x")) {
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", location))
                failure { client(server).latest(1) }
            }
            repeat(6) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/repos/everyonesoftware/music/releases?per_page=100&page=1")) }
            failure { client(server).latest(1) }
        }
    }
    @Test fun permitsCanonicalAssetRedirectToCdnButNotApiRedirectToCdn() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(response(metadata()))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://release-assets.githubusercontent.com/github-production-release-asset/fixture?sig=test"))
            server.enqueue(response(manifest()))
            assertEquals(42, client(server).latest(1)!!.count)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://objects.githubusercontent.com/fixture"))
            failure { client(server).latest(1) }
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
                assertEquals(2, server.requestCount)
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
