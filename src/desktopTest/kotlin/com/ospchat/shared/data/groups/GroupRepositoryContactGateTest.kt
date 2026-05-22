package com.ospchat.shared.data.groups

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ospchat.shared.data.db.OSPCHAT_MIGRATIONS
import com.ospchat.shared.data.db.OspChatDatabase
import com.ospchat.shared.data.identity.IdentityRepository
import com.ospchat.shared.data.peers.PeerEntity
import com.ospchat.shared.net.dto.GroupMemberDto
import com.ospchat.shared.net.dto.GroupSnapshotDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Covers the contact gate added to [GroupRepository.applySnapshot] for
 * D7. A new group from a peer NOT in the user's contacts must be
 * rejected; once the peer is added as a contact, the same snapshot
 * succeeds. Updates to ALREADY-known groups still flow through (the
 * gate only fires on first sighting).
 */
class GroupRepositoryContactGateTest {
    private lateinit var tmpDir: File
    private lateinit var db: OspChatDatabase
    private lateinit var repo: GroupRepository

    private val strangerUuid = "stranger-uuid"
    private val groupId = "g-stranger"
    private val snapshot =
        GroupSnapshotDto(
            id = groupId,
            name = "Stranger's group",
            kind = "CHAT",
            creatorUuid = strangerUuid,
            createdAt = 1L,
            membershipUpdatedAt = 1L,
            members = listOf(GroupMemberDto(uuid = strangerUuid, nickname = "Stranger")),
        )

    @BeforeTest
    fun setup() {
        tmpDir = Files.createTempDirectory("group-contact-gate-test").toFile()
        db =
            Room
                .databaseBuilder<OspChatDatabase>(name = File(tmpDir, "ospchat.db").absolutePath)
                .addMigrations(*OSPCHAT_MIGRATIONS)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        val store =
            PreferenceDataStoreFactory.create(
                produceFile = { File(tmpDir, "identity.preferences_pb") },
            )
        repo =
            GroupRepository(
                groupDao = db.groupDao(),
                groupMessageDao = db.groupMessageDao(),
                peerDao = db.peerDao(),
                identityRepository = IdentityRepository(store),
            )
    }

    @AfterTest
    fun teardown() {
        db.close()
        tmpDir.deleteRecursively()
    }

    @Test
    fun rejectsFirstSightingFromNonContact() =
        runTest {
            db.peerDao().upsert(
                PeerEntity(
                    uuid = strangerUuid,
                    nickname = "Stranger",
                    lastHost = "10.0.0.99",
                    lastPort = 8080,
                    firstSeenAt = 0,
                    lastSeenAt = 0,
                    isContact = false,
                ),
            )
            repo.applySnapshot(fromUuid = strangerUuid, snapshot = snapshot)
            assertNull(db.groupDao().findById(groupId), "non-contact group must be rejected")
        }

    @Test
    fun acceptsFirstSightingFromContact() =
        runTest {
            db.peerDao().upsert(
                PeerEntity(
                    uuid = strangerUuid,
                    nickname = "Friend",
                    lastHost = "10.0.0.5",
                    lastPort = 8080,
                    firstSeenAt = 0,
                    lastSeenAt = 0,
                    isContact = true,
                ),
            )
            repo.applySnapshot(fromUuid = strangerUuid, snapshot = snapshot)
            assertNotNull(db.groupDao().findById(groupId))
        }

    @Test
    fun rejectsFirstSightingFromUnknownPeer() =
        runTest {
            // Peer not in peers table at all (e.g. inserted via group invite
            // path before discovery saw them) — must be rejected by the gate.
            repo.applySnapshot(fromUuid = "ghost-uuid", snapshot = snapshot.copy(creatorUuid = "ghost-uuid"))
            assertNull(db.groupDao().findById(groupId))
        }

    @Test
    fun acceptsUpdateToKnownGroupRegardlessOfContact() =
        runTest {
            // Seed the group as if it had been adopted earlier (e.g.
            // creator WAS a contact at adoption time). Now mark them as
            // non-contact and push a rename: the gate must still allow it,
            // because the gate only fires on first sighting.
            db.peerDao().upsert(
                PeerEntity(
                    uuid = strangerUuid,
                    nickname = "Former friend",
                    lastHost = "10.0.0.5",
                    lastPort = 8080,
                    firstSeenAt = 0,
                    lastSeenAt = 0,
                    isContact = false,
                ),
            )
            db.groupDao().upsert(
                GroupEntity(
                    id = groupId,
                    name = "Original",
                    kind = "CHAT",
                    creatorUuid = strangerUuid,
                    createdAt = 1L,
                    membershipUpdatedAt = 1L,
                ),
            )
            repo.applySnapshot(
                fromUuid = strangerUuid,
                snapshot = snapshot.copy(name = "Renamed", membershipUpdatedAt = 2L),
            )
            assertEquals("Renamed", db.groupDao().findById(groupId)?.name)
        }
}
