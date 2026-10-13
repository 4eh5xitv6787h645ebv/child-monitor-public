/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Child Monitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */
package de.rochefort.childmonitor.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * #310: serialises a set of SharedPreferences (file → key → value) to/from the backup JSON, with
 * the type of each value tagged so it round-trips exactly (SharedPreferences holds String, Boolean,
 * Int, Long, Float, or Set&lt;String&gt;).
 *
 * [parse] treats its input as UNTRUSTED (a backup file could be attacker-supplied and the user
 * tricked into importing it — GCM proves integrity, not benignity): it validates the format/version,
 * rejects unknown type tags, and enforces hard caps on counts/sizes so a malicious blob can't OOM or
 * wedge the restore. The Android apply layer adds a file allow-list on top. Pure / JVM-testable.
 */
object BackupSerializer {

    const val FORMAT = "child-monitor-backup"
    const val VERSION = 1

    // Untrusted-input caps.
    const val MAX_FILES = 32
    const val MAX_KEYS_PER_FILE = 8000
    const val MAX_STRING_LEN = 200_000
    const val MAX_SET_SIZE = 8000

    /** Thrown when the input isn't a valid backup payload (bad format/version/type, or over a cap). */
    class Invalid(message: String) : Exception(message)

    data class Backup(
        val version: Int,
        val createdAtMs: Long,
        /** file → key → value (String / Boolean / Int / Long / Float / Set&lt;String&gt;). */
        val prefs: Map<String, Map<String, Any>>,
    )

    fun serialize(prefs: Map<String, Map<String, Any?>>, createdAtMs: Long): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("createdAtMs", createdAtMs)
        val prefsObj = JSONObject()
        for ((file, entries) in prefs) {
            val fileObj = JSONObject()
            for ((key, value) in entries) {
                if (value == null) continue
                fileObj.put(key, encodeValue(file, key, value))
            }
            prefsObj.put(file, fileObj)
        }
        root.put("prefs", prefsObj)
        return root.toString()
    }

    private fun encodeValue(file: String, key: String, value: Any): JSONObject {
        val e = JSONObject()
        when (value) {
            is String -> { e.put("t", "s"); e.put("v", value) }
            is Boolean -> { e.put("t", "b"); e.put("v", value) }
            is Int -> { e.put("t", "i"); e.put("v", value) }
            is Long -> { e.put("t", "l"); e.put("v", value) }
            // org.json rejects non-finite numbers (a NaN/Infinity gain — e.g. coerceIn(NaN) — would
            // throw a raw JSONException and crash the whole backup). Store finite or 0.0.
            is Float -> { e.put("t", "f"); e.put("v", if (value.isFinite()) value.toDouble() else 0.0) }
            is Set<*> -> {
                e.put("t", "ss")
                val arr = JSONArray()
                for (item in value) arr.put(item?.toString() ?: "")
                e.put("v", arr)
            }
            else -> throw Invalid("unsupported value type for $file/$key: ${value.javaClass.name}")
        }
        return e
    }

    fun parse(json: String): Backup {
        // org.json's parser is recursive-descent: deeply-nested input can StackOverflowError (an
        // Error, not an Exception). Catch both so a hostile blob can only ever produce Invalid.
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw Invalid("not JSON")
        } catch (e: StackOverflowError) {
            throw Invalid("input too deeply nested")
        }
        if (root.optString("format") != FORMAT) throw Invalid("not a child-monitor backup")
        val version = root.optInt("version", -1)
        if (version != VERSION) throw Invalid("unsupported backup version $version")
        val createdAtMs = root.optLong("createdAtMs", 0L)
        val prefsObj = root.optJSONObject("prefs") ?: throw Invalid("missing prefs")
        if (prefsObj.length() > MAX_FILES) throw Invalid("too many pref files")

        val out = LinkedHashMap<String, Map<String, Any>>()
        val fileNames = prefsObj.keys()
        while (fileNames.hasNext()) {
            val file = fileNames.next()
            val fileObj = prefsObj.optJSONObject(file) ?: throw Invalid("bad entry for $file")
            if (fileObj.length() > MAX_KEYS_PER_FILE) throw Invalid("too many keys in $file")
            val entries = LinkedHashMap<String, Any>()
            val keys = fileObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val e = fileObj.optJSONObject(key) ?: throw Invalid("bad value for $file/$key")
                entries[key] = decodeValue(file, key, e)
            }
            out[file] = entries
        }
        return Backup(version, createdAtMs, out)
    }

    private fun decodeValue(file: String, key: String, e: JSONObject): Any {
        val where = "$file/$key"
        // Validate the JSON value KIND matches the tag — reject type-confused payloads (e.g. a
        // {t:"s"} carrying a number) rather than silently coercing. NB numeric tags (i/l/f) only
        // require a JSON Number: JSON can't tell int from long (org.json re-parses a small long as
        // Integer), so the `t` tag is the type authority and we coerce the number to it.
        val v = e.opt("v")
        return when (val t = e.optString("t")) {
            "s" -> {
                if (v !is String) throw Invalid("expected string for $where")
                if (v.length > MAX_STRING_LEN) throw Invalid("string too long for $where")
                v
            }
            "b" -> if (v is Boolean) v else throw Invalid("expected boolean for $where")
            // org.json yields Integer for an int-fitting JSON number and Long otherwise, so "i" must be
            // an Integer (a Long/Double here is out-of-range or fractional — reject, don't truncate/wrap).
            "i" -> if (v is Int) v else throw Invalid("expected int for $where")
            "l" -> when (v) {
                is Int -> v.toLong()   // a small long round-trips through JSON as an Integer
                is Long -> v
                else -> throw Invalid("expected long for $where")
            }
            "f" -> {
                // Any JSON Number — org.json may hand back Double or BigDecimal for a decimal, and
                // Integer/Long for a whole-number float. Reject only non-numbers + non-finite results.
                if (v !is Number) throw Invalid("expected number for $where")
                val f = v.toFloat()
                if (!f.isFinite()) throw Invalid("non-finite float for $where")
                f
            }
            "ss" -> {
                if (v !is JSONArray) throw Invalid("bad set for $where")
                if (v.length() > MAX_SET_SIZE) throw Invalid("set too large for $where")
                val set = LinkedHashSet<String>(v.length())
                for (i in 0 until v.length()) {
                    val item = v.opt(i)
                    if (item !is String) throw Invalid("non-string set item for $where")
                    if (item.length > MAX_STRING_LEN) throw Invalid("set item too long for $where")
                    set.add(item)
                }
                set
            }
            else -> throw Invalid("unknown type '$t' for $where")
        }
    }
}
