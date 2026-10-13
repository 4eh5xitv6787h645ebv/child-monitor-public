package de.rochefort.childmonitor

import java.util.Locale

/** Locale-invariant formatting for values carried in pairing URIs, JSON, and network logs. */
object WireFormat {
    fun pairingCode(number: Int): String {
        require(number in 0..999_999)
        return String.format(Locale.US, "%06d", number)
    }

    fun hourMinute(hour: Int, minute: Int): String {
        require(hour in 0..23 && minute in 0..59)
        return String.format(Locale.US, "%02d:%02d", hour, minute)
    }

    /** Android's legacy WifiInfo address stores IPv4 octets least-significant first. */
    fun littleEndianIpv4(address: Int): String = String.format(
        Locale.US,
        "%d.%d.%d.%d",
        address and 0xff,
        address shr 8 and 0xff,
        address shr 16 and 0xff,
        address shr 24 and 0xff,
    )
}
