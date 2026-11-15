package com.aadin.sendkb;

import android.content.Context;

/** The on/off switch behind Start / Stop, plus the saved Lanyard key. */
final class Prefs {
    private static final String FILE = "settings";
    private static final String ENABLED = "enabled";
    private static final String LANYARD_KEY = "lanyard_key";

    private Prefs() { }

    /** On by default, so an existing install keeps answering after the update. */
    static boolean isEnabled(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(ENABLED, true);
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).apply();
    }

    /** The Lanyard API key you typed in the app, or null if none is saved. Stays on this phone. */
    static String getKey(Context c) {
        String k = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(LANYARD_KEY, null);
        return (k == null || k.trim().isEmpty()) ? null : k.trim();
    }

    static void setKey(Context c, String key) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(LANYARD_KEY, key).apply();
    }

    static void clearKey(Context c) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(LANYARD_KEY).apply();
    }
}
