package com.aadin.sendkb;

import android.content.Context;

/** The on/off switch behind the Start / Stop button in the app. */
final class Prefs {
    private static final String FILE = "settings";
    private static final String ENABLED = "enabled";

    private Prefs() { }

    /** On by default, so an existing install keeps answering after the update. */
    static boolean isEnabled(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(ENABLED, true);
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).apply();
    }
}
