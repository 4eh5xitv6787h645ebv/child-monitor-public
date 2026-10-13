package de.rochefort.childmonitor.audio

import java.io.OutputStream
import java.security.SecureRandom

/** Writes one RFC 6455 binary client frame; transport failures propagate to the microphone worker. */
internal object MaskedWebSocketWriter {
    private val random = SecureRandom()

    fun write(output: OutputStream, data: ByteArray, length: Int) {
        require(length in 0..data.size)
        val mask = ByteArray(4).also(random::nextBytes)
        val prefixSize = when {
            length < 126 -> 2
            length < 65536 -> 4
            else -> 10
        }
        val frame = ByteArray(prefixSize + 4 + length)
        frame[0] = 0x82.toByte()
        frame[1] = (0x80 or when {
            length < 126 -> length
            length < 65536 -> 126
            else -> 127
        }).toByte()
        if (prefixSize > 2) {
            var remaining = length.toLong()
            for (i in prefixSize - 1 downTo 2) {
                frame[i] = remaining.toByte()
                remaining = remaining ushr 8
            }
        }
        mask.copyInto(frame, prefixSize)
        for (i in 0 until length) frame[prefixSize + 4 + i] =
            (data[i].toInt() xor mask[i % 4].toInt()).toByte()
        output.write(frame)
        output.flush()
    }
}
