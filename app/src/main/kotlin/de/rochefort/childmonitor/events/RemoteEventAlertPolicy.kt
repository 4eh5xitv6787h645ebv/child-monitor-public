package de.rochefort.childmonitor.events

/** Pure parent-side gate for child event notifications. */
object RemoteEventAlertPolicy {
    const val MAX_EVENT_AGE_MS = 30_000L

    fun shouldNotify(
        type: String,
        wouldAlert: Boolean,
        ageMs: Long,
        alreadyHandled: Boolean,
        muted: Boolean,
    ): Boolean {
        if (alreadyHandled || muted || !wouldAlert) return false
        if (ageMs !in 0L..MAX_EVENT_AGE_MS) return false
        return type == "cry" || type == "cough" || type == "motion"
    }
}
