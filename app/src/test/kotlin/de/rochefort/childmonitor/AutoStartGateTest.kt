package de.rochefort.childmonitor

import de.rochefort.childmonitor.AutoStartGate.Decision
import de.rochefort.childmonitor.AutoStartGate.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto-start enable gate ordering (#359/#379): permissions → (parent) saved child → overlay → persist.
 */
class AutoStartGateTest {

    @Test
    fun prefKey_mapsRole() {
        assertEquals("autoStartChild", AutoStartGate.prefKey(Role.CHILD))
        assertEquals("autoStartParent", AutoStartGate.prefKey(Role.PARENT))
    }

    @Test
    fun child_permissionsFirstThenOverlayThenPersist() {
        assertEquals(Decision.REQUEST_PERMISSIONS,
            AutoStartGate.decideEnable(Role.CHILD, hasPermissions = false, hasSavedChild = true, canDrawOverlays = true))
        assertEquals(Decision.REQUEST_OVERLAY,
            AutoStartGate.decideEnable(Role.CHILD, hasPermissions = true, hasSavedChild = true, canDrawOverlays = false))
        assertEquals(Decision.PERSIST,
            AutoStartGate.decideEnable(Role.CHILD, hasPermissions = true, hasSavedChild = true, canDrawOverlays = true))
    }

    @Test
    fun child_neverBlockedOnSavedChild() {
        // hasSavedChild is irrelevant for a child; a child with no saved child still persists.
        assertEquals(Decision.PERSIST,
            AutoStartGate.decideEnable(Role.CHILD, hasPermissions = true, hasSavedChild = false, canDrawOverlays = true))
    }

    @Test
    fun parent_gatesPermissionsThenSavedChildThenOverlay() {
        assertEquals(Decision.REQUEST_PERMISSIONS,
            AutoStartGate.decideEnable(Role.PARENT, hasPermissions = false, hasSavedChild = true, canDrawOverlays = true))
        // permissions ok but no saved child → blocked (before the overlay gate)
        assertEquals(Decision.BLOCK_NO_SAVED_CHILD,
            AutoStartGate.decideEnable(Role.PARENT, hasPermissions = true, hasSavedChild = false, canDrawOverlays = false))
        assertEquals(Decision.REQUEST_OVERLAY,
            AutoStartGate.decideEnable(Role.PARENT, hasPermissions = true, hasSavedChild = true, canDrawOverlays = false))
        assertEquals(Decision.PERSIST,
            AutoStartGate.decideEnable(Role.PARENT, hasPermissions = true, hasSavedChild = true, canDrawOverlays = true))
    }

    @Test
    fun hasSavedChild_ipOrPairedKid() {
        assertFalse(AutoStartGate.hasSavedChild(null, 0))
        assertFalse(AutoStartGate.hasSavedChild("", 0))
        assertFalse(AutoStartGate.hasSavedChild("   ", 0))
        assertTrue(AutoStartGate.hasSavedChild("192.168.0.10:10000", 0)) // legacy saved IP
        assertTrue(AutoStartGate.hasSavedChild(null, 1))                 // a paired kid (#406)
    }
}
