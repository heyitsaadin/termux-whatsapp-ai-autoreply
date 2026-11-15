package com.aadin.sendkb;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Asks the AI helper that runs in Termux (python main.py --standby) what to
 * say to a follow-up message. The Groq key stays in Termux; this app only
 * talks to 127.0.0.1.
 */
final class Ai {
    // must match PORT in termux/main.py
    private static final String HELPER_URL = "http://127.0.0.1:8765/reply";

    private Ai() { }

    /**
     * @param history earlier messages of this chat, oldest first, as
     *                {"them" | "me", text}; does not include `message`
     * @return the reply text, or null when the AI decided no reply is needed
     * @throws Exception when the helper is not running or fails
     */
    static String ask(String chat, String message, String status,
                      List<String[]> history) throws Exception {
        JSONObject o = new JSONObject();
        o.put("chat", chat);
        o.put("message", message);
        o.put("status", status);
        JSONArray h = new JSONArray();
        for (String[] turn : history) {
            JSONObject j = new JSONObject();
            j.put("from", turn[0]);
            j.put("text", turn[1]);
            h.put(j);
        }
        o.put("history", h);

        HttpURLConnection c = (HttpURLConnection) new URL(HELPER_URL).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(1500);     // fails fast when Termux isn't running
            c.setReadTimeout(30000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = c.getOutputStream()) {
                os.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("helper answered " + code);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream is = c.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            }
            JSONObject r = new JSONObject(bo.toString("UTF-8"));
            if (r.isNull("reply")) return null;
            String reply = r.optString("reply", "").trim();
            return reply.isEmpty() ? null : reply;
        } finally {
            c.disconnect();
        }
    }
}
