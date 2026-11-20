package com.everyonesoftware.audio.updates

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
