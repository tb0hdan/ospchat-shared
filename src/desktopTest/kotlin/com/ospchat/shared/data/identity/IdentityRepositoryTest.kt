package com.ospchat.shared.data.identity

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IdentityRepositoryTest {
    private lateinit var tmpDir: File

    @BeforeTest
    fun setup() {
        tmpDir = Files.createTempDirectory("ospchat-identity-test").toFile()
    }

    @AfterTest
    fun teardown() {
        tmpDir.deleteRecursively()
    }

    private fun newRepo(filename: String = "identity.preferences_pb"): IdentityRepository {
        val store =
            PreferenceDataStoreFactory.create(
                produceFile = { File(tmpDir, filename) },
            )
        return IdentityRepository(store)
    }

    @Test
    fun nicknameRoundTrip() =
        runTest {
            val repo = newRepo()
            assertNull(repo.nicknameFlow.first())
            repo.setNickname("Alice")
            assertEquals("Alice", repo.nicknameFlow.first())
        }

    @Test
    fun nicknameTrimmedAndRejectsBlank() =
        runTest {
            val repo = newRepo()
            repo.setNickname("  Bob  ")
            assertEquals("Bob", repo.nicknameFlow.first())
            try {
                repo.setNickname("   ")
                error("blank nickname should have thrown")
            } catch (expected: IllegalArgumentException) {
                // ok
            }
        }

    @Test
    fun ensureUuidIsStable() =
        runTest {
            val repo = newRepo()
            val first = repo.ensureUuid()
            assertNotNull(first)
            assertEquals(first, repo.ensureUuid())
            assertEquals(first, repo.uuidFlow.first())
        }

    @Test
    fun avatarHashRoundTripAndClear() =
        runTest {
            val repo = newRepo()
            assertNull(repo.currentAvatarHash())
            repo.setAvatarHash("deadbeef")
            assertEquals("deadbeef", repo.currentAvatarHash())
            repo.setAvatarHash(null)
            assertNull(repo.currentAvatarHash())
        }
}
