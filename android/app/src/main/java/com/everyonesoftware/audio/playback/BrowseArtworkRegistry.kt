package com.everyonesoftware.audio.playback

import com.everyonesoftware.audio.data.Credentials
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Account/process-scoped encrypted capabilities, resolved only by the trusted artwork provider.
 * The bounded LRU only reuses tokens; eviction never revokes a returned page's artwork.
 * One memory-only key authenticates self-contained cover IDs, including legacy unpaged catalogs.
 * No bytes are fetched here. Account changes and clear rotate the key, revoking all old tokens.
 */
internal class BrowseArtworkRegistry(
    private val authority: String,
    private val capacity: Int = 256,
    private val activeCredentials: () -> Credentials?,
) {
    private companion object {
        const val MAX_COVER_CHARS = 4096
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        const val MAX_ENCRYPTED_BYTES = MAX_COVER_CHARS * 4 + NONCE_BYTES + TAG_BYTES
        const val MAX_TOKEN_CHARS = ((MAX_ENCRYPTED_BYTES + 2) / 3) * 4
    }

    data class Request(val credentials: Credentials, val coverArtId: String)
    private val entries = LinkedHashMap<String, String>(capacity, 0.75f, true)
    private var owner: Credentials? = null
    private var key: SecretKey? = null
    private val random = SecureRandom()
    private val prefix = "content://$authority/browse/"
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    init { require(capacity > 0) }

    @Synchronized fun register(credentials: Credentials, coverArtId: String?): String? {
        revokeInactiveOwner()
        if (credentials != activeCredentials() || coverArtId.isNullOrBlank() || coverArtId.length > MAX_COVER_CHARS ||
            Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(coverArtId)) return null
        if (owner != credentials) {
            clear()
            owner = credentials
            key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
        entries[coverArtId]?.let { return it }
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(prefix.toByteArray(Charsets.UTF_8))
        val uri = "$prefix${encoder.encodeToString(nonce + cipher.doFinal(coverArtId.toByteArray(Charsets.UTF_8)))}.jpg"
        entries[coverArtId] = uri
        if (entries.size > capacity) entries.entries.iterator().apply { next(); remove() }
        return uri
    }

    @Synchronized fun resolve(uri: String): Request? {
        revokeInactiveOwner()
        val credentials = owner ?: return null
        val secret = key ?: return null
        // Bound work and accept only the exact canonical URI form issued here.
        if (uri.length > prefix.length + MAX_TOKEN_CHARS + 4 || !uri.startsWith(prefix) || !uri.endsWith(".jpg")) return null
        val token = uri.substring(prefix.length, uri.length - 4)
        if (!Regex("[A-Za-z0-9_-]+").matches(token)) return null
        return try {
            val encrypted = Base64.getUrlDecoder().decode(token)
            if (encrypted.size !in (NONCE_BYTES + TAG_BYTES + 1)..MAX_ENCRYPTED_BYTES || encoder.encodeToString(encrypted) != token) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, encrypted.copyOfRange(0, NONCE_BYTES)))
            cipher.updateAAD(prefix.toByteArray(Charsets.UTF_8))
            Request(credentials, String(cipher.doFinal(encrypted, NONCE_BYTES, encrypted.size - NONCE_BYTES), Charsets.UTF_8))
        } catch (_: GeneralSecurityException) { null }
        catch (_: IllegalArgumentException) { null }
    }

    private fun revokeInactiveOwner() {
        if (owner != null && owner != activeCredentials()) clear()
    }

    /** Called by the provider's pipe worker, never while producing a browse listing. */
    suspend fun read(uri: String, load: suspend (Request) -> ByteArray?): ByteArray? {
        val request = resolve(uri) ?: return null
        val bytes = load(request) ?: return null
        if (bytes.isEmpty() || bytes.size > 2 * 1024 * 1024 || resolve(uri) != request) return null
        return bytes
    }

    @Synchronized fun clear() { entries.clear(); owner = null; key = null }
}
