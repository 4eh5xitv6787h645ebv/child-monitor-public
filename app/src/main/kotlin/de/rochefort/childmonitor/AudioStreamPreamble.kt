/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package de.rochefort.childmonitor

import java.io.PushbackInputStream

/**
 * Distinguishes the child's short, line-based CMHELLO rejection from the raw audio bytes that
 * otherwise begin immediately. Bytes that are not an error preamble are pushed back untouched.
 */
object AudioStreamPreamble {
    enum class Result { AUDIO, UNAUTHORIZED, SERVER_ERROR, CLOSED }

    private val errorPrefix = "CMERROR ".toByteArray(Charsets.US_ASCII)
    private const val MAX_LINE_BYTES = 128

    fun probe(input: PushbackInputStream): Result {
        val captured = ArrayList<Byte>(MAX_LINE_BYTES)
        for (expected in errorPrefix) {
            val value = input.read()
            if (value < 0) return Result.CLOSED
            captured.add(value.toByte())
            if (value.toByte() != expected) {
                unread(input, captured)
                return Result.AUDIO
            }
        }

        while (captured.size < MAX_LINE_BYTES) {
            val value = input.read()
            if (value < 0 || value == '\n'.code) break
            captured.add(value.toByte())
        }
        val line = captured.toByteArray().toString(Charsets.US_ASCII).trim()
        return if (line.startsWith("CMERROR 401")) Result.UNAUTHORIZED else Result.SERVER_ERROR
    }

    private fun unread(input: PushbackInputStream, captured: List<Byte>) {
        input.unread(ByteArray(captured.size) { captured[it] })
    }
}
