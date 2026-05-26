package com.ospchat.shared.turn

/**
 * One ICE server entry to feed into libwebrtc's `RTCConfiguration.iceServers`.
 * Phase 3 multi-network bridging: a TURN-capable peer issues these via
 * `/v1/call/relay-cred` (PR 2) using credentials produced by
 * [TurnCredentialService.issue]. Consumed by `AudioCallSessionFactory.create`.
 *
 *  - [uri]        — e.g. `turn:192.168.1.5:3478?transport=udp`
 *  - [username]   — RFC 8155-style `"<expireEpochSec>:<requesterUuid>"`
 *  - [credential] — base64(HMAC-SHA1(serverSecret, username))
 */
data class IceServerConfig(
    val uri: String,
    val username: String,
    val credential: String,
)
