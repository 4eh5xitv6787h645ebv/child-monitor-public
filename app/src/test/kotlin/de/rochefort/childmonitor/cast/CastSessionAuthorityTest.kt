package de.rochefort.childmonitor.cast

import java.security.SecureRandom
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CastSessionAuthorityTest {
    @Test fun `grant binds to first renderer and rejects a different host`() {
        var now = 1_000L
        val authority = CastSessionAuthority({ now }, SecureRandom(), ttlMs = 10_000L)
        val grant = authority.issue("parent-a")

        assertTrue(CastSessionAuthority.isTokenShapeValid(grant.token))
        assertTrue(authority.validateAndBind(grant.token, "/192.168.1.50"))
        assertTrue(authority.validateAndBind(grant.token, "192.168.1.50"))
        assertFalse(authority.validateAndBind(grant.token, "192.168.1.51"))
        assertEquals(1, authority.activeCount())
    }

    @Test fun `grant expires and is never persisted or refreshed by reads`() {
        var now = 5_000L
        val authority = CastSessionAuthority({ now }, SecureRandom(), ttlMs = 2_000L)
        val grant = authority.issue("parent-a")
        now = 7_000L

        assertFalse(authority.validateAndBind(grant.token, "10.0.0.2"))
        assertEquals(0, authority.activeCount())
    }

    @Test fun `new grant replaces the same parent's previous capability`() {
        val authority = CastSessionAuthority()
        val first = authority.issue("parent-a")
        val second = authority.issue("parent-a")

        assertNotEquals(first.token, second.token)
        assertFalse(authority.validateAndBind(first.token, "10.0.0.2"))
        assertTrue(authority.validateAndBind(second.token, "10.0.0.2"))
        assertEquals(1, authority.revokeForParent("parent-a"))
        assertFalse(authority.validateAndBind(second.token, "10.0.0.2"))
    }

    @Test fun `malformed tokens and addresses fail closed`() {
        val authority = CastSessionAuthority()
        val grant = authority.issue("parent-a")

        assertFalse(authority.validateAndBind(null, "10.0.0.2"))
        assertFalse(authority.validateAndBind("../${grant.token}", "10.0.0.2"))
        assertFalse(authority.validateAndBind(grant.token, null))
        assertFalse(authority.validateAndBind(grant.token, "10.0.0.2\r\nspoof"))
    }
}
