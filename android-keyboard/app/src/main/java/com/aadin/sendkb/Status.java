package com.aadin.sendkb;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Reads the status tag that the discord-status-web page shows. That tag is
 * set with the /tag Discord command and stored in Lanyard, which anyone can
 * read:
 *
 *   https://api.lanyard.rest/v1/users/<id>   ->   data.kv.tag
 *
 * Tag "At school"  ->  what = "at school"
 *                      reply = "Aadin is at school, pls message him later."
 * No tag (/tag clear) ->  null, which means: no auto-reply at all.
 *
 * With the screen off the network can be flaky, so a failed lookup is retried
 * and, if it still fails, the last answer we got is used for up to 2 hours.
 */
final class Status {
    static final String DISCORD_USER_ID = "1382308851814240298";
    static final String MY_NAME = "Aadin";
    private static final String API_URL =
            "https://api.lanyard.rest/v1/users/" + DISCORD_USER_ID;

    private static final int ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 1500;
    private static final long MAX_STALE_MS = 2L * 60 * 60 * 1000;
    private static final String TAG = "StatusReply";

    /** What you are doing right now, and the fixed line built from it. */
    static final class Info {
        final String what;    // "at school"
        final String reply;   // "Aadin is at school, pls message him later."

        Info(String what) {
            this.what = what;
            this.reply = MY_NAME + " is " + what + ", pls message him later.";
        }
    }

    private Status() { }

    /**
     * The current status, or null when no tag is set.
     * Throws only if Lanyard can't be reached and there is no recent answer
     * saved (so we never reply from old data).
     */
    static Info current(Context ctx) throws Exception {
        Exception failure = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                Info info = infoFor(fetchTag());
                save(ctx, info);
                return info;
            } catch (Exception e) {
                failure = e;
                Log.w(TAG, "status lookup failed (attempt " + (attempt + 1) + ")", e);
                if (attempt < ATTEMPTS - 1) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        SharedPreferences sp = ctx.getSharedPreferences("status", Context.MODE_PRIVATE);
        if (sp.contains("time")
                && System.currentTimeMillis() - sp.getLong("time", 0) < MAX_STALE_MS) {
            Log.w(TAG, "offline, using the last known status");
            String saved = sp.getString("what", "");
            return saved.isEmpty() ? null : new Info(saved);
        }
        throw failure;
    }

    /** Just the fixed line (used by the app icon check). */
    static String currentReply(Context ctx) throws Exception {
        Info info = current(ctx);
        return info == null ? null : info.reply;
    }

    private static Info infoFor(String tag) {
        if (tag == null) return null;
        String what = clean(tag);
        return what.isEmpty() ? null : new Info(what);
    }

    private static void save(Context ctx, Info info) {
        ctx.getSharedPreferences("status", Context.MODE_PRIVATE).edit()
                .putString("what", info == null ? "" : info.what)
                .putLong("time", System.currentTimeMillis())
                .apply();
    }

    private static String fetchTag() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API_URL).openConnection();
        try {
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            c.setRequestProperty("User-Agent", "status-auto-reply");
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("Lanyard answered " + code);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream is = c.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            }
            JSONObject data = new JSONObject(bo.toString("UTF-8")).optJSONObject("data");
            if (data == null) throw new Exception("Lanyard sent no data");
            JSONObject kv = data.optJSONObject("kv");
            if (kv == null) return null;
            String tag = kv.optString("tag", "").trim();
            return tag.isEmpty() ? null : tag;
        } finally {
            c.disconnect();
        }
    }

    /**
     * "🏫 At school" -> "at school": drop emoji/symbols at both ends and
     * lower-case the first letter so it reads naturally after "is".
     */
    static String clean(String s) {
        int start = 0, end = s.length();
        while (start < end) {
            int cp = s.codePointAt(start);
            if (Character.isLetterOrDigit(cp)) break;
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = s.codePointBefore(end);
            if (Character.isLetterOrDigit(cp)) break;
            end -= Character.charCount(cp);
        }
        String t = s.substring(start, end).trim();
        if (t.length() > 1 && Character.isUpperCase(t.charAt(0))
                && Character.isLowerCase(t.charAt(1))) {
            t = Character.toLowerCase(t.charAt(0)) + t.substring(1);
        }
        return t;
    }
}
