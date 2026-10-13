package de.rochefort.childmonitor.audio

import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertTrue

class PacketLossConcealerTest {

    @Test fun `first concealment approximates last frame`() {
        val plc = PacketLossConcealer(frameSize = 160)
        val original = ShortArray(160) { 1000 }
        plc.onPacketReceived(original)
        val concealed = plc.concealLostPacket()
        val max = concealed.maxOf { abs(it.toInt()) }
        assertTrue(max in 800..1100)
    }

    @Test fun `prolonged loss decays amplitude`() {
        val plc = PacketLossConcealer(frameSize = 160)
        plc.onPacketReceived(ShortArray(160) { 10_000 })
        var peak = 0
        repeat(20) {
            val c = plc.concealLostPacket()
            peak = c.maxOf { abs(it.toInt()) }
        }
        assertTrue(peak < 5_000)
    }

    @Test fun `reset on received`() {
        val plc = PacketLossConcealer(frameSize = 160)
        plc.onPacketReceived(ShortArray(160))
        repeat(15) { plc.concealLostPacket() }
        assertTrue(plc.lossCount() == 15)
        plc.onPacketReceived(ShortArray(160))
        assertTrue(plc.lossCount() == 0)
    }
}
