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
 *
 * You should have received a copy of the GNU General Public License
 * along with Child Monitor. If not, see <http://www.gnu.org/licenses/>.
 */
package de.rochefort.childmonitor.util

/**
 * #368: shared RFC4180 cell escaping + OWASP CSV-injection guard for all on-device exports
 * (previously duplicated in SleepLogExport and BabyDashboardActivity).
 *
 * A value beginning with '=', '+', '-', '@', tab, CR, or LF is evaluated as a formula by
 * Excel / LibreOffice / Google Sheets when the shared file is opened, so we prepend a single
 * quote to force it to render as literal text, then RFC4180-quote the whole cell (doubling any
 * embedded quotes). Pure / JVM-testable.
 */
object CsvSafe {
    fun cell(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val first = s[0]
        // Also guard a formula char hidden behind leading whitespace (e.g. " =cmd"): some importers
        // trim, then evaluate. trimStart() also strips tab/CR/LF, so keep the raw first-char checks
        // for those (they're both whitespace and injection triggers).
        val trimmedFirst = s.trimStart().firstOrNull()
        val needsPrefix = first == '=' || first == '+' || first == '-' ||
            first == '@' || first == '\t' || first == '\r' || first == '\n' ||
            trimmedFirst == '=' || trimmedFirst == '+' || trimmedFirst == '-' || trimmedFirst == '@'
        val safe = if (needsPrefix) "'$s" else s
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
}
