package de.rochefort.childmonitor

/**
 * Decides whether the parent may talk to a child device over cleartext (#203).
 *
 * The parent records a TLS fingerprint pin the first time a child presents one
 * at QR-pairing (see [ParentSessionStore.putTlsPin]). Before this policy, the
 * transport was chosen as `if (pin != null) https else http` — so if the pin
 * ever went missing (cleared, prefs wiped, or a downgrade-inducing re-pair with
 * a fingerprint-less QR) the parent silently fell back to plaintext, sending
 * camera snapshots and the bearer token in the clear with zero warning.
 *
 * The rule here: **once a device has ever been pinned, never downgrade it to
 * cleartext.** A device that has genuinely never presented a fingerprint (e.g.
 * a child too old for on-device TLS) is still allowed over cleartext, but the
 * caller must surface an explicit insecure-connection warning.
 *
 * Pure logic so it is unit-testable on the JVM; persistence lives in
 * [ParentSessionStore] and the wiring in the parent connection code.
 */
object TransportPolicy {

    enum class Decision {
        /** A pin is recorded now — use pinned TLS. */
        PINNED_TLS,

        /** The device was pinned before but the pin is gone now — refuse to fall
         *  back to cleartext; require TLS (which will fail closed until the user
         *  re-pairs) rather than leak the stream/token. */
        REFUSE_DOWNGRADE,

        /** The device has never been pinned — cleartext is permitted, but the
         *  caller must warn the user the connection is unencrypted. */
        INSECURE_CLEARTEXT
    }

    fun decide(hasPinNow: Boolean, hasEverPinned: Boolean): Decision = when {
        hasPinNow -> Decision.PINNED_TLS
        hasEverPinned -> Decision.REFUSE_DOWNGRADE
        else -> Decision.INSECURE_CLEARTEXT
    }

    /** True when the transport must be TLS (either pinned now, or previously
     *  pinned so a downgrade is refused). Only a never-pinned device is cleartext. */
    fun requireTls(hasPinNow: Boolean, hasEverPinned: Boolean): Boolean =
        decide(hasPinNow, hasEverPinned) != Decision.INSECURE_CLEARTEXT

    /** True only when cleartext is being used because the device was never
     *  pinned — the case that warrants a user-facing insecure-connection warning. */
    fun isInsecureCleartext(hasPinNow: Boolean, hasEverPinned: Boolean): Boolean =
        decide(hasPinNow, hasEverPinned) == Decision.INSECURE_CLEARTEXT
}
