package de.rochefort.childmonitor.events

/** Parent-side freshness/de-duplication gate for MQTT-backed room sensor alerts (#315). */
object RemoteEnvironmentAlertPolicy {
    private const val MAX_AGE_MS = 5 * 60 * 1000L

    fun shouldNotify(
        alert: String?,
        updatedAtEpochMs: Long,
        nowEpochMs: Long,
        alreadyHandled: Boolean,
        muted: Boolean,
    ): Boolean = !alert.isNullOrBlank() &&
        updatedAtEpochMs > 0L &&
        nowEpochMs >= updatedAtEpochMs &&
        nowEpochMs - updatedAtEpochMs <= MAX_AGE_MS &&
        !alreadyHandled &&
        !muted
}
