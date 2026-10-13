package de.rochefort.childmonitor.reliability

/** Pure timing/action policy for recovery after a possibly unstable boot. */
object DeferredBootPolicy {
    const val START_DELAY_MS = 60_000L

    private const val BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    private const val LOCKED_BOOT_COMPLETED = "android.intent.action.LOCKED_BOOT_COMPLETED"
    private const val QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"

    fun isBootAction(action: String?): Boolean =
        action == BOOT_COMPLETED || action == LOCKED_BOOT_COMPLETED || action == QUICKBOOT_POWERON

    /** Direct boot is intentionally ignored; fully booted and OEM quick-boot signals schedule. */
    fun shouldScheduleAfter(action: String?): Boolean =
        action == BOOT_COMPLETED || action == QUICKBOOT_POWERON
}
