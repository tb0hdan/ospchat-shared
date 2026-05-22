package com.ospchat.shared.net.server

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the request-size cap installed by MessageServer via
 * [installRequestSizeCap]. The test calls the production helper directly
 * so the cap logic can't silently drift from what the real server applies.
 * See docs/SECURITY.md D1.
 *
 * Chunked-transfer bypass (no Content-Length) is rejected by the same
 * helper — the branch is reachable but Ktor's in-process test client
 * refuses to emit a Transfer-Encoding header, so we don't exercise it
 * end-to-end here. It is covered by direct code inspection.
 */
class MessageServerRequestCapTest {
    private val cap = 1024L

    @Test
    fun rejectsOversizeContentLength() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installRequestSizeCap(cap)
            }
            routing { post("/ping") { call.respond(HttpStatusCode.OK) } }

            val body = "x".repeat((cap + 1).toInt())
            val response =
                client.post("/ping") {
                    contentType(ContentType.Text.Plain)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }

    @Test
    fun allowsTinyPostUnderCap() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installRequestSizeCap(cap)
            }
            routing { post("/ping") { call.respond(HttpStatusCode.OK) } }
            val response =
                client.post("/ping") {
                    contentType(ContentType.Text.Plain)
                    setBody("hi")
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun allowsEmptyPostWithZeroContentLength() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installRequestSizeCap(cap)
            }
            routing { post("/ping") { call.respond(HttpStatusCode.OK) } }
            // /v1/notify-refresh is empty-body POST; Ktor sends Content-Length: 0
            // and the cap must allow it through.
            assertEquals(HttpStatusCode.OK, client.post("/ping").status)
        }

    @Test
    fun allowsGetWithoutContentLength() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installRequestSizeCap(cap)
            }
            routing { get("/ping") { call.respond(HttpStatusCode.OK) } }
            assertEquals(HttpStatusCode.OK, client.get("/ping").status)
        }
}
