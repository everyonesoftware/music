package com.everyonesoftware.audio.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Private, account-scoped image bytes. Authentication URLs never enter this index. */
class ArtworkStore(private val directory: File, private val automaticLimit: Long = 50_000_000) {
    @Serializable private data class Entry(val key: String, val connectionId: String, val bytes: Long,
        val digest: String, val touched: Long = System.currentTimeMillis())
    @Serializable private data class Owner(val scope: String, val connectionId: String, val name: String, val keys: Set<String>)
    @Serializable private data class Disk(val entries: List<Entry> = emptyList(), val owners: List<Owner> = emptyList())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val catalog = File(directory, "artwork.json")
    private var disk = try {
        json.decodeFromString<Disk>(catalog.readText()).let { value ->
            value.copy(entries = value.entries.filter { validKey(it.key) && it.bytes in 1..2L * 1024 * 1024 && validKey(it.digest) },
                owners = value.owners.filter { validKey(it.scope) }.map { it.copy(keys = it.keys.filter(::validKey).toSet()) })
        }
    } catch (_: Exception) { Disk() }
    private fun key(connection: Connection, id: String): String {
        require(id.isNotBlank() && id.length <= 4096 && !Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(id))
        return OfflineIndex.key(connection, id, "artwork-v1")
    }
    private fun timestamp() = maxOf(System.currentTimeMillis(), (disk.entries.maxOfOrNull { it.touched } ?: 0) + 1)
    private fun scope(connection: Connection) = OfflineIndex.key(connection, "", "artwork-account-v1")
    private fun image(key: String) = File(directory, "$key.image")
    private fun pinned() = disk.owners.flatMap { it.keys }.toSet()
    @Synchronized fun pinnedKeys(): Set<String> = pinned()
    @Synchronized fun keys(): Set<String> = disk.entries.map { it.key }.toSet()
    @Synchronized fun syncOwners(connection: Connection, owners: Map<String, List<String>>) {
        val scope = scope(connection)
        val keep = disk.owners.filterNot { it.scope == scope }
        val next = disk.copy(owners = keep + owners.filterValues { it.isNotEmpty() }.map { (name, ids) ->
            Owner(scope, connection.id, name, ids.map { key(connection, it) }.toSet())
        })
        if (next == disk) return
        save(next); disk = next
    }
    @Synchronized fun replaceOwner(connection: Connection, owner: String, ids: List<String>) {
        require(owner.isNotBlank() && owner.length <= 4096)
        val scope = scope(connection)
        val next = disk.copy(owners = disk.owners.filterNot { it.scope == scope && it.name == owner } +
            if (ids.isEmpty()) emptyList() else listOf(Owner(scope, connection.id, owner, ids.map { key(connection, it) }.toSet())))
        save(next)
        disk = next
    }
    @Synchronized fun removeOwner(connection: Connection, owner: String) = replaceOwner(connection, owner, emptyList())
    @Synchronized fun removeConnection(connectionId: String) {
        val removed = disk.entries.filter { it.connectionId == connectionId }
        val next = disk.copy(entries = disk.entries - removed.toSet(), owners = disk.owners.filterNot { it.connectionId == connectionId })
        save(next); disk = next
        removed.forEach { image(it.key).delete() }
    }
    @Synchronized fun clearAutomatic() {
        val pinned = pinned()
        val remove = disk.entries.filter { it.key !in pinned }
        val next = disk.copy(entries = disk.entries - remove.toSet())
        save(next)
        disk = next
        remove.forEach { image(it.key).delete() }
    }
    @Synchronized fun usage(): Pair<Long, Long> {
        val pinned = pinned()
        return disk.entries.filter { it.key !in pinned }.sumOf { it.bytes } to
            disk.entries.filter { it.key in pinned }.sumOf { it.bytes }
    }
    private fun trim() {
        val pinned = pinned()
        var bytes = disk.entries.filter { it.key !in pinned }.sumOf { it.bytes }
        val removed = mutableSetOf<Entry>()
        for (entry in disk.entries.filter { it.key !in pinned }.sortedBy { it.touched }) {
            if (bytes <= automaticLimit) break
            removed.add(entry); bytes -= entry.bytes
        }
        if (removed.isEmpty()) return
        val next = disk.copy(entries = disk.entries - removed)
        save(next); disk = next
        removed.forEach { image(it.key).delete() }
    }
    @Synchronized fun put(connection: Connection, id: String, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= 2 * 1024 * 1024)
        val key = key(connection, id)
        directory.mkdirs()
        val temporary = File(directory, "$key.tmp")
        temporary.outputStream().use { it.write(bytes); it.fd.sync() }
        Files.move(temporary.toPath(), image(key).toPath(), StandardCopyOption.REPLACE_EXISTING)
        disk = disk.copy(entries = disk.entries.filterNot { it.key == key } + Entry(key, connection.id, bytes.size.toLong(), digest(bytes), timestamp()))
        save(disk)
        trim()
    }
    @Synchronized fun read(connection: Connection, id: String): ByteArray? {
        val key = key(connection, id)
        val entry = disk.entries.firstOrNull { it.key == key } ?: return null
        if (entry.bytes !in 1..2L * 1024 * 1024 || image(key).length() != entry.bytes) return null
        val bytes = try { image(key).readBytes() } catch (_: Exception) { return null }
        if (bytes.size.toLong() != entry.bytes || digest(bytes) != entry.digest) return null
        disk = disk.copy(entries = disk.entries.map { if (it.key == key) it.copy(touched = timestamp()) else it })
        // Readable saved artwork must remain usable when recency cannot be persisted.
        runCatching { save(disk) }
        return bytes
    }
    @Synchronized fun file(connection: Connection, id: String): File? = if (read(connection, id) != null) image(key(connection, id)) else null
    private fun save(value: Disk) {
        directory.mkdirs()
        val temporary = File(directory, "artwork.tmp")
        temporary.outputStream().use { it.write(json.encodeToString(value).toByteArray()); it.fd.sync() }
        Files.move(temporary.toPath(), catalog.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    companion object {
        private fun validKey(value: String) = Regex("[0-9a-f]{64}").matches(value)
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
