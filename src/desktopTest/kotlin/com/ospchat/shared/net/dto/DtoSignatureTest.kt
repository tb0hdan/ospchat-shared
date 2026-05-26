package com.ospchat.shared.net.dto

import com.ospchat.shared.crypto.SigningCrypto
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 2b multi-network bridging — round-trip coverage for every DTO
 * that gained a `signaturePayload` extension. Each test signs the
 * canonical payload with one keypair and verifies against the same
 * pubkey, then asserts a tampered DTO field produces a different
 * payload that the original signature can't verify.
 */
class DtoSignatureTest {
    private val keyPair = SigningCrypto.generate()
    private val verifier = SigningCrypto.verifyingKey(keyPair.publicKeyBytes())
    private val signedAt = 1_700_000_000_000L

    @Test
    fun incomingMessageRoundTrip() {
        val dto =
            IncomingMessageDto(
                id = "m1",
                fromUuid = "u1",
                fromNickname = "alice",
                body = "hi",
                sentAt = 1_699_999_000_000L,
            )
        val sig = keyPair.sign(dto.signaturePayload(signedAt))
        assertTrue(verifier.verify(dto.signaturePayload(signedAt), sig))
        // Tampered body must fail.
        val tampered = dto.copy(body = "hI")
        assertFalse(verifier.verify(tampered.signaturePayload(signedAt), sig))
        // Tampered signedAt must fail.
        assertFalse(verifier.verify(dto.signaturePayload(signedAt + 1), sig))
    }

    @Test
    fun incomingMessageWithAttachmentRoundTrip() {
        val dto =
            IncomingMessageDto(
                id = "m2",
                fromUuid = "u1",
                fromNickname = "alice",
                body = "see attached",
                sentAt = 1L,
                attachment =
                    AttachmentDto(
                        mimeType = "image/jpeg",
                        sizeBytes = 4096,
                        width = 800,
                        height = 600,
                    ),
            )
        val sig = keyPair.sign(dto.signaturePayload(signedAt))
        assertTrue(verifier.verify(dto.signaturePayload(signedAt), sig))
        // Drop attachment → different payload, sig invalid.
        assertFalse(verifier.verify(dto.copy(attachment = null).signaturePayload(signedAt), sig))
        // Change attachment dimensions → different payload, sig invalid.
        val resized = dto.copy(attachment = dto.attachment!!.copy(width = 801))
        assertFalse(verifier.verify(resized.signaturePayload(signedAt), sig))
    }

    @Test
    fun readReceiptRoundTrip() {
        val dto = ReadReceiptDto(fromUuid = "u1", upToSentAt = 12345L)
        val sig = keyPair.sign(dto.signaturePayload(signedAt))
        assertTrue(verifier.verify(dto.signaturePayload(signedAt), sig))
        assertFalse(verifier.verify(dto.copy(upToSentAt = 12346L).signaturePayload(signedAt), sig))
    }

    @Test
    fun reactionRoundTripAndNullEmoji() {
        val withEmoji =
            ReactionDto(
                messageId = "m1",
                fromUuid = "u1",
                fromNickname = "alice",
                emoji = "👍",
                reactedAt = 1L,
            )
        val without = withEmoji.copy(emoji = null)
        val sig = keyPair.sign(withEmoji.signaturePayload(signedAt))
        assertTrue(verifier.verify(withEmoji.signaturePayload(signedAt), sig))
        // Null vs. emoji are distinguishable — the same signature shouldn't
        // verify against the null variant.
        assertFalse(verifier.verify(without.signaturePayload(signedAt), sig))
    }

    @Test
    fun groupSnapshotRoundTripAndMemberOrderMatters() {
        val a = GroupMemberDto(uuid = "u1", nickname = "alice")
        val b = GroupMemberDto(uuid = "u2", nickname = "bob")
        val snap =
            GroupSnapshotDto(
                id = "g1",
                name = "Friends",
                kind = "GROUP",
                creatorUuid = "u1",
                createdAt = 1L,
                membershipUpdatedAt = 2L,
                members = listOf(a, b),
            )
        val sig = keyPair.sign(snap.signaturePayload(signedAt))
        assertTrue(verifier.verify(snap.signaturePayload(signedAt), sig))
        // Reordered members → different payload → sig must fail.
        val reordered = snap.copy(members = listOf(b, a))
        assertFalse(verifier.verify(reordered.signaturePayload(signedAt), sig))
    }

    @Test
    fun groupMessageRoundTrip() {
        val msg =
            GroupMessageDto(
                id = "gm1",
                fromUuid = "u1",
                fromNickname = "alice",
                body = "hi group",
                sentAt = 1L,
            )
        val sig = keyPair.sign(msg.signaturePayload(signedAt))
        assertTrue(verifier.verify(msg.signaturePayload(signedAt), sig))
        assertFalse(verifier.verify(msg.copy(body = "hi group!").signaturePayload(signedAt), sig))
    }

    @Test
    fun groupSyncRequestRoundTripAndCursorOrderMatters() {
        val req =
            GroupSyncRequestDto(
                fromUuid = "u1",
                cursors =
                    listOf(
                        GroupSyncCursorDto(groupId = "g1", latestSentAt = 1L),
                        GroupSyncCursorDto(groupId = "g2", latestSentAt = 2L),
                    ),
            )
        val sig = keyPair.sign(req.signaturePayload(signedAt))
        assertTrue(verifier.verify(req.signaturePayload(signedAt), sig))
        // Reorder cursors → different payload → sig invalid.
        val reordered = req.copy(cursors = req.cursors.reversed())
        assertFalse(verifier.verify(reordered.signaturePayload(signedAt), sig))
    }

    @Test
    fun groupLeaveRoundTrip() {
        val dto = GroupLeaveDto(groupId = "g1", fromUuid = "u1")
        val sig = keyPair.sign(dto.signaturePayload(signedAt))
        assertTrue(verifier.verify(dto.signaturePayload(signedAt), sig))
        // Tamper groupId → sig invalid.
        assertFalse(verifier.verify(dto.copy(groupId = "g2").signaturePayload(signedAt), sig))
    }

    @Test
    fun crossDtoDomainSeparation() {
        // A signature valid for an IncomingMessageDto must not verify against
        // a ReactionDto with the "same" data — the domain prefix differs.
        val msg = IncomingMessageDto(id = "x", fromUuid = "u", fromNickname = "n", body = "b", sentAt = 1L)
        val sig = keyPair.sign(msg.signaturePayload(signedAt))
        val reaction =
            ReactionDto(
                messageId = "x",
                fromUuid = "u",
                fromNickname = "n",
                emoji = "b", // same letter as msg.body, to control for accidental collisions
                reactedAt = 1L,
            )
        assertFalse(verifier.verify(reaction.signaturePayload(signedAt), sig))
    }
}
