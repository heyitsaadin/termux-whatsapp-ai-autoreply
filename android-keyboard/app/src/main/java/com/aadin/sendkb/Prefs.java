package com.aadin.sendkb;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** Everything the app remembers. It all stays on this phone. */
final class Prefs {
    private static final String FILE = "settings";
    private static final String ENABLED = "enabled";
    private static final String TAG = "status_tag";
    private static final String NAME = "my_name";
    private static final String RECENT = "recent_places";
    private static final String BIAS_LAT = "bias_lat";
    private static final String BIAS_LON = "bias_lon";
    private static final int RECENT_MAX = 5;

    private Prefs() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---------- Start / Stop ----------

    /** On by default, so an existing install keeps answering after the update. */
    static boolean isEnabled(Context c) {
        return sp(c).getBoolean(ENABLED, true);
    }

    static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean(ENABLED, on).apply();
    }

    // ---------- your status ----------

    /** The status text, like "Travelling to Goa". Empty means none is set. */
    static String getTag(Context c) {
        String t = sp(c).getString(TAG, "");
        return t == null ? "" : t.trim();
    }

    static void setTag(Context c, String tag) {
        sp(c).edit().putString(TAG, tag == null ? "" : tag.trim()).apply();
    }

    static void clearTag(Context c) {
        sp(c).edit().remove(TAG).apply();
    }

    /** The name used in the reply ("Sam is at school..."). Empty means "I'm at school...". */
    static String getName(Context c) {
        String n = sp(c).getString(NAME, "");
        return n == null ? "" : n.trim();
    }

    static void setName(Context c, String name) {
        sp(c).edit().putString(NAME, name == null ? "" : name.trim()).apply();
    }

    // ---------- places ----------

    /** Places you used before, newest first. */
    static List<String> recentPlaces(Context c) {
        List<String> out = new ArrayList<>();
        String raw = sp(c).getString(RECENT, "");
        if (raw == null) return out;
        for (String s : raw.split("\n")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    static void addRecentPlace(Context c, String place) {
        String p = place == null ? "" : place.trim();
        if (p.isEmpty()) return;
        List<String> list = recentPlaces(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).equalsIgnoreCase(p)) list.remove(i);
        }
        list.add(0, p);
        while (list.size() > RECENT_MAX) list.remove(list.size() - 1);
        sp(c).edit().putString(RECENT, String.join("\n", list)).apply();
    }

    /** Where you last were, so place search shows nearby results first. */
    static boolean hasBias(Context c) {
        return sp(c).contains(BIAS_LAT) && sp(c).contains(BIAS_LON);
    }

    static double biasLat(Context c) {
        return sp(c).getFloat(BIAS_LAT, 0f);
    }

    static double biasLon(Context c) {
        return sp(c).getFloat(BIAS_LON, 0f);
    }

    static void setBias(Context c, double lat, double lon) {
        sp(c).edit().putFloat(BIAS_LAT, (float) lat).putFloat(BIAS_LON, (float) lon).apply();
    }
}
