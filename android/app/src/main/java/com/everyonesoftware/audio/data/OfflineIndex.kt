package com.everyonesoftware.audio.data

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class OfflineSettings(val autoSave: Boolean = true, val limitBytes: Long = 2_000_000_000,
    val prefetch: Int = 3, val mobilePrefetch: Boolean = false, val offline: Boolean = false)

@Serializable
data class OfflineEntry(val key: String, val connectionId: String, val song: Song,
    val pinned: Boolean = false, val touched: Long = System.currentTimeMillis(), val playedAt: Long? = null,
    val owners: Set<String> = emptySet())

@Serializable
data class OfflineCollection(val kind: String, val id: String, val name: String,
    val artist: String = "", val year: Int = 0, val coverArtId: String? = null, val songs: List<Song>) {
    init { require(kind in setOf("album", "playlist") && id.isNotBlank()) }
    val owner: String get() = "collection:$kind:$id"
    fun availableCount(complete: (String) -> Boolean): Int = songs.count { complete(it.id) }
    fun playableSongs(complete: (String) -> Boolean): List<Song> {
        require(songs.isNotEmpty()) { "This collection has no songs." }
        require(songs.all { complete(it.id) }) { "This collection is not fully downloaded. Connect and finish downloading before playing it offline." }
        return songs
    }
}

/** Atomic unsigned metadata and pin ownership, scoped by server and account identity. */
class OfflineIndex(private val directory: File) {
    @Serializable private data class SavedCollection(val account: String, val connectionId: String, val value: OfflineCollection)
    @Serializable private data class Disk(val settings: OfflineSettings = OfflineSettings(),
        val entries: List<OfflineEntry> = emptyList(), val collections: List<SavedCollection> = emptyList())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(directory, "catalog.json")
    private var disk = try { json.decodeFromString<Disk>(file.readText()) } catch (_: Exception) { Disk() }
    var settings: OfflineSettings
        @Synchronized get() = disk.settings
        @Synchronized set(value) {
            require(value.limitBytes >= 1_000_000 && value.prefetch in listOf(0, 1, 3, 5, 10))
            commit(disk.copy(settings = value))
        }
    private fun owners(entry: OfflineEntry): Set<String> = entry.owners.ifEmpty {
        if (entry.pinned) setOf("song:${entry.song.id}") else emptySet()
    }
    @Synchronized fun entries(): List<OfflineEntry> = disk.entries.toList()
    @Synchronized fun collections(connection: Connection): List<OfflineCollection> =
        disk.collections.filter { it.account == account(connection) }.map { it.value }
    @Synchronized fun saveCollection(connection: Connection, collection: OfflineCollection) {
        val account = account(connection)
        val old = disk.collections.firstOrNull { it.account == account && it.value.owner == collection.owner }
        val oldKeys = old?.value?.songs.orEmpty().map { key(connection, it.id) }.toSet()
        val updated = disk.entries.associateBy { it.key }.toMutableMap()
        oldKeys.forEach { key -> updated[key]?.let { entry ->
            val remaining = owners(entry) - collection.owner
            updated[key] = entry.copy(pinned = remaining.isNotEmpty(), owners = remaining)
        } }
        collection.songs.forEach { song ->
            val key = key(connection, song.id)
            val entry = updated[key] ?: OfflineEntry(key, connection.id, song)
            updated[key] = entry.copy(song = song, pinned = true, owners = owners(entry) + collection.owner)
        }
        commit(disk.copy(entries = updated.values.toList(), collections = disk.collections.filterNot {
            it.account == account && it.value.owner == collection.owner
        } + SavedCollection(account, connection.id, collection)))
    }
    @Synchronized fun removeCollection(connection: Connection, kind: String, id: String) {
        val owner = "collection:$kind:$id"
        val snapshot = collections(connection).firstOrNull { it.owner == owner } ?: return
        val keys = snapshot.songs.map { key(connection, it.id) }.toSet()
        commit(disk.copy(collections = disk.collections.filterNot { it.account == account(connection) && it.value.owner == owner },
            entries = disk.entries.map { entry -> if (entry.key !in keys) entry else {
                val remaining = owners(entry) - owner
                entry.copy(owners = remaining, pinned = remaining.isNotEmpty())
            } }))
    }
    @Synchronized fun remember(key: String, connectionId: String, song: Song, pinned: Boolean = false) {
        val old = disk.entries.firstOrNull { it.key == key }
        val owners = old?.let(::owners).orEmpty() + if (pinned) setOf("song:${song.id}") else emptySet()
        commit(disk.copy(entries = disk.entries.filterNot { it.key == key } +
            OfflineEntry(key, connectionId, song, owners.isNotEmpty(), playedAt = old?.playedAt, owners = owners)))
    }
    @Synchronized fun markPlayed(key: String) {
        commit(disk.copy(entries = disk.entries.map { if (it.key == key) it.copy(playedAt = System.currentTimeMillis()) else it }))
    }
    /** Individual removal releases only the explicit song owner, never a collection's pin. */
    @Synchronized fun unpin(key: String) {
        commit(disk.copy(entries = disk.entries.map { entry -> if (entry.key != key) entry else {
            val remaining = owners(entry) - "song:${entry.song.id}"
            entry.copy(pinned = remaining.isNotEmpty(), owners = remaining)
        } }))
    }
    @Synchronized fun remove(keys: Set<String>) { commit(disk.copy(entries = disk.entries.filterNot { it.key in keys })) }
    @Synchronized fun removeConnection(id: String) {
        commit(disk.copy(entries = disk.entries.filterNot { it.connectionId == id }, collections = disk.collections.filterNot { it.connectionId == id }))
    }
    private fun commit(next: Disk) {
        directory.mkdirs()
        val temporary = File(directory, "catalog.tmp")
        temporary.outputStream().use { it.write(json.encodeToString(next).toByteArray()); it.fd.sync() }
        check(temporary.renameTo(file)) { "Cannot save offline music metadata." }
        disk = next
    }
    companion object {
        private fun account(connection: Connection) = key(connection, "", "collection-account-v1")
        fun key(connection: Connection, songId: String, representation: String = "server-default-v1"): String {
            val fields = listOf(connection.id, connection.baseUrl, connection.username, songId, representation)
            val bytes = fields.joinToString("") { "${it.toByteArray().size}:$it" }.toByteArray()
            return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }
}
