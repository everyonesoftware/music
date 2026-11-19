package com.everyonesoftware.audio.playback

import com.everyonesoftware.audio.data.Connection
import com.everyonesoftware.audio.data.Credentials
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BrowseArtworkRegistryTest {
    private val account = Credentials(Connection("c", "Private", "https://secret.invalid", "private-user"), "password")

    @Test fun artworkTrustUsesActualUidPackagesAndPidNotClaimedUriIdentity() {
        var resolvedUid: Int? = null
        val checks = mutableListOf<Triple<String, Int, Int>>()
        val packages: (Int) -> Array<String>? = { uid -> resolvedUid = uid; arrayOf("untrusted", "trusted") }
        assertTrue(artworkCallerIsTrusted(22, 33, 11, packages) { pkg, pid, uid ->
            checks.add(Triple(pkg, pid, uid)); pkg == "trusted"
        })
        assertEquals(22, resolvedUid)
        assertEquals(listOf(Triple("untrusted", 33, 22), Triple("trusted", 33, 22)), checks)
        assertFalse(artworkCallerIsTrusted(22, 33, 11, { null }) { _, _, _ -> true })
        assertFalse(artworkCallerIsTrusted(22, 33, 11, packages) { _, _, _ -> false })
        assertTrue(artworkCallerIsTrusted(11, 33, 11, { fail("own UID needs no package claims"); null }) { _, _, _ -> false })
    }

    @Test fun everyCoverRemainsResolvableAfterLegacySizedCatalogAndRepeatedPages() {
        val registry = BrowseArtworkRegistry("app.artwork") { account }
        val firstPage = (0 until 100).map { registry.register(account, "cover-$it")!! }
        val rest = (100 until 512).map { registry.register(account, "cover-$it")!! }
        repeat(3) { (0 until 512).forEach { registry.register(account, "cover-$it") } }
        (firstPage + rest).forEachIndexed { index, uri ->
            assertEquals("cover-$index", registry.resolve(uri)?.coverArtId)
        }
        val entries = BrowseArtworkRegistry::class.java.getDeclaredField("entries").apply { isAccessible = true }
        assertEquals(256, (entries.get(registry) as Map<*, *>).size)
    }

    @Test fun loadingArtworkRechecksRevocationAndBoundsWithoutKeepingBytes() = runTest {
        var active: Credentials? = account
        val registry = BrowseArtworkRegistry("app.artwork") { active }
        val uri = registry.register(account, "cover")!!
        var reads = 0
        assertArrayEquals(byteArrayOf(1, 2), registry.read(uri) { reads++; byteArrayOf(1, 2) })
        assertEquals(1, reads)
        assertNull(registry.read(uri + "?forged") { fail("loaded forged URI"); null })
        assertNull(registry.read(uri) { ByteArray(2 * 1024 * 1024 + 1) })
        assertNull(registry.read(uri) { active = null; byteArrayOf(1) })
        active = account
        registry.clear()
        assertNull(registry.read(uri) { fail("loaded revoked URI"); null })
        val replacement = registry.register(account, "cover")!!
        assertNull(registry.resolve(uri))
        assertNull(registry.read(replacement) {
            registry.clear()
            registry.register(account, "cover")
            byteArrayOf(1)
        })
        assertNull(registry.register(account, "x".repeat(4097)))
        val maxCover = "界".repeat(4096)
        assertEquals(maxCover, registry.resolve(registry.register(account, maxCover)!!)!!.coverArtId)
        assertNull(registry.register(account, "https://private.invalid/cover?secret=1"))
    }

    @Test fun opaqueArtworkRequestsAreBoundedAndAccountScoped() = runTest {
        var active: Credentials? = account
        val registry = BrowseArtworkRegistry("app.artwork", capacity = 2) { active }
        val first = registry.register(account, "private-cover")!!
        assertTrue(first.startsWith("content://app.artwork/browse/"))
        for (secret in listOf("secret.invalid", "private-user", "password", "private-cover", "file:")) assertFalse(first.contains(secret))
        assertEquals(first, registry.register(account, "private-cover"))
        for (forged in listOf("file:///cover", first + "?x=1", first + "#fragment", first.replace("app.artwork", "other")))
            assertNull(registry.resolve(forged))
        assertEquals("private-cover", registry.resolve(first)!!.coverArtId)
        active = account.copy(password = "changed")
        assertNull(registry.resolve(first))
        assertNull(registry.register(account, "private-cover"))
        val second = registry.register(active!!, "two")!!
        val third = registry.register(active!!, "three")!!
        val fourth = registry.register(active!!, "four")!!
        // Eviction bounds reuse state, not the validity of already returned capabilities.
        assertNotNull(registry.resolve(second))
        val entries = BrowseArtworkRegistry::class.java.getDeclaredField("entries").apply { isAccessible = true }
        assertEquals(2, (entries.get(registry) as Map<*, *>).size)
        val tokenStart = second.indexOf("/browse/") + "/browse/".length
        val tampered = second.substring(0, tokenStart) + (if (second[tokenStart] == 'A') "B" else "A") + second.substring(tokenStart + 1)
        assertNull(registry.resolve(tampered))
        assertNull(BrowseArtworkRegistry("app.artwork") { active }.resolve(second))
        assertNotNull(registry.resolve(third))
        assertNotNull(registry.resolve(fourth))
        registry.clear()
        active = account
        assertNull(registry.resolve(first))
    }
}
