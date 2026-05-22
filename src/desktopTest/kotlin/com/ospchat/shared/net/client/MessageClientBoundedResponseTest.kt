package com.ospchat.shared.net.client

import com.ospchat.shared.data.discovery.Peer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers the response-body cap in MessageClient.fetchAttachment / fetchAvatar.
 * Replaces the unbounded `response.readBytes()` that was the OOM sink in
 * docs/SECURITY.md D2.
 *
 * The avatar cap is 2 MB; the attachment cap is 16 MB. The tests exercise
 * just-under, declared-over, and undeclared-over for the avatar path because
 * its smaller cap keeps the test heap reasonable. Same code path is reused
 * by the attachment fetch, so coverage carries.
 */
class MessageClientBoundedResponseTest {
    private val peer =
        Peer(
            uuid = "peer-uuid",
            nickname = "n",
            host = "127.0.0.1",
            port = 8080,
        )
    private val avatarCap = 2L * 1024 * 1024

    @Test
    fun avatarOfDeclaredOversizeIsRejected() =
        runTest {
            val engine =
                MockEngine { _ ->
                    val tooBig = avatarCap + 1
                    // Declare oversize but only send a tiny body — we should
                    // fail fast on Content-Length without reading bytes.
                    respond(
                        content = ByteArray(0),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentLength, tooBig.toString()),
                    )
                }
            val client = MessageClient(HttpClient(engine))
            val ex =
                assertFailsWith<IllegalStateException> {
                    client.fetchAvatar(peer, rediscover = false)
                }
            assertTrue(
                ex.message.orEmpty().contains("cap"),
                "expected cap mention in error, got: ${ex.message}",
            )
        }

    @Test
    fun avatarOfUndeclaredOversizeIsRejected() =
        runTest {
            val payload = ByteArray((avatarCap + 1024).toInt()) { (it % 256).toByte() }
            val engine =
                MockEngine { _ ->
                    // No Content-Length — the stream itself runs past the cap.
                    respond(content = payload, status = HttpStatusCode.OK)
                }
            val client = MessageClient(HttpClient(engine))
            val ex =
                assertFailsWith<IllegalStateException> {
                    client.fetchAvatar(peer, rediscover = false)
                }
            assertTrue(
                ex.message.orEmpty().contains("cap"),
                "expected cap mention in error, got: ${ex.message}",
            )
        }

    @Test
    fun smallAvatarPasses() =
        runTest {
            val payload = ByteArray(1024) { 0x42 }
            val engine =
                MockEngine { _ ->
                    respond(
                        content = payload,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentLength, payload.size.toString()),
                    )
                }
            val client = MessageClient(HttpClient(engine))
            val bytes = client.fetchAvatar(peer, rediscover = false)
            assertEquals(payload.size, bytes.size)
        }
}
