package com.ospchat.shared.data.avatar

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileAvatarStoreTest {
    private lateinit var tmpDir: File
    private lateinit var store: FileAvatarStore

    @BeforeTest
    fun setup() {
        tmpDir = Files.createTempDirectory("avatar-store-test").toFile()
        store = FileAvatarStore(parentDir = tmpDir)
    }

    @AfterTest
    fun teardown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun selfRoundTrip() {
        val bytes = byteArrayOf(0x10, 0x20, 0x30)
        val path = store.writeSelf(bytes, hash = "hash1")
        assertTrue(store.selfExists("hash1"))
        assertEquals(path, store.selfPath("hash1"))
        assertTrue(File(path).name == "self-hash1.jpg")
    }

    @Test
    fun peerRoundTripIsolatedPerUuid() {
        store.writePeer(uuid = "u1", hash = "h1", bytes = byteArrayOf(1))
        store.writePeer(uuid = "u2", hash = "h1", bytes = byteArrayOf(2))
        assertTrue(store.peerExists("u1", "h1"))
        assertTrue(store.peerExists("u2", "h1"))
        assertFalse(store.peerExists("u3", "h1"))
    }

    @Test
    fun cleanupSelfKeepsCurrentHashOnly() {
        store.writeSelf(byteArrayOf(1), hash = "old1")
        store.writeSelf(byteArrayOf(2), hash = "old2")
        store.writeSelf(byteArrayOf(3), hash = "current")
        store.cleanupSelfExcept("current")
        assertFalse(store.selfExists("old1"))
        assertFalse(store.selfExists("old2"))
        assertTrue(store.selfExists("current"))
    }

    @Test
    fun cleanupSelfNullDeletesEverything() {
        store.writeSelf(byteArrayOf(1), hash = "h1")
        store.writeSelf(byteArrayOf(2), hash = "h2")
        store.cleanupSelfExcept(null)
        assertFalse(store.selfExists("h1"))
        assertFalse(store.selfExists("h2"))
    }

    @Test
    fun cleanupPeerIsScopedToOneUuid() {
        store.writePeer(uuid = "u1", hash = "old", bytes = byteArrayOf(1))
        store.writePeer(uuid = "u1", hash = "new", bytes = byteArrayOf(2))
        store.writePeer(uuid = "u2", hash = "old", bytes = byteArrayOf(3))
        store.cleanupPeerExcept(uuid = "u1", keepHash = "new")
        assertFalse(store.peerExists("u1", "old"))
        assertTrue(store.peerExists("u1", "new"))
        // u2's "old" untouched because cleanup is scoped per-uuid.
        assertTrue(store.peerExists("u2", "old"))
    }

    @Test
    fun acceptsUuidAndHexHash() {
        // Real wire inputs: UUID-shaped uuid, 64-char lowercase hex SHA-256.
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        val hash = "e".repeat(64)
        store.writePeer(uuid = uuid, hash = hash, bytes = byteArrayOf(9))
        assertTrue(store.peerExists(uuid, hash))
    }

    @Test
    fun rejectsPeerPathTraversal() {
        // Either field controlled by the peer (uuid via mDNS TXT, hash via
        // /v1/info) must not be able to escape the avatar root.
        assertFailsWith<IllegalArgumentException> {
            store.writePeer(uuid = "../escape", hash = "h", bytes = byteArrayOf(1))
        }
        assertFailsWith<IllegalArgumentException> {
            store.writePeer(uuid = "u", hash = "../escape", bytes = byteArrayOf(1))
        }
        assertFailsWith<IllegalArgumentException> {
            store.writePeer(uuid = "u/v", hash = "h", bytes = byteArrayOf(1))
        }
        assertFailsWith<IllegalArgumentException> {
            store.peerExists("../u", "h")
        }
    }

    @Test
    fun rejectsSelfPathTraversal() {
        assertFailsWith<IllegalArgumentException> {
            store.writeSelf(byteArrayOf(1), hash = "../escape")
        }
        assertFailsWith<IllegalArgumentException> { store.selfExists("../escape") }
        assertFailsWith<IllegalArgumentException> { store.readSelf("../escape") }
    }

    @Test
    fun rejectsEmptyAndControlChars() {
        assertFailsWith<IllegalArgumentException> {
            store.writePeer(uuid = "", hash = "h", bytes = byteArrayOf(1))
        }
        assertFailsWith<IllegalArgumentException> {
            store.writePeer(uuid = "u", hash = "", bytes = byteArrayOf(1))
        }
        assertFailsWith<IllegalArgumentException> {
            store.writePeer(uuid = "u\n", hash = "h", bytes = byteArrayOf(1))
        }
    }

    @Test
    fun cleanupPeerRejectsUnsafeUuid() {
        // Otherwise an attacker who got their malformed uuid into the upstream
        // sync could trigger us to listFiles() with an attacker-controlled prefix.
        assertFailsWith<IllegalArgumentException> {
            store.cleanupPeerExcept(uuid = "../u", keepHash = "h")
        }
    }
}
