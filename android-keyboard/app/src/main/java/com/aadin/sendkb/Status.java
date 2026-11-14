package com.aadin.sendkb;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Builds the one canned reply from the status tag that the discord-status-web
 * page shows. That tag is set with the /tag Discord command and stored in
 * Lanyard, which anyone can read:
 *
 *   https://api.lanyard.rest/v1/users/<id>   ->   data.kv.tag
 *
 * Tag "At school"  ->  "Aadin is at school, pls message him later."
 * No tag (/tag clear) ->  no reply at all, which is the off switch.
 */
final class Status {
    static final String DISCORD_USER_ID = "1382308851814240298";
    static final String MY_NAME = "Aadin";
    private static final String API_URL =
            "https://api.lanyard.rest/v1/users/" + DISCORD_USER_ID;

    private Status() { }

    /**
     * The reply everyone gets right now, or null when no tag is set.
     * Throws if Lanyard can't be reached (so we never send a wrong reply).
     */
    static String currentReply() throws Exception {
        String tag = fetchTag();
        if (tag == null) return null;
        String what = clean(tag);
        if (what.isEmpty()) return null;
        return MY_NAME + " is " + what + ", pls message him later.";
    }

    private static String fetchTag() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API_URL).openConnection();
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
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
