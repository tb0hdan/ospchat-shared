package com.ospchat.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedInfoTest {
    @Test
    fun wireApiVersion() {
        assertEquals("0.8.0", SharedInfo.WIRE_API_VERSION)
    }

    @Test
    fun mdnsServiceType() {
        assertEquals("_ospchat._tcp.", SharedInfo.MDNS_SERVICE_TYPE)
    }
}
