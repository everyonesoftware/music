package com.everyonesoftware.audio.playback

import com.everyonesoftware.audio.data.ListeningSession

/** Service-owned visit evidence. Readiness/selection/paused restore never prove a start. */
internal class PlaybackHistory {
    private var owner: Pair<String, String>? = null
    private var active: Int? = null
    private var started = false
    private var visits = emptyList<Int>()

    fun observe(session: ListeningSession, index: Int, isPlaying: Boolean, repeat: Boolean = false, ended: Boolean = false): List<Int> {
        val nextOwner = session.connectionId to session.queueId
        if (owner != nextOwner) {
            owner = nextOwner
            active = null
            started = false
            visits = session.played.filter { it in session.occurrences }.takeLast(MAX_VISITS)
        }
        val occurrence = session.occurrences.getOrNull(index)
        if (active != occurrence || repeat || ended) {
            if (started) active?.let { visits = (visits + it).takeLast(MAX_VISITS) }
            active = occurrence
            started = false
        }
        if (isPlaying && !ended && occurrence != null) started = true
        return visits
    }

    companion object { const val MAX_VISITS = 1000 }
}
