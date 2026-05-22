package com.ospchat.shared.net.server

import com.ospchat.shared.net.dto.ErrorDto
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

/**
 * Covers the StatusPages policy installed by MessageServer via
 * [installPeerStatusPages]. The catch-all 500 must NOT echo
 * `cause.message` — that's how internal file paths and JVM strings
 * leak (see docs/SECURITY.md D11). The two narrow handlers
 * (BadRequestException, IllegalArgumentException) intentionally do
 * surface their message: those are our own validator strings and
 * Ktor's parse-error text, both safe by construction.
 */
class MessageServerStatusPagesTest {
    @Test
    fun unhandledExceptionReturns500WithGenericDetail() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installPeerStatusPages()
            }
            routing {
                get("/boom") {
                    // A real bug path: a Throwable whose message would leak
                    // a filesystem location if echoed back.
                    error("/home/gh0st/secret/path: file not found")
                }
            }
            val client =
                createClient {
                    install(ClientContentNegotiation) { json() }
                }
            val response = client.get("/boom")
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val body: ErrorDto = response.body()
            assertEquals(ErrorCodes.INTERNAL_ERROR, body.error)
            assertEquals("internal error", body.detail)
            assertFalse(
                body.detail?.contains("/home/") == true,
                "internal path leaked into response: ${body.detail}",
            )
            assertFalse(
                body.detail?.contains("secret") == true,
                "internal message leaked into response: ${body.detail}",
            )
        }

    @Test
    fun illegalArgumentSurfacesItsMessage() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installPeerStatusPages()
            }
            routing {
                get("/iae") {
                    require(false) { "messageId contains unsafe character" }
                    call.respond(HttpStatusCode.OK)
                }
            }
            val client =
                createClient {
                    install(ClientContentNegotiation) { json() }
                }
            val response = client.get("/iae")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body: ErrorDto = response.body()
            assertEquals(ErrorCodes.BAD_REQUEST, body.error)
            assertEquals("messageId contains unsafe character", body.detail)
        }

    @Test
    fun badRequestSurfacesItsMessage() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installPeerStatusPages()
            }
            routing {
                get("/bad") {
                    throw BadRequestException("missing field 'x'")
                }
            }
            val client =
                createClient {
                    install(ClientContentNegotiation) { json() }
                }
            val response = client.get("/bad")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body: ErrorDto = response.body()
            assertEquals(ErrorCodes.BAD_REQUEST, body.error)
            assertEquals("missing field 'x'", body.detail)
        }
}
