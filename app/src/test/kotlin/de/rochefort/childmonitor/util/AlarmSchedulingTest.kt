package de.rochefort.childmonitor.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [#229] setExactAndAllowWhileIdle / setAndAllowWhileIdle are API 23 (M) methods.
 * On the supported API 21–22 they must NOT be selected (NoSuchMethodError); the
 * scheduler must fall back to the pre-Doze exact setter instead.
 */
class AlarmSchedulingTest {

    @Test
    fun api21_usesLegacyExact_regardlessOfExactAllowedFlag() {
        assertEquals(AlarmMode.EXACT_LEGACY, AlarmScheduling.chooseMode(21, exactAllowed = false))
        assertEquals(AlarmMode.EXACT_LEGACY, AlarmScheduling.chooseMode(21, exactAllowed = true))
    }

    @Test
    fun api22_usesLegacyExact() {
        assertEquals(AlarmMode.EXACT_LEGACY, AlarmScheduling.chooseMode(22, exactAllowed = true))
    }

    @Test
    fun api23_usesExactWhileIdle() {
        assertEquals(AlarmMode.EXACT_WHILE_IDLE, AlarmScheduling.chooseMode(23, exactAllowed = true))
    }

    @Test
    fun api30_usesExactWhileIdle() {
        // Below S, exactAllowed is always true (no scheduling policy yet).
        assertEquals(AlarmMode.EXACT_WHILE_IDLE, AlarmScheduling.chooseMode(30, exactAllowed = true))
    }

    @Test
    fun api31_exactAllowed_usesExactWhileIdle() {
        assertEquals(AlarmMode.EXACT_WHILE_IDLE, AlarmScheduling.chooseMode(31, exactAllowed = true))
    }

    @Test
    fun api31_exactDenied_usesInexactWhileIdle() {
        assertEquals(AlarmMode.INEXACT_WHILE_IDLE, AlarmScheduling.chooseMode(31, exactAllowed = false))
    }

    @Test
    fun api34_exactDenied_usesInexactWhileIdle() {
        assertEquals(AlarmMode.INEXACT_WHILE_IDLE, AlarmScheduling.chooseMode(34, exactAllowed = false))
    }
}
