package com.ospchat.shared.media

import com.ospchat.shared.net.dto.CallIceDto
import com.ospchat.shared.turn.IceServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-agnostic facade over one audio-only WebRTC session (one
 * `RTCPeerConnection` underneath). One session per call; the repository
 * creates a fresh session via [AudioCallSessionFactory] when a call starts
 * and tears it down on hangup.
 *
 * Implementations:
 *  - Android: wraps `io.getstream.webrtc.android.PeerConnection`
 *    (Stream's pre-compiled libwebrtc fork).
 *  - Desktop JVM: wraps `dev.onvoid.webrtc.RTCPeerConnection`
 *    (JNI bindings to libwebrtc).
 *
 * Both implementations are configured with empty ICE servers (host
 * candidates only — OSPChat is LAN-only TOFU, no STUN/TURN).
 */
interface AudioCallSession {
    /** Connection state. Driven by ICE / DTLS transitions inside libwebrtc. */
    val state: StateFlow<State>

    /**
     * Local ICE candidates as they're gathered. Each one is POSTed by the
     * repository to the remote peer's `/v1/call/ice` endpoint with the
     * pre-filled [CallIceDto.callId] / [CallIceDto.fromUuid] applied by
     * the caller.
     */
    val localIceCandidates: Flow<IceCandidate>

    /**
     * Create the SDP offer for an outbound call. Must be called once, before
     * any other method. Returns the offer SDP to send via `/v1/call/offer`.
     */
    suspend fun createOffer(): String

    /**
     * Set the remote offer (received via `/v1/call/offer`) and create the
     * answer SDP for an inbound call. Must be called once, before any other
     * method. Returns the answer SDP to send via `/v1/call/answer`.
     */
    suspend fun acceptOffer(remoteSdp: String): String

    /**
     * Caller side only: install the answer SDP received via `/v1/call/answer`.
     * After this call ICE negotiation completes and [state] should transition
     * to [State.CONNECTED] shortly thereafter (or [State.FAILED]).
     */
    suspend fun setRemoteAnswer(sdp: String)

    /**
     * Either side: add a remote ICE candidate received via `/v1/call/ice`.
     */
    suspend fun addRemoteIce(candidate: IceCandidate)

    /** Mute (or unmute) the local microphone track. */
    fun setMuted(muted: Boolean)

    /**
     * Tear down the session — closes the underlying `RTCPeerConnection`,
     * releases the mic, stops emitting on [localIceCandidates] / [state].
     * Idempotent; safe to call multiple times.
     */
    fun close()

    enum class State {
        /** Session created; no SDP exchanged yet. */
        NEW,

        /** SDP set; ICE gathering / DTLS handshake in progress. */
        NEGOTIATING,

        /** ICE connected, DTLS handshake done — audio is flowing. */
        CONNECTED,

        /** ICE failed or DTLS errored — terminal. */
        FAILED,

        /** Explicitly closed via [close] — terminal. */
        CLOSED,
    }

    data class IceCandidate(
        val sdpMid: String?,
        val sdpMLineIndex: Int,
        val candidate: String,
    )
}

/**
 * Creates one [AudioCallSession] per call. Each platform's DI graph supplies
 * the concrete factory (Android: `AndroidAudioCallSessionFactory`, desktop:
 * `JvmAudioCallSessionFactory`). The factory typically owns expensive shared
 * native state (libwebrtc's PeerConnectionFactory, audio device module) so
 * sessions can be lightweight.
 */
interface AudioCallSessionFactory {
    /**
     * Create a fresh session. [iceServers] is threaded into the underlying
     * `RTCConfiguration.iceServers`; passing an empty list (the default)
     * gives the pre-phase-3 behaviour of host-candidates-only LAN calls.
     * Phase 3 supplies TURN entries fetched via `/v1/call/relay-cred`.
     */
    fun create(iceServers: List<IceServerConfig> = emptyList()): AudioCallSession
}

/**
 * Helpers to convert between [AudioCallSession.IceCandidate] and the wire
 * DTO [CallIceDto]. Keeps the session interface platform-agnostic and the
 * DTO module purely about serialization.
 */
fun AudioCallSession.IceCandidate.toDto(
    callId: String,
    fromUuid: String,
): CallIceDto =
    CallIceDto(
        callId = callId,
        fromUuid = fromUuid,
        candidate = candidate,
        sdpMid = sdpMid,
        sdpMLineIndex = sdpMLineIndex,
    )

fun CallIceDto.toCandidate(): AudioCallSession.IceCandidate =
    AudioCallSession.IceCandidate(
        sdpMid = sdpMid,
        sdpMLineIndex = sdpMLineIndex,
        candidate = candidate,
    )
