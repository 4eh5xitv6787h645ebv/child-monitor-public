package de.rochefort.childmonitor.perf

/**
 * Runtime governor profiles (quality/balanced/battery-saver).
 * Implements CM-PERF-003.
 *
 * Combines user-selected profile with live signals (battery saver,
 * thermal profile) to yield a set of effective knobs that other
 * subsystems read to tune themselves.
 */
class RuntimeGovernor {

    enum class Profile { QUALITY, BALANCED, BATTERY_SAVER }

    data class Inputs(
        val profile: Profile = Profile.BALANCED,
        val batterySaverOn: Boolean = false,
        val thermalPaused: Boolean = false,
        val charging: Boolean = false
    )

    data class Effective(
        val snapshotIntervalMs: Int,
        val audioSampleRate: Int,
        val jitterBufferMs: Int,
        val pairingActive: Boolean
    )

    fun resolve(inputs: Inputs): Effective {
        val baseProfile = if (inputs.batterySaverOn && !inputs.charging) Profile.BATTERY_SAVER
            else inputs.profile
        if (inputs.thermalPaused) {
            return Effective(
                snapshotIntervalMs = Int.MAX_VALUE,
                audioSampleRate = 8000,
                jitterBufferMs = 120,
                pairingActive = false
            )
        }
        return when (baseProfile) {
            Profile.QUALITY -> Effective(
                snapshotIntervalMs = 800,
                audioSampleRate = 16000,
                jitterBufferMs = 80,
                pairingActive = true
            )
            Profile.BALANCED -> Effective(
                snapshotIntervalMs = 1_500,
                audioSampleRate = 8000,
                jitterBufferMs = 100,
                pairingActive = true
            )
            Profile.BATTERY_SAVER -> Effective(
                snapshotIntervalMs = 4_000,
                audioSampleRate = 8000,
                jitterBufferMs = 160,
                pairingActive = true
            )
        }
    }
}
