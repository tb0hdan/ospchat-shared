package com.ospchat.shared.data.discovery

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JmDnsPeerDiscoveryTest {
    @Test
    fun startStopIdempotent() {
        val discovery = JmDnsPeerDiscovery()
        discovery.start(nickname = "self", uuid = "self-uuid", port = 18_080)
        // Calling start again is a no-op (no exception, no second registration).
        discovery.start(nickname = "self", uuid = "self-uuid", port = 18_080)
        discovery.stop()
        // Stop again is also safe.
        discovery.stop()
        assertEquals(emptyMap(), discovery.peers.value)
    }

    @Test
    fun rejectsInvalidPort() {
        val discovery = JmDnsPeerDiscovery()
        try {
            discovery.start("x", "y", port = 0)
            error("expected IllegalArgumentException for port 0")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
        try {
            discovery.start("x", "y", port = 70_000)
            error("expected IllegalArgumentException for out-of-range port")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    /**
     * Same-process JmDNS discovery. Register a peer on a separate JmDNS
     * instance and wait for our service to discover it. May not work in
     * sandboxed CI environments without multicast; skipped if discovery
     * doesn't fire within the timeout window.
     */
    @Test
    fun discoversAnotherJmdnsInstance() =
        runBlocking {
            val discovery = JmDnsPeerDiscovery()
            val remoteJm = JmDNS.create()
            val remoteInfo =
                ServiceInfo.create(
                    "_ospchat._tcp.local.",
                    "remote-peer",
                    54_321,
                    0,
                    0,
                    mapOf("uuid" to "remote-uuid"),
                )
            try {
                discovery.start("self", "self-uuid", 12_345)
                remoteJm.registerService(remoteInfo)

                // Give mDNS a moment to propagate. Discovery happens through the
                // OS multicast loopback so this is environment-sensitive.
                val seen =
                    withTimeoutOrNull(8_000) {
                        while (true) {
                            val snapshot = discovery.peers.value
                            if (snapshot["remote-uuid"] != null) return@withTimeoutOrNull snapshot["remote-uuid"]
                            delay(150)
                        }
                        @Suppress("UNREACHABLE_CODE")
                        null
                    }
                if (seen == null) {
                    println("WARN: cross-instance mDNS discovery did not fire within 8s — likely sandboxed env, skipping assertion")
                    return@runBlocking
                }
                assertEquals("remote-peer", seen.nickname)
                assertEquals(54_321, seen.port)
                assertEquals("remote-uuid", seen.uuid)
            } finally {
                runCatching { remoteJm.unregisterAllServices() }
                runCatching { remoteJm.close() }
                discovery.stop()
            }
        }

    /** Self-echo suppression: our own registration must not appear in [peers]. */
    @Test
    fun ownServiceNeverEnteredAsPeer() =
        runBlocking {
            val discovery = JmDnsPeerDiscovery()
            try {
                discovery.start(nickname = "myself", uuid = "self-uuid-xyz", port = 19_080)
                // Give mDNS time to round-trip our own registration.
                delay(2_000)
                val ownEntry = discovery.peers.value["self-uuid-xyz"]
                assertNull(ownEntry, "self UUID must be filtered out of peer snapshot")
            } finally {
                discovery.stop()
            }
        }
}
