package com.everyonesoftware.audio.updates

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/** Exact original history is retained even when a commit is omitted from summary bullets. */
data class UpdateChangeCommit(val sha: String, val subject: String)

data class UpdateChangelog(
    val fromSha: String,
    val toSha: String,
    val improvements: List<String>,
    val fixes: List<String>,
    val commits: List<UpdateChangeCommit>,
) {
    val count: Int get() = improvements.size + fixes.size
}

/** Local-only range selection; malformed optional history never rejects the APK offer. */
internal fun UpdateRelease.localChangelog(installedCode: Long): UpdateChangelog? = try {
    val raw = requireNotNull(changelog)
    require(raw.toString().toByteArray(Charsets.UTF_8).size <= 1024 * 1024)
    val schema = raw["schema"] as? kotlinx.serialization.json.JsonPrimitive
    require(schema != null && !schema.isString && schema.content == "1")
    val history = Json.decodeFromJsonElement<ManifestChangelog>(raw)
    require(history.schema == 1 && history.platform == "android")
    require(installedCode in 1 until count.toLong() && version == "0.1.$count")
    require(history.head.version == version && history.head.sha == sourceSha && validSha(sourceSha))
    require(history.releases.size in 1..4096)
    require(history.releases.sumOf { it.commits.size.toLong() } <= 10_000)
    var previousVersion = 0
    val seen = mutableSetOf<String>()
    for ((index, entry) in history.releases.withIndex()) {
        val number = releaseNumber(entry.version)
        require(number > previousVersion && validSha(entry.sha))
        val expectedCommits = number - previousVersion
        previousVersion = number
        if (index == 0) {
            require(entry.commits.isEmpty())
            seen.add(entry.sha)
        } else {
            require(entry.commits.size == expectedCommits && entry.commits.last().sha == entry.sha)
        }
        for (commit in entry.commits) {
            require(validSha(commit.sha) && seen.add(commit.sha))
            require(validSubject(commit.subject))
            require(commit.kind in listOf(null, "improvement", "fix"))
            require(if (commit.kind == null) commit.text == null else commit.text?.let(::validLine) == true)
        }
    }
    require(history.releases.last().version == history.head.version && history.releases.last().sha == history.head.sha)
    val installed = history.releases.indexOfFirst { it.version == "0.1.$installedCode" }
    require(installed >= 0 && installed < history.releases.lastIndex)
    val commits = history.releases.drop(installed + 1).flatMap { it.commits }
    UpdateChangelog(history.releases[installed].sha, history.head.sha,
        commits.filter { it.kind == "improvement" }.map { requireNotNull(it.text) },
        commits.filter { it.kind == "fix" }.map { requireNotNull(it.text) },
        commits.map { UpdateChangeCommit(it.sha, it.subject) })
} catch (_: Exception) { null }

private val fullSha = Regex("[0-9a-f]{40}")
private fun validSha(value: String) = fullSha.matches(value)
private fun validSubject(value: String) = '\n' !in value
private fun validLine(value: String) = value.isNotBlank() &&
    value.none { it == '\n' || it == '\r' || it == '\u0000' }
private fun releaseNumber(version: String): Int {
    require(Regex("0\\.1\\.[1-9][0-9]{0,9}").matches(version))
    return requireNotNull(version.removePrefix("0.1.").toIntOrNull()).also { require(it <= 2_100_000_000) }
}

@Serializable private data class ManifestChangelog(val schema: Int, val platform: String,
    val head: ChangelogHead, val releases: List<ChangelogRelease>)
@Serializable private data class ChangelogHead(val version: String, val sha: String)
@Serializable private data class ChangelogRelease(val version: String, val sha: String, val commits: List<ChangelogCommit>)
@Serializable private data class ChangelogCommit(val sha: String, val subject: String, val kind: String?, val text: String?)
