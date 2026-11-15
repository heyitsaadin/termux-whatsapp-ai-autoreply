package com.aadin.sendkb;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Writes the status tag to Lanyard, the same call the /tag Discord command makes:
 *   PUT    https://api.lanyard.rest/v1/users/<id>/kv/tag   (body = the tag text)
 *   DELETE https://api.lanyard.rest/v1/users/<id>/kv/tag   (clears it)
 * The Authorization header is your Lanyard API key.
 */
final class Lanyard {
    /** Lanyard refused the key (wrong or revoked). */
    static final class BadKey extends Exception {
        BadKey() { super("Lanyard rejected the key"); }
    }

    private static final String KV_URL =
            "https://api.lanyard.rest/v1/users/" + Status.DISCORD_USER_ID + "/kv/tag";

    private Lanyard() { }

    static void setTag(String key, String text) throws Exception {
        call("PUT", key, text);
    }

    static void clearTag(String key) throws Exception {
        call("DELETE", key, null);
    }

    private static void call(String method, String key, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(KV_URL).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Authorization", key);
            c.setRequestProperty("User-Agent", "status-auto-reply");
            if (body != null) {
                byte[] data = body.getBytes("UTF-8");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "text/plain");
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(data);
                }
            }
            int code = c.getResponseCode();
            if (code == 401 || code == 403) throw new BadKey();
            if (code < 200 || code >= 300) throw new Exception("Lanyard answered " + code);
        } finally {
            c.disconnect();
        }
    }
}
