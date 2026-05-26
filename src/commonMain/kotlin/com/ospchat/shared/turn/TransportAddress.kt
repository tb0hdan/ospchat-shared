package com.ospchat.shared.turn

/**
 * (Family, address, port) triple used inside STUN address attributes.
 * IPv4 only for phase 3 — the encoder rejects IPv6. The `address` is the raw
 * 4-byte network-order representation (e.g. 192.168.1.5 -> [192, 168, 1, 5]).
 */
internal data class TransportAddress(
    val family: Int = StunAddressFamily.IPV4,
    val address: ByteArray,
    val port: Int,
) {
    init {
        require(family == StunAddressFamily.IPV4) { "phase 3 supports IPv4 only" }
        require(address.size == 4) { "IPv4 address must be 4 bytes" }
        require(port in 0..0xFFFF) { "port out of range: $port" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TransportAddress) return false
        if (family != other.family) return false
        if (port != other.port) return false
        return address.contentEquals(other.address)
    }

    override fun hashCode(): Int {
        var result = family
        result = 31 * result + address.contentHashCode()
        result = 31 * result + port
        return result
    }

    override fun toString(): String {
        val (a, b, c, d) =
            listOf(
                address[0].toInt() and 0xFF,
                address[1].toInt() and 0xFF,
                address[2].toInt() and 0xFF,
                address[3].toInt() and 0xFF,
            )
        return "$a.$b.$c.$d:$port"
    }
}
