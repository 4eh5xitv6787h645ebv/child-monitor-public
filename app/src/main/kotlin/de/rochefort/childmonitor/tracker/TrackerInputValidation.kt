package de.rochefort.childmonitor.tracker

/** Shared numeric boundary rules for caregiver-entered tracker values (#459). */
object TrackerInputValidation {
    fun optionalInt(raw: String, range: IntRange): Boolean {
        val value = raw.trim()
        return value.isEmpty() || value.toIntOrNull()?.let { it in range } == true
    }

    fun requiredInt(raw: String, range: IntRange): Boolean {
        val value = raw.trim()
        return value.isNotEmpty() && value.toIntOrNull()?.let { it in range } == true
    }

    fun optionalFiniteDouble(raw: String, range: ClosedFloatingPointRange<Double>): Boolean {
        val value = raw.trim()
        if (value.isEmpty()) return true
        val parsed = value.toDoubleOrNull() ?: return false
        return parsed.isFinite() && parsed in range
    }

    fun requiredFiniteDouble(raw: String, range: ClosedFloatingPointRange<Double>): Boolean =
        raw.trim().isNotEmpty() && optionalFiniteDouble(raw, range)

    fun anyEntered(vararg raw: String): Boolean = raw.any { it.trim().isNotEmpty() }
}
