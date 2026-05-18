package com.ospchat.shared.data.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ospchat.shared.data.groups.GroupEntity
import com.ospchat.shared.data.messages.MessageEntity
import com.ospchat.shared.data.peers.PeerAddressEntity
import com.ospchat.shared.data.peers.PeerEntity
import com.ospchat.shared.data.peers.PeerNicknameEntity
import com.ospchat.shared.data.reactions.ReactionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OspChatDatabaseTest {
    private lateinit var tmpDir: File
    private lateinit var db: OspChatDatabase

    @BeforeTest
    fun setup() {
        tmpDir = Files.createTempDirectory("ospchat-db-test").toFile()
        db =
            Room
                .databaseBuilder<OspChatDatabase>(name = File(tmpDir, "ospchat.db").absolutePath)
                .addMigrations(*OSPCHAT_MIGRATIONS)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }

    @AfterTest
    fun teardown() {
        db.close()
        tmpDir.deleteRecursively()
    }

    @Test
    fun peerRoundTrip() =
        runTest {
            val peer =
                PeerEntity(
                    uuid = "uuid-1",
                    nickname = "Alice",
                    lastHost = "10.0.0.5",
                    lastPort = 42_000,
                    firstSeenAt = 1_000L,
                    lastSeenAt = 2_000L,
                )
            db.peerDao().upsert(peer)
            val found = db.peerDao().findByUuid("uuid-1")
            assertNotNull(found)
            assertEquals("Alice", found.nickname)
            assertEquals(false, found.isContact)
        }

    @Test
    fun messageInsertAndObserve() =
        runTest {
            db.peerDao().upsert(
                PeerEntity(
                    uuid = "uuid-2",
                    nickname = "Bob",
                    lastHost = "10.0.0.6",
                    lastPort = 42_001,
                    firstSeenAt = 0L,
                    lastSeenAt = 0L,
                ),
            )
            db.messageDao().insert(
                MessageEntity(
                    id = "msg-1",
                    peerUuid = "uuid-2",
                    fromUuid = "uuid-2",
                    fromNickname = "Bob",
                    body = "hello",
                    sentAt = 100L,
                    direction = "IN",
                    status = "DELIVERED",
                ),
            )
            val msgs = db.messageDao().observeByPeer("uuid-2").first()
            assertEquals(1, msgs.size)
            assertEquals("hello", msgs[0].body)
        }

    @Test
    fun reactionsAndComposite() =
        runTest {
            db.peerDao().upsert(
                PeerEntity(
                    uuid = "p",
                    nickname = "n",
                    lastHost = "h",
                    lastPort = 1,
                    firstSeenAt = 0,
                    lastSeenAt = 0,
                ),
            )
            db.messageDao().insert(
                MessageEntity(
                    id = "m",
                    peerUuid = "p",
                    fromUuid = "p",
                    fromNickname = "n",
                    body = "hi",
                    sentAt = 1,
                    direction = "IN",
                    status = "DELIVERED",
                ),
            )
            db.reactionDao().upsert(
                ReactionEntity(
                    messageId = "m",
                    fromUuid = "u1",
                    fromNickname = "U1",
                    emoji = "👍",
                    reactedAt = 2,
                ),
            )
            // composite-PK replace: a fresh reaction from same user on same message overwrites
            db.reactionDao().upsert(
                ReactionEntity(
                    messageId = "m",
                    fromUuid = "u1",
                    fromNickname = "U1",
                    emoji = "❤️",
                    reactedAt = 3,
                ),
            )
            val rs = db.reactionDao().observeForPeer("p").first()
            assertEquals(1, rs.size)
            assertEquals("❤️", rs[0].emoji)
        }

    @Test
    fun peerHistoryTablesIndependent() =
        runTest {
            db.peerHistoryDao().insertAddressIfAbsent(
                PeerAddressEntity(
                    uuid = "p",
                    host = "h1",
                    port = 1,
                    firstSeenAt = 0,
                    lastSeenAt = 0,
                ),
            )
            db.peerHistoryDao().insertNicknameIfAbsent(
                PeerNicknameEntity(
                    uuid = "p",
                    nickname = "Old",
                    firstSeenAt = 0,
                    lastSeenAt = 0,
                ),
            )
            val addrs = db.peerHistoryDao().observeAddresses("p").first()
            val nicks = db.peerHistoryDao().observeNicknames("p").first()
            assertEquals(1, addrs.size)
            assertEquals(1, nicks.size)
        }

    @Test
    fun groupTablesPresent() =
        runTest {
            val g =
                GroupEntity(
                    id = "g1",
                    name = "Test Group",
                    kind = "CHAT",
                    creatorUuid = "u1",
                    createdAt = 1,
                    membershipUpdatedAt = 1,
                )
            db.groupDao().upsert(g)
            assertEquals("Test Group", db.groupDao().findById("g1")?.name)
        }

    @Test
    fun unreadCountsByPeer() =
        runTest {
            db.peerDao().upsert(
                PeerEntity(
                    uuid = "px",
                    nickname = "X",
                    lastHost = "h",
                    lastPort = 1,
                    firstSeenAt = 0,
                    lastSeenAt = 0,
                    lastReadAt = 5,
                ),
            )
            // 2 inbound after last_read_at=5, 1 inbound before, 1 outbound after (ignored)
            listOf(
                MessageEntity(
                    id = "a",
                    peerUuid = "px",
                    fromUuid = "px",
                    fromNickname = "X",
                    body = "b1",
                    sentAt = 3,
                    direction = "IN",
                    status = "DELIVERED",
                ),
                MessageEntity(
                    id = "b",
                    peerUuid = "px",
                    fromUuid = "px",
                    fromNickname = "X",
                    body = "b2",
                    sentAt = 7,
                    direction = "IN",
                    status = "DELIVERED",
                ),
                MessageEntity(
                    id = "c",
                    peerUuid = "px",
                    fromUuid = "px",
                    fromNickname = "X",
                    body = "b3",
                    sentAt = 8,
                    direction = "IN",
                    status = "DELIVERED",
                ),
                MessageEntity(
                    id = "d",
                    peerUuid = "px",
                    fromUuid = "me",
                    fromNickname = "Me",
                    body = "b4",
                    sentAt = 9,
                    direction = "OUT",
                    status = "SENDING",
                ),
            ).forEach { db.messageDao().insert(it) }
            val counts = db.messageDao().observeUnreadCounts().first()
            assertEquals(1, counts.size)
            assertEquals("px", counts[0].peerUuid)
            assertEquals(2, counts[0].count)
        }

    @Test
    fun markOutboundReadOnlyTouchesDelivered() =
        runTest {
            db.peerDao().upsert(
                PeerEntity(
                    uuid = "py",
                    nickname = "Y",
                    lastHost = "h",
                    lastPort = 1,
                    firstSeenAt = 0,
                    lastSeenAt = 0,
                ),
            )
            listOf(
                MessageEntity(
                    id = "o1",
                    peerUuid = "py",
                    fromUuid = "me",
                    fromNickname = "Me",
                    body = "b",
                    sentAt = 1,
                    direction = "OUT",
                    status = "DELIVERED",
                ),
                MessageEntity(
                    id = "o2",
                    peerUuid = "py",
                    fromUuid = "me",
                    fromNickname = "Me",
                    body = "b",
                    sentAt = 2,
                    direction = "OUT",
                    status = "SENDING",
                ),
                MessageEntity(
                    id = "o3",
                    peerUuid = "py",
                    fromUuid = "me",
                    fromNickname = "Me",
                    body = "b",
                    sentAt = 3,
                    direction = "OUT",
                    status = "FAILED",
                ),
                MessageEntity(
                    id = "o4",
                    peerUuid = "py",
                    fromUuid = "me",
                    fromNickname = "Me",
                    body = "b",
                    sentAt = 5,
                    direction = "OUT",
                    status = "DELIVERED",
                ),
            ).forEach { db.messageDao().insert(it) }
            db.messageDao().markOutboundRead("py", upToSentAt = 4)
            assertEquals("READ", db.messageDao().findById("o1")?.status)
            assertEquals("SENDING", db.messageDao().findById("o2")?.status)
            assertEquals("FAILED", db.messageDao().findById("o3")?.status)
            // o4 has sent_at=5 > upTo=4, stays DELIVERED
            assertEquals("DELIVERED", db.messageDao().findById("o4")?.status)
        }

    @Test
    fun groupMembersReplaceAtomically() =
        runTest {
            db.groupDao().upsert(
                GroupEntity(
                    id = "g2",
                    name = "G2",
                    kind = "CHAT",
                    creatorUuid = "u1",
                    createdAt = 1,
                    membershipUpdatedAt = 1,
                ),
            )
            db.groupDao().replaceMembers(
                "g2",
                listOf(
                    com.ospchat.shared.data.groups
                        .GroupMemberEntity(groupId = "g2", memberUuid = "u1", memberNickname = "U1", joinedAt = 1),
                    com.ospchat.shared.data.groups
                        .GroupMemberEntity(groupId = "g2", memberUuid = "u2", memberNickname = "U2", joinedAt = 1),
                ),
            )
            assertEquals(2, db.groupDao().membersOf("g2").size)
            db.groupDao().replaceMembers(
                "g2",
                listOf(
                    com.ospchat.shared.data.groups
                        .GroupMemberEntity(groupId = "g2", memberUuid = "u3", memberNickname = "U3", joinedAt = 2),
                ),
            )
            val after = db.groupDao().membersOf("g2")
            assertEquals(1, after.size)
            assertEquals("u3", after[0].memberUuid)
        }

    @Test
    fun migrationsArrayCoversAllVersions() {
        // Defensive: any future migration added must be wired into the array
        assertEquals(8, OSPCHAT_MIGRATIONS.size)
        assertTrue(OSPCHAT_MIGRATIONS.all { it.startVersion + 1 == it.endVersion })
    }
}
