package de.rochefort.childmonitor.reliability

import org.junit.Test
import kotlin.test.assertTrue

class BackoffPolicyTest {

    @Test fun `delay grows exponentially up to cap`() {
        val policy = BackoffPolicy(
            initialMs = 100L,
            maxMs = 2_000L,
            jitterRatio = 0.0,
            loopWindowMs = 120_000L,
            loopThreshold = 100
        )
        val d1 = policy.delay()
        val d2 = policy.delay()
        val d3 = policy.delay()
        assertTrue(d1 == 100L)
        assertTrue(d2 in 150L..250L || d2 == 200L)
        assertTrue(d3 >= d2)
    }

    @Test fun `onSuccess resets attempts`() {
        val policy = BackoffPolicy(initialMs = 100L, maxMs = 2_000L, jitterRatio = 0.0)
        policy.delay(); policy.delay(); policy.delay()
        policy.onSuccess()
        val after = policy.delay()
        assertTrue(after == 100L)
    }

    @Test fun `inRestartLoop fires once threshold exceeded within window`() {
        val policy = BackoffPolicy(
            initialMs = 1,
            maxMs = 1,
            jitterRatio = 0.0,
            loopWindowMs = 60_000L,
            loopThreshold = 3
        )
        repeat(5) { policy.delay() }
        assertTrue(policy.inRestartLoop())
    }
}
