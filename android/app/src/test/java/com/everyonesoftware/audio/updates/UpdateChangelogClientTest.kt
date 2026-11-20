package com.everyonesoftware.audio.updates

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class UpdateChangelogClientTest {
    private val from = "a".repeat(40)
    private val middle = "d".repeat(40)
    private val to = "b".repeat(40)
    private val hash = "c".repeat(64)
    private fun base(count: Int) = "https://github.com/everyonesoftware/music/releases/download/v0.1.$count/"
    private fun publication(count: Int = 42) = """[{"tag_name":"v0.1.$count","draft":false,"prerelease":false,"published_at":"2026-01-01","body":"Current downloads | APK | stale notes","assets":[{"name":"release.json","browser_download_url":"${base(count)}release.json"},{"name":"Music-0.1.$count.apk","browser_download_url":"${base(count)}Music-0.1.$count.apk","size":18}]}]"""
    private fun manifest(history: String, count: Int = 42) = """{"count":$count,"version":"0.1.$count","tag":"v0.1.$count","sha":"$to","apk":"Music-0.1.$count.apk","bytes":18,"sha256":"$hash","changelog":$history}"""
    private fun history() = """{"schema":1,"platform":"android","head":{"version":"0.1.42","sha":"$to"},"releases":[{"version":"0.1.40","sha":"$from","commits":[]},{"version":"0.1.41","sha":"$middle","commits":[{"sha":"$middle","subject":"[verified] feat(shared): Shared transport","kind":"improvement","text":"Shared transport"}]},{"version":"0.1.42","sha":"$to","commits":[{"sha":"$to","subject":"fix(android): Restore playback","kind":"fix","text":"Restore playback"}]}]}"""

    private fun offered(history: String?) = UpdateRelease(42, "0.1.42", "Music-0.1.42.apk", 18, hash,
        base(42) + "Music-0.1.42.apk", sourceSha = to,
        changelog = history?.let { kotlinx.serialization.json.Json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject })

    @Test fun malformedHistoryIsUnavailableRatherThanAZeroChangeClaim() {
        val good = history()
        val invalid = listOf(
            good.replace("\"schema\":1", "\"schema\":2"),
            good.replace("\"schema\":1", "\"schema\":\"1\""),
            good.replace("\"platform\":\"android\"", "\"platform\":\"windows\""),
            good.replace("\"platform\":\"android\"", "\"platform\":1"),
            good.replaceFirst("\"version\":\"0.1.42\"", "\"version\":\"0.1.43\""),
            good.replaceFirst(to, from),
            good.replace("0.1.41", "0.1.40"),
            good.replace("0.1.41", "0.1.09"),
            good.replace("0.1.41", "0.1.43"),
            good.replace("0.1.41", "0.1.2100000001"),
            good.replace("\"commits\":[]", "\"commits\":[{\"sha\":\"$from\",\"subject\":\"baseline\",\"kind\":null,\"text\":null}]"),
            good.replace(middle, from),
            good.replace("\"sha\":\"$to\",\"subject\"", "\"sha\":\"$middle\",\"subject\""),
            good.replace("\"sha\":\"$middle\",\"subject\"", "\"sha\":\"${"e".repeat(40)}\",\"subject\""),
            good.replace(middle, "D".repeat(40)),
            good.replace("\"kind\":\"fix\"", "\"kind\":\"feature\""),
            good.replace("\"kind\":\"fix\"", "\"kind\":null"),
            good.replace("\"text\":\"Restore playback\"", "\"text\":null"),
            good.replace("\"text\":\"Restore playback\"", "\"text\":\"  \""),
            good.replace("\"text\":\"Restore playback\"", "\"text\":\"First\\nsecond\""),
            good.replace("\"text\":\"Restore playback\"", "\"text\":\"First\\rsecond\""),
            good.replace("\"text\":\"Restore playback\"", "\"text\":\"First\\u0000second\""),
            good.replace("fix(android): Restore playback", "fix: first\\nsecond"),
            good.replace("\"kind\":\"fix\",", ""),
            good.replace("\"text\":\"Restore playback\"", "\"text\":false"),
        )
        invalid.forEachIndexed { index, raw -> assertNull("Invalid fixture $index", offered(raw).localChangelog(40)) }
        assertNull(offered(null).localChangelog(40))
        assertNull(offered(good).localChangelog(11))
        assertNull(offered(good).localChangelog(0))
        assertNull(offered(good).localChangelog(42))
        assertNull(offered(good).copy(sourceSha = from).localChangelog(40))
    }

    @Test fun emptyOriginalFirstLineIsRetainedButNeverFabricatesASummary() {
        val raw = history().replace("fix(android): Restore playback", "")
            .replace("\"kind\":\"fix\",\"text\":\"Restore playback\"", "\"kind\":null,\"text\":null")
        val changes = requireNotNull(offered(raw).localChangelog(41))
        assertEquals(listOf(UpdateChangeCommit(to, "")), changes.commits)
        assertEquals(0, changes.count)
    }

    @Test fun producerOriginalCarriageReturnAndLongSubjectsArePreservedVerbatim() {
        for (subject in listOf("Original\rsubject", "x".repeat(5000), "\u0000")) {
            val raw = history().replace("\"fix(android): Restore playback\"", kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<String>(), subject))
                .replace("\"kind\":\"fix\",\"text\":\"Restore playback\"", "\"kind\":null,\"text\":null")
            val changes = requireNotNull(offered(raw).localChangelog(41))
            assertEquals(subject, changes.commits.single().subject)
            assertEquals(0, changes.count)
        }
    }

    @Test fun normalizedProducerBulletsPreserveOriginalControlCharactersInDetails() {
        for (control in listOf('\r', '\u0000')) {
            val subject = "fix: Original${control}subject"
            val raw = history().replace("\"fix(android): Restore playback\"", kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer<String>(), subject))
                .replace("\"text\":\"Restore playback\"", "\"text\":\"Original subject\"")
            val changes = requireNotNull(offered(raw).localChangelog(41))
            assertEquals(subject, changes.commits.single().subject)
            assertEquals(listOf("Original subject"), changes.fixes)
        }
    }

    @Test fun longProducerSummaryIsAcceptedWithinOverallManifestLimit() {
        val text = "A".repeat(5000)
        val raw = history().replace("fix(android): Restore playback", "fix(android): $text")
            .replace("\"text\":\"Restore playback\"", "\"text\":\"$text\"")
        val changes = requireNotNull(offered(raw).localChangelog(41))
        assertEquals(listOf(text), changes.fixes)
        assertEquals("fix(android): $text", changes.commits.single().subject)
    }

    @Test fun deletedNonfinalCommitCannotMasqueradeAsCompleteHistory() {
        val extra = """{"sha":"${"e".repeat(40)}","subject":"Original nonfinal commit","kind":null,"text":null},"""
        val endpoint = """{"sha":"$to","subject":"fix(android): Restore playback"""
        val complete = history().replace("0.1.42", "0.1.43").replace(endpoint, extra + endpoint)
        val offered = offered(complete).copy(count = 43, version = "0.1.43")
        assertNotNull(offered.localChangelog(40))
        assertNull(offered.copy(changelog = kotlinx.serialization.json.Json.parseToJsonElement(complete.replace(extra, "")) as kotlinx.serialization.json.JsonObject).localChangelog(40))
    }

    @Test fun boundedHistoryRejectsExcessiveReleaseArraysAndSerializedSize() {
        fun manyReleases(size: Int): String {
            val releases = (1..size).joinToString(",") { number ->
                val sha = number.toString(16).padStart(40, '0')
                val commits = if (number == 1) "" else """{"sha":"$sha","subject":"Original","kind":null,"text":null}"""
                """{"version":"0.1.$number","sha":"$sha","commits":[$commits]}"""
            }
            return """{"schema":1,"platform":"android","head":{"version":"0.1.$size","sha":"${size.toString(16).padStart(40, '0')}"},"releases":[$releases]}"""
        }
        val large = offered(manyReleases(4097)).copy(count = 4097, version = "0.1.4097", sourceSha = 4097.toString(16).padStart(40, '0'))
        assertNull(large.localChangelog(1))
        val commits = (1..300).joinToString(",") { index ->
            val sha = if (index == 300) to else index.toString(16).padStart(40, '0')
            """{"sha":"$sha","subject":"${"x".repeat(4096)}","kind":null,"text":null}"""
        }
        val raw = """{"schema":1,"platform":"android","head":{"version":"0.1.42","sha":"$to"},"releases":[{"version":"0.1.40","sha":"$from","commits":[]},{"version":"0.1.42","sha":"$to","commits":[$commits]}]}"""
        assertNull(offered(raw.replace("0.1.42", "0.1.340")).copy(count = 340, version = "0.1.340").localChangelog(40))
    }

    @Test fun optionalHistoryFailuresNeverInvalidateApkDiscovery() = runBlocking {
        for (raw in listOf("null", "[]", "{}", history().replace("\"schema\":1", "\"schema\":9"))) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(publication()))
                server.enqueue(MockResponse().setBody(manifest(raw)))
                server.start()
                val client = UpdateClient(UpdateTestConfig(server.url("/")))
                val offered = requireNotNull(client.latest(40))
                assertEquals(42, offered.count)
                assertNull(offered.localChangelog(40))
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun nullSummaryKeepsOriginalDetailsAndInstalledSelectionExcludesEarlierSegments() {
        val raw = history().replace("\"kind\":\"fix\",\"text\":\"Restore playback\"", "\"kind\":null,\"text\":null")
        val changes = requireNotNull(offered(raw).localChangelog(41))
        assertEquals(middle, changes.fromSha)
        assertEquals(0, changes.count)
        assertEquals(listOf(UpdateChangeCommit(to, "fix(android): Restore playback")), changes.commits)
    }

    @Test fun discoveryAcceptsHistoryLargerThanLegacy64KiBLimit() = runBlocking {
        val commits = (1..500).joinToString(",") { index ->
            val sha = if (index == 500) to else index.toString(16).padStart(40, '0')
            """{"sha":"$sha","subject":"feat(shared): Preserve original subject $index","kind":"improvement","text":"Change $index"}"""
        }
        val raw = """{"schema":1,"platform":"android","head":{"version":"0.1.540","sha":"$to"},"releases":[{"version":"0.1.40","sha":"$from","commits":[]},{"version":"0.1.540","sha":"$to","commits":[$commits]}]}"""
        assertTrue(manifest(raw, 540).toByteArray().size > 65536)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(publication(540)))
            server.enqueue(MockResponse().setBody(manifest(raw, 540)))
            server.start()
            val client = UpdateClient(UpdateTestConfig(server.url("/")))
            assertEquals(500, client.changelog(40, requireNotNull(client.latest(40))).count)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun discoveryCachesCompleteSkippedReleaseRangeWithoutFurtherRequests() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(publication()))
            server.enqueue(MockResponse().setBody(manifest(history())))
            // A legacy reconstruction attempt must fail promptly, not hang this test.
            server.enqueue(MockResponse().setResponseCode(404))
            server.start()
            val client = UpdateClient(UpdateTestConfig(server.url("/")))
            val offered = requireNotNull(client.latest(40))
            val changes = client.changelog(40, offered)
            assertEquals(from, changes.fromSha)
            assertEquals(to, changes.toSha)
            assertEquals(listOf("Shared transport"), changes.improvements)
            assertEquals(listOf("Restore playback"), changes.fixes)
            assertEquals(listOf(middle, to), changes.commits.map { it.sha })
            assertEquals(2, server.requestCount)
            assertEquals(changes, client.changelog(40, offered))
            assertEquals(2, server.requestCount)
            assertEquals("", offered.notes)
            repeat(2) {
                val request = server.takeRequest()
                assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
            }
        }
    }
}
