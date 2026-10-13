package de.rochefort.childmonitor.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #320: which special-access prompts the alert-delivery UI should surface, by SDK level + grant
 * state. DnD (notification-policy) access is needed API 23+ for setBypassDnd to take effect;
 * full-screen-intent access is user-revocable only on API 34+ (auto-granted below).
 */
class AlertAccessPolicyTest {

    @Test fun dndAccessByLevel() {
        assertEquals(AccessState.NOT_APPLICABLE, AlertAccessPolicy.dnd(sdkInt = 21, granted = false))
        assertEquals(AccessState.NOT_APPLICABLE, AlertAccessPolicy.dnd(sdkInt = 22, granted = true))
        assertEquals(AccessState.NEEDED, AlertAccessPolicy.dnd(sdkInt = 23, granted = false))
        assertEquals(AccessState.GRANTED, AlertAccessPolicy.dnd(sdkInt = 23, granted = true))
        assertEquals(AccessState.NEEDED, AlertAccessPolicy.dnd(sdkInt = 34, granted = false))
    }

    @Test fun fsiAccessByLevel() {
        // Below 34 the USE_FULL_SCREEN_INTENT permission is auto-granted → no prompt, regardless of
        // the (meaningless, pre-34) granted flag.
        assertEquals(AccessState.NOT_APPLICABLE, AlertAccessPolicy.fullScreenIntent(sdkInt = 33, granted = false))
        assertEquals(AccessState.NOT_APPLICABLE, AlertAccessPolicy.fullScreenIntent(sdkInt = 33, granted = true))
        assertEquals(AccessState.NEEDED, AlertAccessPolicy.fullScreenIntent(sdkInt = 34, granted = false))
        assertEquals(AccessState.GRANTED, AlertAccessPolicy.fullScreenIntent(sdkInt = 34, granted = true))
    }

    @Test fun anyNeededReflectsEitherMissing() {
        assertTrue(AlertAccessPolicy.anyNeeded(AccessState.NEEDED, AccessState.GRANTED))
        assertTrue(AlertAccessPolicy.anyNeeded(AccessState.GRANTED, AccessState.NEEDED))
        assertFalse(AlertAccessPolicy.anyNeeded(AccessState.GRANTED, AccessState.GRANTED))
        assertFalse(AlertAccessPolicy.anyNeeded(AccessState.GRANTED, AccessState.NOT_APPLICABLE))
        assertFalse(AlertAccessPolicy.anyNeeded(AccessState.NOT_APPLICABLE, AccessState.NOT_APPLICABLE))
    }
}
