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
package de.rochefort.childmonitor.parent

/**
 * #354: classify the child's `POST /api/session/refresh` HTTP status so the parent reacts correctly.
 * The child now returns 429 for a transient rate-limit (distinct from 401 for a genuinely
 * invalid/revoked refresh credential), so the parent can drop a definitively-dead credential and
 * prompt re-pair on 401 while keeping (and backing off) the credential on 429. Pure → unit-testable.
 */
object RefreshRenewPolicy {

    enum class Outcome {
        /** 200 — a fresh bearer was issued; read it from the body. */
        RENEW,

        /** 429 — rate-limited; keep the credential and back off / retry later. */
        RATE_LIMITED,

        /** 401 — the refresh credential is invalid/revoked; drop it and prompt re-pair. */
        REVOKED,

        /** Any other non-200 (network/server error); keep the credential, treat as transient. */
        TRANSIENT,
    }

    fun classify(httpCode: Int): Outcome = when (httpCode) {
        200 -> Outcome.RENEW
        429 -> Outcome.RATE_LIMITED
        401 -> Outcome.REVOKED
        else -> Outcome.TRANSIENT
    }
}
