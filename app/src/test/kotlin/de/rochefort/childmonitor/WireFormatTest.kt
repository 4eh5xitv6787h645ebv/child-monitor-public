package de.rochefort.childmonitor

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class WireFormatTest {
    private lateinit var originalLocale: Locale

    @Before
    fun rememberLocale() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `wire values always use ASCII protocol digits`() {
        assertEquals("000123", WireFormat.pairingCode(123))
        assertEquals("07:05", WireFormat.hourMinute(7, 5))
        assertEquals("192.168.0.5", WireFormat.littleEndianIpv4(0x0500A8C0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `pairing code rejects out of range input`() {
        WireFormat.pairingCode(1_000_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `wire time rejects impossible values`() {
        WireFormat.hourMinute(24, 0)
    }
}
