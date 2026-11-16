package com.everyonesoftware.audio.data

object OfflinePlan {
    fun songs(queue: List<Song>, index: Int, prefetch: Int, partials: List<OfflineEntry>, connectionId: String,
        repeatMode: Int = 0): List<Song> {
        val current = queue.getOrNull(index) ?: return emptyList()
        if (repeatMode == 1) return listOf(current)
        val upcoming = (1..prefetch).mapNotNull { offset ->
            val position = index + offset
            if (position < queue.size) queue[position] else if (repeatMode == 2) queue[position % queue.size] else null
        }
        return (listOf(current) + upcoming.take(1) +
            partials.filter { it.connectionId == connectionId }.sortedByDescending { it.touched }.take(3).map { it.song } +
            upcoming.drop(1)).distinctBy { it.id }
    }
}
