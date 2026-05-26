package com.ospchat.shared.net.dto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

/**
 * Phase 4 multi-network bridging — the load-bearing invariant for the
 * `toUuid` field's append-only signature extension: a DTO with
 * `toUuid == null` must produce byte-identical signature bytes whether
 * it's serialized by a phase-2b client (no `toUuid` field at all) or a
 * phase-4 client (field present but null). Otherwise we'd silently
 * break signature interop across the rollout window.
 *
 * Together with [DtoSignatureTest] (which proves a non-null `toUuid`
 * changes the payload), this nails down the wire compatibility
 * promise.
 */
class RelayBackwardsCompatTest {
    private val signedAt = 1_700_000_000_000L

    @Test
    fun incomingMessage_nullToUuid_matches_phase2bPayload() {
        // A phase-2b sender would have signed a DTO whose `toUuid` field
        // didn't even exist (the property was added in phase 4). When the
        // bytes hit a phase-4 receiver they deserialize as null. The
        // signature must verify, so the canonical payload must match.
        val phase2bShape =
            IncomingMessageDto(
                id = "m1",
                fromUuid = "u1",
                fromNickname = "alice",
                body = "hello",
                sentAt = 1L,
            )
        val phase4ShapeNullToUuid =
            phase2bShape.copy(toUuid = null, via = null, hopTtl = null)
        assertContentEquals(
            phase2bShape.signaturePayload(signedAt),
            phase4ShapeNullToUuid.signaturePayload(signedAt),
        )
    }

    @Test
    fun incomingMessage_nonNullToUuid_diverges() {
        val a =
            IncomingMessageDto(
                id = "m1",
                fromUuid = "u1",
                fromNickname = "alice",
                body = "hello",
                sentAt = 1L,
                toUuid = null,
            )
        val b = a.copy(toUuid = "u2")
        assertFalse(
            a.signaturePayload(signedAt).contentEquals(b.signaturePayload(signedAt)),
            "toUuid must enter the signature payload when set",
        )
    }

    @Test
    fun viaAndHopTtl_outsideSignature() {
        // Intermediates mutate `via` and `hopTtl`; the signature must
        // NOT cover them or relayed messages would fail verification.
        val original =
            IncomingMessageDto(
                id = "m1",
                fromUuid = "u1",
                fromNickname = "alice",
                body = "hi",
                sentAt = 1L,
                toUuid = "u2",
            )
        val afterBridgeHop =
            original.copy(via = listOf("bridge-uuid"), hopTtl = 2)
        assertContentEquals(
            original.signaturePayload(signedAt),
            afterBridgeHop.signaturePayload(signedAt),
        )
    }

    @Test
    fun readReceipt_nullToUuid_matches() {
        val phase2b = ReadReceiptDto(fromUuid = "u1", upToSentAt = 1L)
        val phase4Null = phase2b.copy(toUuid = null)
        assertContentEquals(
            phase2b.signaturePayload(signedAt),
            phase4Null.signaturePayload(signedAt),
        )
    }

    @Test
    fun reaction_nullToUuid_matches() {
        val phase2b =
            ReactionDto(
                messageId = "m1",
                fromUuid = "u1",
                fromNickname = "alice",
                emoji = "👍",
                reactedAt = 1L,
            )
        val phase4Null = phase2b.copy(toUuid = null)
        assertContentEquals(
            phase2b.signaturePayload(signedAt),
            phase4Null.signaturePayload(signedAt),
        )
    }

    @Test
    fun groupSnapshot_nullToUuid_matches() {
        val phase2b =
            GroupSnapshotDto(
                id = "g1",
                name = "Friends",
                kind = "GROUP",
                creatorUuid = "u1",
                createdAt = 1L,
                membershipUpdatedAt = 2L,
                members = listOf(GroupMemberDto(uuid = "u1", nickname = "alice")),
            )
        val phase4Null = phase2b.copy(toUuid = null)
        assertContentEquals(
            phase2b.signaturePayload(signedAt),
            phase4Null.signaturePayload(signedAt),
        )
    }

    @Test
    fun groupMessage_nullToUuid_matches() {
        val phase2b =
            GroupMessageDto(
                id = "gm1",
                fromUuid = "u1",
                fromNickname = "alice",
                body = "hi group",
                sentAt = 1L,
            )
        val phase4Null = phase2b.copy(toUuid = null)
        assertContentEquals(
            phase2b.signaturePayload(signedAt),
            phase4Null.signaturePayload(signedAt),
        )
    }

    @Test
    fun groupSyncRequest_nullToUuid_matches() {
        val phase2b =
            GroupSyncRequestDto(
                fromUuid = "u1",
                cursors = listOf(GroupSyncCursorDto(groupId = "g1", latestSentAt = 1L)),
            )
        val phase4Null = phase2b.copy(toUuid = null)
        assertContentEquals(
            phase2b.signaturePayload(signedAt),
            phase4Null.signaturePayload(signedAt),
        )
    }

    @Test
    fun groupLeave_nullToUuid_matches() {
        val phase2b = GroupLeaveDto(groupId = "g1", fromUuid = "u1")
        val phase4Null = phase2b.copy(toUuid = null)
        assertContentEquals(
            phase2b.signaturePayload(signedAt),
            phase4Null.signaturePayload(signedAt),
        )
    }

    // ---- Phase 5 multi-network bridging — call DTOs ------------------------
    //
    // Phase 3 (PR 2) shipped CallOfferDto / CallAnswerDto / CallIceDto /
    // CallHangupDto with the signing fields but no bridging fields. Phase 5
    // (PR 3) added `toUuid` / `via` / `hopTtl` to the same DTOs, append-only
    // over the phase-3 signature payload so a relayed offer from a PR-3
    // sender still verifies on a PR-2 receiver when toUuid is null. The
    // tests below pin that invariant down.

    @Test
    fun callOffer_nullToUuid_matches_phase3Payload() {
        val phase3 =
            CallOfferDto(
                callId = "c1",
                fromUuid = "u1",
                fromNickname = "alice",
                sdp = "v=0...",
                sentAt = 1L,
            )
        val phase5Null = phase3.copy(toUuid = null, via = null, hopTtl = null)
        assertContentEquals(
            phase3.signaturePayload(signedAt),
            phase5Null.signaturePayload(signedAt),
        )
    }

    @Test
    fun callOffer_nonNullToUuid_diverges() {
        val a =
            CallOfferDto(
                callId = "c1",
                fromUuid = "u1",
                fromNickname = "alice",
                sdp = "v=0...",
                sentAt = 1L,
                toUuid = null,
            )
        val b = a.copy(toUuid = "u2")
        assertFalse(
            a.signaturePayload(signedAt).contentEquals(b.signaturePayload(signedAt)),
            "toUuid must enter the call-offer signature payload when set",
        )
    }

    @Test
    fun callOffer_viaAndHopTtl_outsideSignature() {
        // Bridges mutate via/hopTtl when forwarding; signature must not cover
        // them or the forwarded offer would fail verification at the target.
        val original =
            CallOfferDto(
                callId = "c1",
                fromUuid = "u1",
                fromNickname = "alice",
                sdp = "v=0...",
                sentAt = 1L,
                toUuid = "u2",
            )
        val afterBridgeHop = original.copy(via = listOf("bridge-uuid"), hopTtl = 2)
        assertContentEquals(
            original.signaturePayload(signedAt),
            afterBridgeHop.signaturePayload(signedAt),
        )
    }

    @Test
    fun callAnswer_nullToUuid_matches_phase3Payload() {
        val phase3 =
            CallAnswerDto(
                callId = "c1",
                fromUuid = "u1",
                sdp = "v=0...",
            )
        val phase5Null = phase3.copy(toUuid = null, via = null, hopTtl = null)
        assertContentEquals(
            phase3.signaturePayload(signedAt),
            phase5Null.signaturePayload(signedAt),
        )
    }

    @Test
    fun callAnswer_nonNullToUuid_diverges() {
        val a = CallAnswerDto(callId = "c1", fromUuid = "u1", sdp = "v=0...")
        val b = a.copy(toUuid = "u2")
        assertFalse(
            a.signaturePayload(signedAt).contentEquals(b.signaturePayload(signedAt)),
            "toUuid must enter the call-answer signature payload when set",
        )
    }

    @Test
    fun callAnswer_viaAndHopTtl_outsideSignature() {
        val original = CallAnswerDto(callId = "c1", fromUuid = "u1", sdp = "v=0...", toUuid = "u2")
        val afterBridgeHop = original.copy(via = listOf("bridge-uuid"), hopTtl = 2)
        assertContentEquals(
            original.signaturePayload(signedAt),
            afterBridgeHop.signaturePayload(signedAt),
        )
    }

    @Test
    fun callIce_nullToUuid_matches_phase3Payload() {
        val phase3 =
            CallIceDto(
                callId = "c1",
                fromUuid = "u1",
                candidate = "candidate:0 1 UDP 2122252543 192.168.1.5 9000 typ host",
                sdpMid = "0",
                sdpMLineIndex = 0,
            )
        val phase5Null = phase3.copy(toUuid = null, via = null, hopTtl = null)
        assertContentEquals(
            phase3.signaturePayload(signedAt),
            phase5Null.signaturePayload(signedAt),
        )
    }

    @Test
    fun callIce_nonNullToUuid_diverges() {
        val a =
            CallIceDto(
                callId = "c1",
                fromUuid = "u1",
                candidate = "candidate:0 1 UDP 2122252543 192.168.1.5 9000 typ host",
            )
        val b = a.copy(toUuid = "u2")
        assertFalse(
            a.signaturePayload(signedAt).contentEquals(b.signaturePayload(signedAt)),
            "toUuid must enter the call-ice signature payload when set",
        )
    }

    @Test
    fun callIce_viaAndHopTtl_outsideSignature() {
        val original =
            CallIceDto(
                callId = "c1",
                fromUuid = "u1",
                candidate = "candidate:0 1 UDP 2122252543 192.168.1.5 9000 typ host",
                toUuid = "u2",
            )
        val afterBridgeHop = original.copy(via = listOf("bridge-uuid"), hopTtl = 2)
        assertContentEquals(
            original.signaturePayload(signedAt),
            afterBridgeHop.signaturePayload(signedAt),
        )
    }

    @Test
    fun callHangup_nullToUuid_matches_phase3Payload() {
        val phase3 = CallHangupDto(callId = "c1", fromUuid = "u1", reason = "HANGUP")
        val phase5Null = phase3.copy(toUuid = null, via = null, hopTtl = null)
        assertContentEquals(
            phase3.signaturePayload(signedAt),
            phase5Null.signaturePayload(signedAt),
        )
    }

    @Test
    fun callHangup_nonNullToUuid_diverges() {
        val a = CallHangupDto(callId = "c1", fromUuid = "u1", reason = "HANGUP")
        val b = a.copy(toUuid = "u2")
        assertFalse(
            a.signaturePayload(signedAt).contentEquals(b.signaturePayload(signedAt)),
            "toUuid must enter the call-hangup signature payload when set",
        )
    }

    @Test
    fun callHangup_viaAndHopTtl_outsideSignature() {
        val original = CallHangupDto(callId = "c1", fromUuid = "u1", reason = "HANGUP", toUuid = "u2")
        val afterBridgeHop = original.copy(via = listOf("bridge-uuid"), hopTtl = 2)
        assertContentEquals(
            original.signaturePayload(signedAt),
            afterBridgeHop.signaturePayload(signedAt),
        )
    }

    @Test
    fun callHangup_nullReason_remainsStableUnderToUuidAddition() {
        // Hangup's `reason` is a nullable string written via writeNullableString.
        // Adding/removing toUuid must not interact with the reason encoding.
        val noReasonPhase3 = CallHangupDto(callId = "c1", fromUuid = "u1")
        val noReasonPhase5Null = noReasonPhase3.copy(toUuid = null)
        assertContentEquals(
            noReasonPhase3.signaturePayload(signedAt),
            noReasonPhase5Null.signaturePayload(signedAt),
        )
    }
}
