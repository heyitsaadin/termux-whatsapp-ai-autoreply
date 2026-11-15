package com.aadin.sendkb;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Place suggestions: a live search on OpenStreetMap data via Photon (free, no
 * account or API key), ranked near where you last used "Use my location".
 * Also turns GPS coordinates into a place name for that button.
 * Only the text you type (or your rough coordinates) is sent, nothing else.
 */
final class PlaceFinder {
    private static final String PHOTON = "https://photon.komoot.io";

    private PlaceFinder() { }

    /** Each entry is {label shown, value to use}. Empty list if the search fails. */
    static List<String[]> search(Context ctx, String q) {
        List<String[]> out = new ArrayList<>();
        try {
            String url = PHOTON + "/api/?limit=8&lang=en"
                    + (Prefs.hasBias(ctx)
                            ? "&lat=" + Prefs.biasLat(ctx) + "&lon=" + Prefs.biasLon(ctx) : "")
                    + "&q=" + URLEncoder.encode(q, "UTF-8");
            JSONArray feats = new JSONObject(get(url)).optJSONArray("features");
            if (feats == null) return out;
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < feats.length() && out.size() < 6; i++) {
                JSONObject p = feats.getJSONObject(i).optJSONObject("properties");
                if (p == null) continue;
                String name = p.optString("name", "").trim();
                if (name.isEmpty() || !seen.add(name.toLowerCase(Locale.ROOT))) continue;
                String loc = p.optString("city", "").trim();
                if (loc.isEmpty()) loc = p.optString("county", "").trim();
                String state = p.optString("state", "").trim();
                String area = loc.isEmpty() ? state : (state.isEmpty() ? loc : loc + ", " + state);
                out.add(new String[]{area.isEmpty() ? name : name + ", " + area, name});
            }
        } catch (Exception e) {
            // search was slow or down: recent places still work
        }
        return out;
    }

    /** Town or area name for a GPS point, or null if it can't be worked out. */
    static String reverse(double lat, double lon) {
        String name = reverseOnce(lat, lon, true);
        return name != null ? name : reverseOnce(lat, lon, false);
    }

    private static String reverseOnce(double lat, double lon, boolean areaOnly) {
        try {
            String url = PHOTON + "/reverse?limit=1&lang=en&lat=" + lat + "&lon=" + lon
                    + (areaOnly ? "&layer=locality&layer=district&layer=city" : "");
            JSONArray feats = new JSONObject(get(url)).optJSONArray("features");
            if (feats == null || feats.length() == 0) return null;
            JSONObject p = feats.getJSONObject(0).optJSONObject("properties");
            if (p == null) return null;
            String[] order = areaOnly
                    ? new String[]{"name", "city", "district", "locality", "county"}
                    : new String[]{"city", "locality", "district", "name", "county"};
            for (String k : order) {
                String v = p.optString(k, "").trim();
                if (!v.isEmpty()) return v;
            }
        } catch (Exception e) {
            // fall through
        }
        return null;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(6000);
            c.setRequestProperty("User-Agent", "status-auto-reply");
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
