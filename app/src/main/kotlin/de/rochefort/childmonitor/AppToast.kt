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
package de.rochefort.childmonitor

import android.content.Context
import android.util.Log
import android.widget.Toast

/**
 * Central wrapper for the app's status toasts so the user can silence them with one
 * setting ("Suppress pop-up messages" on the home screen, key [KEY_SUPPRESS]). All the
 * monitoring-flow [Toast]s route through here; when suppression is on, [show] is a no-op.
 *
 * Drop-in for `Toast.makeText(ctx, msg, dur).show()` — the two overloads mirror the
 * String/CharSequence and string-resource forms of [Toast.makeText]. Opt-in: the pref
 * defaults to false, so behaviour is unchanged until the user enables it.
 */
object AppToast {
    private const val TAG = "AppToast"
    const val PREFS = "ListenPreferences"
    const val KEY_SUPPRESS = "suppressToasts"

    fun suppressed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SUPPRESS, false)

    fun show(context: Context, text: CharSequence?, duration: Int) {
        if (suppressed(context)) { Log.i(TAG, "Toast suppressed (pop-up messages disabled)"); return }
        Toast.makeText(context, text, duration).show()
    }

    fun show(context: Context, resId: Int, duration: Int) {
        if (suppressed(context)) { Log.i(TAG, "Toast suppressed (pop-up messages disabled)"); return }
        Toast.makeText(context, resId, duration).show()
    }
}
