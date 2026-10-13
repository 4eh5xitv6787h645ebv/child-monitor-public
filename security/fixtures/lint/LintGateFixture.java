package de.rochefort.childmonitor.lintgate;

import android.app.Activity;

/** Intentionally invalid for minSdk 34; copied into app sources only by prove-lint-gate.sh. */
final class LintGateFixture {
    private LintGateFixture() {}

    static Object callApi35FromApi34(Activity activity) {
        return activity.getCaller();
    }
}
