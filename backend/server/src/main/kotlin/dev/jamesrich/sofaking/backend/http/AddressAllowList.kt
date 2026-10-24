package dev.jamesrich.sofaking.backend.http

import java.net.InetAddress

/** Client addresses admitted to an unauthenticated route: IP literals or CIDR blocks; an empty list admits everyone. */
class AddressAllowList(entries: List<String>) {
    private class Rule(val address: ByteArray, val bits: Int) {
        fun matches(candidate: ByteArray): Boolean {
            if (candidate.size != address.size) return false
            for (bit in 0 until bits) {
                val index = bit / 8
                val mask = 0x80 ushr (bit % 8)
                if ((address[index].toInt() and mask) != (candidate[index].toInt() and mask)) return false
            }
            return true
        }
    }

    private val rules = entries.map { entry ->
        val host = entry.substringBefore('/')
        val prefix = entry.substringAfter('/', "")
        val address = literal(host) ?: throw IllegalArgumentException("Not an IP address or CIDR block: $entry")
        val bits = if (prefix.isEmpty()) {
            address.size * 8
        } else {
            prefix.toIntOrNull()?.takeIf { it in 0..address.size * 8 }
                ?: throw IllegalArgumentException("Bad prefix length: $entry")
        }
        Rule(address, bits)
    }

    val open: Boolean get() = rules.isEmpty()

    fun allows(remote: String): Boolean {
        if (rules.isEmpty()) return true
        val address = literal(if (remote.equals("localhost", true)) "127.0.0.1" else remote) ?: return false
        return rules.any { it.matches(address) }
    }

    private companion object {
        /** An IPv4-mapped IPv6 address's first 12 bytes: ten zeros and two 0xFF (::ffff:0:0/96). */
        val V4_MAPPED_PREFIX = ByteArray(10) + byteArrayOf(-1, -1)
        const val IPV4_BYTES = 4
        const val IPV6_BYTES = 16
        const val IPV4_DOTS = 3

        /** The address bytes of an IP literal (IPv4-mapped IPv6 as IPv4), or null for anything that would need DNS. */
        fun literal(host: String): ByteArray? {
            val bare = host.trim('[', ']')
            val dotted = bare.isNotEmpty() && bare.all { it.isDigit() || it == '.' }
            // Java reads "192.168.1" as 192.168.0.1; a configuration value must spell all four parts.
            if (dotted && bare.count { it == '.' } != IPV4_DOTS) return null
            if (!dotted && !bare.contains(':')) return null
            val bytes = runCatching { InetAddress.getByName(bare).address }.getOrNull() ?: return null
            val mappedAt = IPV6_BYTES - IPV4_BYTES
            if (bytes.size == IPV6_BYTES && bytes.copyOfRange(0, mappedAt).contentEquals(V4_MAPPED_PREFIX)) {
                return bytes.copyOfRange(mappedAt, IPV6_BYTES)
            }
            return bytes
        }
    }
}
