package com.everyonesoftware.audio.updates

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class UpdateChangelogClientTest {
    private val from = "a".repeat(40)
    private val to = "b".repeat(40)
    private val hash = "c".repeat(64)
    private val api = "/repos/everyonesoftware/music/"
    private fun quote(value: String) = Json.encodeToString(value)
    private fun base(count: Int) = "https://github.com/everyonesoftware/music/releases/download/v0.1.$count/"
    private fun publication(count: Int, sha: String) = """{"tag_name":"v0.1.$count","target_commitish":"$sha","draft":false,"prerelease":false,"published_at":"2026-01-01T00:00:00Z","body":"## Current downloads | Android | APK |","assets":[{"name":"release.json","browser_download_url":"${base(count)}release.json","state":"uploaded"},{"name":"Music-0.1.$count.apk","browser_download_url":"${base(count)}Music-0.1.$count.apk","size":18,"state":"uploaded"}]}"""
    private fun manifest(count: Int, sha: String) = """{"count":$count,"version":"0.1.$count","tag":"v0.1.$count","sha":"$sha","apk":"Music-0.1.$count.apk","bytes":18,"sha256":"$hash"}"""
    private fun offered() = UpdateRelease(42, "0.1.42", "Music-0.1.42.apk", 18, hash, base(42) + "Music-0.1.42.apk", sourceSha = to)
    private fun commit(sha: String, subject: String) = """{"sha":"$sha","commit":{"message":${quote(subject)}}}"""
    private fun comparison(commits: List<String>, total: Int = commits.size) = """{"status":"ahead","ahead_by":$total,"behind_by":0,"total_commits":$total,"base_commit":{"sha":"$from"},"merge_base_commit":{"sha":"$from"},"commits":[${commits.joinToString(",")}]}"""
    private fun objectAt(sha: String, type: String = "commit") = """{"object":{"type":"$type","sha":"$sha"}}"""
    private fun reference(count: Int, sha: String, type: String = "commit") = objectAt(sha, type).dropLast(1) + ",\"ref\":\"refs/tags/v0.1.$count\"}"
    private fun comparePath(page: Int = 1) = api + "compare/$from...$to?per_page=100&page=$page"
    private fun refPath(count: Int) = api + "git/ref/tags/v0.1.$count"
    private fun manifestPath(count: Int) = "/everyonesoftware/music/releases/download/v0.1.$count/release.json"
    private fun fixture(): MutableMap<String, String> = mutableMapOf(
        api + "releases?per_page=100&page=1" to "[${publication(42, to)},${publication(30, "d".repeat(40))},${publication(10, from)}]",
        manifestPath(10) to manifest(10, from), manifestPath(42) to manifest(42, to),
        refPath(10) to reference(10, from), refPath(42) to reference(42, to),
        comparePath() to comparison(listOf(commit(to, "[verified] fix(android): Restore playback\nOriginal commit body"))),
    )
    private fun server(routes: Map<String, String>) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = routes[request.path]?.let { MockResponse().setBody(it) }
                ?: MockResponse().setResponseCode(404)
        }
        start()
    }
    private fun client(server: MockWebServer) = UpdateClient(UpdateTestConfig(server.url("/")))
    private suspend fun failure(block: suspend () -> Unit): UpdateException {
        try { block() } catch (error: UpdateException) {
            assertEquals("Update unavailable. Please try again.", error.message)
            assertNull(error.cause)
            return error
        }
        throw AssertionError("Expected unavailable changelog")
    }

    private fun detailPath(sha: String, page: Int = 1) = api + "commits/$sha?per_page=100&page=$page"
    private fun files(sha: String, paths: List<String>) = """{"sha":"$sha","files":[${paths.joinToString(",") { """{"filename":${quote(it)}}""" }}]}"""
    private fun sha(number: Int) = number.toString(16).padStart(40, '0')

    @Test fun validatesBothPublishedManifestsAndOfferedIdentity() = runBlocking<Unit> {
        val mutations: List<(MutableMap<String, String>) -> Unit> = listOf(
            { it[manifestPath(10)] = manifest(10, from).replace("\"count\":10", "\"count\":\"10\"") },
            { it[manifestPath(42)] = manifest(42, to).replace(hash, "bad") },
            { it[manifestPath(10)] = manifest(10, from).replace("\"bytes\":18", "\"bytes\":19") },
            { it[manifestPath(42)] = manifest(42, from) },
            { it[manifestPath(10)] = manifest(10, from).replace("Music-0.1.10.apk", "../Music-0.1.10.apk") },
            { it[manifestPath(10)] = manifest(10, from).replace("0.1.10", "0.1.010") },
            { it[refPath(10)] = objectAt(to) },
            { it[api + "releases?per_page=100&page=1"] = "[${publication(42, to)},${publication(10, from)},${publication(10, from)}]" },
            { it[api + "releases?per_page=100&page=1"] = "[${publication(42, to)}]" },
            { it[api + "releases?per_page=100&page=1"] = "[${publication(10, from)},${publication(42, from)}]" },
            { it[api + "releases?per_page=100&page=1"] = "[${publication(10, from)},${publication(42, to).replace("uploaded", "new")}]" },
        )
        for (mutate in mutations) {
            val routes = fixture().also(mutate)
            server(routes).use { failure { client(it).changelog(10, offered()) } }
        }
        for (bad in listOf(offered().copy(sourceSha = from), offered().copy(bytes = 19), offered().copy(sha256 = "d".repeat(64)))) {
            server(fixture()).use { failure { client(it).changelog(10, bad) } }
        }
    }

    @Test fun rejectsMissingOrMalformedExpectedSourceBeforeNetwork() = runBlocking<Unit> {
        server(fixture()).use { server ->
            for (source in listOf("", "bad", "../tags/main", "A".repeat(40), to + "?secret")) {
                failure { client(server).changelog(10, offered().copy(sourceSha = source)) }
            }
            for (installed in listOf(0L, 42L, 43L, Long.MAX_VALUE)) failure { client(server).changelog(installed, offered()) }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun scansCompleteReleaseInventoryIncludingInstalledOnLaterPage() = runBlocking<Unit> {
        val routes = fixture()
        val windows = publication(70, to).replace(".apk", ".zip")
        routes[api + "releases?per_page=100&page=1"] = "[${publication(42, to)},${List(99) { windows }.joinToString(",")}]"
        routes[api + "releases?per_page=100&page=2"] = "[${publication(10, from)}]"
        server(routes).use { assertEquals(from, client(it).changelog(10, offered()).fromSha) }
        routes.remove(api + "releases?per_page=100&page=2")
        server(routes).use { failure { client(it).changelog(10, offered()) } }
    }

    @Test fun peelsAnnotatedTagsButRejectsCyclesInvalidTypesAndMismatches() = runBlocking<Unit> {
        val tagSha = "e".repeat(40)
        val routes = fixture()
        routes[refPath(42)] = reference(42, tagSha, "tag")
        routes[api + "git/tags/$tagSha"] = """{"sha":"$tagSha","object":{"type":"commit","sha":"$to"}}"""
        server(routes).use { assertEquals(to, client(it).changelog(10, offered()).toSha) }
        for (bad in listOf(objectAt(tagSha, "tag"), objectAt(to, "tree"), objectAt(from), objectAt("../../evil", "tag"))) {
            routes[api + "git/tags/$tagSha"] = bad.dropLast(1) + ",\"sha\":\"$tagSha\"}"
            server(routes).use { failure { client(it).changelog(10, offered()) } }
        }
    }

    @Test fun collectsEveryComparePageKeepingChronologicalOriginalDetails() = runBlocking<Unit> {
        val routes = fixture()
        routes[comparePath()] = comparison((1..100).map { commit(sha(it), "feat(shared): Change $it") }, 101)
        routes[comparePath(2)] = comparison(listOf(commit(to, "fix: Last change")), 101)
        server(routes).use { server ->
            val result = client(server).changelog(10, offered())
            assertEquals(101, result.count)
            assertEquals(100, result.improvements.size)
            assertEquals("feat(shared): Change 1", result.commits.first().subject)
            assertEquals(to, result.commits.last().sha)
        }
    }

    @Test fun rejectsDivergenceTruncatedDuplicateCountAndHeadMismatch() = runBlocking<Unit> {
        val good = comparison(listOf(commit(to, "fix: Last change")))
        val invalid = listOf(
            good.replace("\"ahead\"", "\"diverged\""), good.replace("\"behind_by\":0", "\"behind_by\":1"),
            good.replace("\"ahead_by\":1", "\"ahead_by\":2"),
            good.replace("\"total_commits\":1", "\"total_commits\":1001"),
            good.replace("\"base_commit\":{\"sha\":\"$from\"}", "\"base_commit\":{\"sha\":\"$to\"}"),
            comparison(listOf(commit(to, "fix: duplicate"), commit(to, "fix: duplicate"))),
            comparison(listOf(commit(from, "fix: base"))), comparison(listOf(commit(sha(1), "fix: wrong head"))),
            comparison(emptyList(), 1), comparison(listOf(commit(to, "\nno subject"))),
        )
        for (body in invalid) {
            val routes = fixture().also { it[comparePath()] = body }
            server(routes).use { failure { client(it).changelog(10, offered()) } }
        }
        val routes = fixture().also { it[comparePath()] = comparison((1..100).map { n -> commit(sha(n), "Change $n") }, 101) }
        server(routes).use { failure { client(it).changelog(10, offered()) } }
        routes[comparePath(2)] = comparison(listOf(commit(to, "Change")), 102)
        server(routes).use { failure { client(it).changelog(10, offered()) } }
    }

    @Test fun omitsDocsCiWindowsOnlyButRetainsSharedUnknownAndOriginalCommitDetails() = runBlocking<Unit> {
        val subjects = listOf("docs: Update guide", "ci: Build Windows", "fix(windows): Shell", "[verified] feat(shared): Shared transport", "feat(unknown): Unknown improvement", "fix(windows): Shared fix", "docs: Shared docs tooling", "feat: Unscoped")
        val ids = subjects.indices.map { if (it == subjects.lastIndex) to else sha(it + 1) }
        val routes = fixture()
        routes[comparePath()] = comparison(subjects.indices.map { commit(ids[it], subjects[it]) })
        routes[detailPath(ids[0])] = files(ids[0], listOf("docs/guide.md"))
        routes[detailPath(ids[1])] = files(ids[1], listOf(".github/workflows/release.yml"))
        routes[detailPath(ids[2])] = files(ids[2], listOf("apps/windows/Music.App/Shell.cs"))
        routes[detailPath(ids[5])] = files(ids[5], listOf("apps/windows/Shell.cs", "shared/update.json"))
        routes[detailPath(ids[6])] = files(ids[6], listOf("scripts/documentation.mjs"))
        server(routes).use { server ->
            val result = client(server).changelog(10, offered())
            assertEquals(listOf("Shared transport", "Unknown improvement", "Shared docs tooling", "Unscoped"), result.improvements)
            assertEquals(listOf("Shared fix"), result.fixes)
            assertEquals(subjects, result.commits.map { it.subject })
            assertEquals(5, result.count)
        }
    }

    @Test fun filePagingAndCrossPlatformRenamesCannotHideSharedChanges() = runBlocking<Unit> {
        val routes = fixture()
        routes[comparePath()] = comparison(listOf(commit(to, "fix(windows): Shared removal")))
        routes[detailPath(to)] = files(to, (1..100).map { "apps/windows/file$it.cs" })
        routes[detailPath(to, 2)] = """{"sha":"$to","files":[{"filename":"apps/windows/moved.cs","previous_filename":"shared/source.kt"}]}"""
        server(routes).use { assertEquals(listOf("Shared removal"), client(it).changelog(10, offered()).fixes) }
        routes.remove(detailPath(to, 2))
        server(routes).use { failure { client(it).changelog(10, offered()) } }
        routes[detailPath(to, 2)] = files(to, listOf("apps/windows/file1.cs"))
        server(routes).use { failure { client(it).changelog(10, offered()) } }
        routes[detailPath(to, 2)] = files(from, listOf("shared/file.kt"))
        server(routes).use { failure { client(it).changelog(10, offered()) } }
    }

    @Test fun legitimateEmptyGroupsKeepCommitDetailsInsteadOfReleaseMarkdown() = runBlocking<Unit> {
        val routes = fixture()
        routes[comparePath()] = comparison(listOf(commit(to, "ci: Workflow")))
        routes[detailPath(to)] = files(to, listOf(".github/workflows/check.yml"))
        server(routes).use {
            val result = client(it).changelog(10, offered())
            assertEquals(0, result.count)
            assertEquals(1, result.commits.size)
        }
    }

    @Test fun historyFailureDoesNotPreventIndependentDiscovery() = runBlocking<Unit> {
        val routes = fixture().also { it.remove(comparePath()) }
        server(routes).use {
            val client = client(it)
            failure { client.changelog(10, offered()) }
            assertEquals(offered(), client.latest(10))
        }
    }

    @Test fun rejectsReferenceIdentityMismatchEvenWhenObjectMatchesSource() = runBlocking<Unit> {
        val routes = fixture()
        routes[refPath(42)] = """{"ref":"refs/tags/v0.1.999","object":{"type":"commit","sha":"$to"}}"""
        server(routes).use { failure { client(it).changelog(10, offered()) } }
    }

    @Test fun fileInventoryValidatesEveryPathEvenAfterFindingSharedEvidence() = runBlocking<Unit> {
        val routes = fixture()
        routes[comparePath()] = comparison(listOf(commit(to, "fix(windows): Mixed commit")))
        routes[detailPath(to)] = files(to, listOf("shared/source.kt", "../invalid.cs"))
        server(routes).use { failure { client(it).changelog(10, offered()) } }
    }

    @Test fun cancellationStopsCompareSocketAndPropagatesInsteadOfReportingUnavailable() = runBlocking<Unit> {
        val routes = fixture()
        val reached = CompletableDeferred<Unit>()
        server(routes).use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == comparePath()) {
                        reached.complete(Unit)
                        return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
                    }
                    return routes[request.path]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
                }
            }
            val job = launch(Dispatchers.IO) { client(server).changelog(10, offered()) }
            withTimeout(3000) { reached.await() }
            withTimeout(3000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
    }

    @Test fun continuationAndUntrustedRedirectCannotTurnPartialHistoryIntoSuccess() = runBlocking<Unit> {
        for (redirect in listOf(false, true)) {
            val routes = fixture()
            server(routes).use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path == comparePath()) return if (redirect)
                            MockResponse().setResponseCode(302).setHeader("Location", "https://api.github.com/repos/attacker/music/commits/main")
                        else MockResponse().setBody(routes.getValue(comparePath())).setHeader("Link", "<https://evil.invalid/secret>; rel=\"next\"")
                        return routes[request.path]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
                    }
                }
                failure { client(server).changelog(10, offered()) }
                assertEquals(6, server.requestCount)
            }
        }
    }

    @Test fun usesInstalledAndOfferedSourcesAcrossSkippedPublicationsNotReleaseBody() = runBlocking<Unit> {
        server(fixture()).use { server ->
            val result = client(server).changelog(10, offered())
            assertEquals(from, result.fromSha)
            assertEquals(to, result.toSha)
            assertEquals(listOf("Restore playback"), result.fixes)
            assertTrue(result.improvements.isEmpty())
            assertEquals(1, result.count)
            assertEquals(listOf(UpdateChangeCommit(to, "[verified] fix(android): Restore playback")), result.commits)
            repeat(server.requestCount) {
                val request = server.takeRequest()
                assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
                assertFalse(request.path!!.contains("v0.1.30/release.json"))
            }
        }
    }
}
