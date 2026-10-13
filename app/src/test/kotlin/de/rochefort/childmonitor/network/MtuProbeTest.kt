package de.rochefort.childmonitor.network

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MtuProbeTest {

    @Test fun `vpn recommendation is more conservative than wifi`() {
        val wifi = MtuProbe.recommend(isVpn = false, isCellular = false)
        val vpn = MtuProbe.recommend(isVpn = true, isCellular = false)
        assertTrue(vpn.maxPayloadBytes < wifi.maxPayloadBytes)
    }

    @Test fun `chunk is never larger than payload ceiling`() {
        val rec = MtuProbe.recommend(isVpn = true, isCellular = false)
        val chunk = MtuProbe.chunkForWrite(desiredBytes = 65_000, rec = rec)
        assertTrue(chunk <= rec.maxPayloadBytes)
    }

    @Test fun `binary search finds largest successful payload`() {
        val found = MtuProbe.binarySearchMaxPayload(
            host = java.net.InetAddress.getLoopbackAddress(),
            port = 0,
            tester = { it <= 1280 },
            low = 512,
            high = 1420
        )
        assertEquals(1280, found)
    }
}
