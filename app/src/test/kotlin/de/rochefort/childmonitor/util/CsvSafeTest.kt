package de.rochefort.childmonitor.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** #368: shared CSV cell escaping + CSV-injection guard. */
class CsvSafeTest {

    @Test fun nullOrEmpty_isEmpty() {
        assertEquals("", CsvSafe.cell(null))
        assertEquals("", CsvSafe.cell(""))
    }

    @Test fun plainValue_isQuoted() {
        assertEquals("\"hello\"", CsvSafe.cell("hello"))
    }

    @Test fun embeddedQuotes_areDoubled() {
        assertEquals("\"a\"\"b\"", CsvSafe.cell("a\"b"))
    }

    @Test fun commasAndNewlinesSurviveInsideQuotes() {
        assertEquals("\"a,b\"", CsvSafe.cell("a,b"))
    }

    @Test fun formulaTriggers_getLeadingApostrophe() {
        // =, +, -, @, tab, CR, LF → prefix a single quote so spreadsheets treat it as text.
        assertEquals("\"'=SUM(A1:A2)\"", CsvSafe.cell("=SUM(A1:A2)"))
        assertEquals("\"'+1\"", CsvSafe.cell("+1"))
        assertEquals("\"'-1\"", CsvSafe.cell("-1"))
        assertEquals("\"'@x\"", CsvSafe.cell("@x"))
        assertEquals("\"'\tx\"", CsvSafe.cell("\tx"))
        assertEquals("\"'\rx\"", CsvSafe.cell("\rx"))
        assertEquals("\"'\nx\"", CsvSafe.cell("\nx"))
    }

    @Test fun leadingWhitespaceBeforeFormula_isGuarded() {
        // Some importers trim leading whitespace then evaluate — guard " =cmd" too.
        assertEquals("\"' =SUM(A1:A2)\"", CsvSafe.cell(" =SUM(A1:A2)"))
        assertEquals("\"'   -1\"", CsvSafe.cell("   -1"))
        // ...but ordinary leading whitespace (no formula char) is left alone.
        assertEquals("\" hello\"", CsvSafe.cell(" hello"))
    }

    @Test fun normalLeadingChar_noApostrophe() {
        assertEquals("\"name\"", CsvSafe.cell("name"))
        assertEquals("\"3 apples\"", CsvSafe.cell("3 apples"))
    }
}
