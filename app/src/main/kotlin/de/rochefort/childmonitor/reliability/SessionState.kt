package de.rochefort.childmonitor.reliability

/**
 * Finite-state machine states for a parent/child monitoring session.
 * Implements CM-REL-002: crash-safe FSM for parent/child session lifecycle.
 *
 * Transitions enforced:
 *
 *   IDLE ─► STARTING ─► CONNECTING ─► CONNECTED ─► ACTIVE
 *            │              │            │          │
 *            ▼              ▼            ▼          ▼
 *          FAILED       FAILED       DRAINING    DRAINING ─► CLOSED
 *            │              │                                 ▲
 *            └──────────────┴─────────────────────────────────┘
 *
 * Persisted checkpoints are used to recover after process death (CM-REL-006).
 */
enum class SessionState {
    IDLE,
    STARTING,
    CONNECTING,
    CONNECTED,
    ACTIVE,
    DRAINING,
    FAILED,
    CLOSED;

    fun canTransitionTo(next: SessionState): Boolean = when (this) {
        IDLE -> next == STARTING || next == CLOSED
        STARTING -> next == CONNECTING || next == FAILED || next == CLOSED
        CONNECTING -> next == CONNECTED || next == FAILED || next == DRAINING || next == CLOSED
        CONNECTED -> next == ACTIVE || next == DRAINING || next == FAILED || next == CLOSED
        ACTIVE -> next == DRAINING || next == FAILED || next == CLOSED
        DRAINING -> next == CLOSED || next == FAILED
        FAILED -> next == STARTING || next == CLOSED
        CLOSED -> next == STARTING // re-arm for next session
    }
}
