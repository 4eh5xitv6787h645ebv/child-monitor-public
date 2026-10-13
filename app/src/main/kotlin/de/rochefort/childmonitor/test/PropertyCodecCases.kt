package de.rochefort.childmonitor.test

/**
 * Property-test cases for codec, framing, and websocket/tcp packet boundaries.
 * Implements CM-TST-004: Property tests for codec, framing, and packet boundaries.
 *
 * These are pure input generators. A JVM unit test (or a property-based test
 * framework like Kotest PBT) can enumerate them and run them through the
 * production codec path. Keeping them as plain data lets us unit-test the
 * generator itself and reuse the same cases for fuzzing (CM-TST-008).
 */
object PropertyCodecCases {

    data class Case(val label: String, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is Case && other.label == label && other.bytes.contentEquals(bytes)
        override fun hashCode(): Int = 31 * label.hashCode() + bytes.contentHashCode()
    }

    fun audioFrameCases(): List<Case> = listOf(
        Case("empty", ByteArray(0)),
        Case("single-byte", byteArrayOf(0x7F)),
        Case("all-zeros-160", ByteArray(160)),
        Case("all-zeros-4096", ByteArray(4_096)),
        Case("alt-max", ByteArray(160) { if (it % 2 == 0) 0x7F else 0x80.toByte() }),
        Case("ramp", ByteArray(256) { (it and 0xFF).toByte() }),
        Case("random-fixed-seed", ByteArray(1_000).also {
            val rng = java.util.Random(0xDEADBEEF)
            rng.nextBytes(it)
        })
    )

    fun websocketBoundaryCases(): List<Case> = listOf(
        Case("ws-tiny-payload", ByteArray(1)),
        Case("ws-125-boundary", ByteArray(125)),
        Case("ws-126-boundary", ByteArray(126)),
        Case("ws-extended-len", ByteArray(65_535)),
        Case("ws-huge", ByteArray(1_000_000))
    )

    fun tcpFramingCases(): List<Case> = listOf(
        Case("tcp-single-mulaw-frame", ByteArray(160)),
        Case("tcp-split-across-two", ByteArray(320)),
        Case("tcp-odd-size", ByteArray(161)),
        Case("tcp-two-plus-partial", ByteArray(161 + 160)),
        Case("tcp-truncated-one-byte", ByteArray(159))
    )
}
