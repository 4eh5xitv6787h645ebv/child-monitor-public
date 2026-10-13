package de.rochefort.childmonitor.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #296: pure foreground app-lock state machine. These tests pin the tricky lifecycle invariants —
 * cold start locks, background→foreground re-locks, in-app navigation does NOT lock, and the unlock
 * prompt round-trip does NOT cause a re-lock loop.
 */
class AppLockGateTest {

    @Test fun coldStartLocksWhenEnabled() {
        val g = AppLockGate()
        assertTrue("first foreground with lock on must prompt", g.onActivityStarted(enabled = true))
    }

    @Test fun coldStartDoesNotLockWhenDisabled() {
        val g = AppLockGate()
        assertFalse(g.onActivityStarted(enabled = false))
    }

    @Test fun inAppNavigationDoesNotLock() {
        val g = AppLockGate()
        assertTrue(g.onActivityStarted(enabled = true)) // cold start → lock
        g.onUnlockSucceeded()
        // navigate A→B: B starts before A stops (count goes 1→2), not a foreground transition
        assertFalse("starting a 2nd activity must not re-lock", g.onActivityStarted(enabled = true))
        g.onActivityStopped() // A stops (count 2→1) — still foreground
        assertFalse(g.onActivityStarted(enabled = true)) // C starts — still no lock
    }

    @Test fun backgroundThenForegroundRelocks() {
        val g = AppLockGate()
        g.onActivityStarted(enabled = true); g.onUnlockSucceeded() // authenticated
        val wentBg = g.onActivityStopped()                          // count 1→0
        assertTrue("count 0 means backgrounded", wentBg)
        g.onEnteredBackground()                                     // debounce fired, truly backgrounded
        assertTrue("returning from background must re-lock", g.onActivityStarted(enabled = true))
    }

    @Test fun unlockRoundTripDoesNotRelock() {
        val g = AppLockGate()
        assertTrue(g.onActivityStarted(enabled = true)) // cold start → lock
        g.beginUnlock()                                  // app launches LockActivity / confirm intent
        // the confirm UI covers our activities → they stop (count → 0) during a possibly-slow biometric
        g.onActivityStopped()
        g.onEnteredBackground()                          // even if the debounce fires, unlocking → no reset
        // confirm returns; our activity restarts while still unlocking
        assertFalse("must not re-lock during the unlock round-trip", g.onActivityStarted(enabled = true))
        g.onUnlockSucceeded()
        // the underlying activity re-foregrounds after LockActivity finishes → must stay unlocked
        assertFalse("must not re-lock right after a successful unlock", g.onActivityStarted(enabled = true))
    }

    @Test fun cancelledUnlockStaysUnauthenticated() {
        val g = AppLockGate()
        assertTrue(g.onActivityStarted(enabled = true))
        g.beginUnlock()
        g.onUnlockFailedOrCancelled()
        assertFalse(g.isAuthenticated())
        // next foreground still demands unlock
        g.onActivityStopped(); g.onEnteredBackground()
        assertTrue(g.onActivityStarted(enabled = true))
    }

    @Test fun staysUnlockedAcrossNavigationUntilBackground() {
        val g = AppLockGate()
        g.onActivityStarted(enabled = true); g.onUnlockSucceeded()
        assertTrue(g.isAuthenticated())
        // lots of in-app activity churn, never hitting count 0
        g.onActivityStarted(enabled = true); g.onActivityStopped()
        g.onActivityStarted(enabled = true); g.onActivityStopped()
        assertTrue("still authenticated without a real background", g.isAuthenticated())
    }

    @Test fun shouldLockOnCreateCoversColdStartButNotAfterUnlock() {
        val g = AppLockGate()
        assertTrue("fresh + enabled → pre-lock on create", g.shouldLockOnCreate(enabled = true))
        assertFalse("disabled → never pre-lock", g.shouldLockOnCreate(enabled = false))
        g.beginUnlock()
        assertFalse("mid-unlock → don't re-launch the lock", g.shouldLockOnCreate(enabled = true))
        g.onUnlockSucceeded()
        assertFalse("authenticated → new activities don't pre-lock", g.shouldLockOnCreate(enabled = true))
    }

    @Test fun strayStopDoesNotUnderflowCount() {
        val g = AppLockGate()
        g.onActivityStopped() // stray stop with nothing started — must NOT drive count to -1
        // if the count had underflowed to -1, this start would be -1→0 (not a foreground transition)
        // and would NOT lock; a correct guard keeps count at 0 so a fresh start is a cold foreground.
        assertTrue("a fresh start after a stray stop is still a cold foreground", g.onActivityStarted(enabled = true))
    }

    @Test fun disabledNeverLocksEvenAcrossBackground() {
        val g = AppLockGate()
        assertFalse(g.onActivityStarted(enabled = false))
        g.onActivityStopped(); g.onEnteredBackground()
        assertFalse(g.onActivityStarted(enabled = false))
    }

    @Test fun requireReauthDropsAuthenticationSoNextStartLocks() {
        val g = AppLockGate()
        g.beginUnlock(); g.onUnlockSucceeded()
        assertFalse("authenticated -> no pre-lock", g.shouldLockOnCreate(enabled = true))
        g.requireReauth() // #375 lock-on-enable
        assertTrue("after requireReauth, a new activity pre-locks", g.shouldLockOnCreate(enabled = true))
    }
}
