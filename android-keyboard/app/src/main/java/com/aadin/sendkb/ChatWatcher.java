package com.aadin.sendkb;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Chat watcher: answers messages that arrive while a WhatsApp chat is OPEN.
 *
 * WhatsApp posts no notification for the chat you are looking at, so the
 * notification-based standby loop cannot see those messages. This service
 * reads the open chat instead:
 *
 *   1. a new incoming message appears at the bottom of the chat
 *   2. we POST it (plus recent history) to the Termux script on 127.0.0.1
 *   3. the script answers with the AI reply text
 *   4. we type it into the message box and tap send
 *
 * It only acts while `python main.py --standby` is running (that is the
 * server we talk to). Messages already on screen when you open a chat are
 * never answered, and it never overwrites text you have typed yourself.
 *
 * WhatsApp view ids used (if WhatsApp changes them, the [app] lines in
 * ~/wa_autoreply.log list the ids that were seen):
 *   entry, conversation_contact_name, message_text, date, send
 */
public class ChatWatcher extends AccessibilityService {
    private static final String TAG = "ChatWatcher";
    private static final String WA = "com.whatsapp";
    private static final String ID = WA + ":id/";
    // must match SERVER_PORT in termux/main.py
    private static final String BASE = "http://127.0.0.1:8765";

    private static final long SETTLE_MS = 900;     // wait for the screen to settle
    private static final long WARMUP_MS = 2500;    // after opening a chat: look, don't reply
    private static final long QUIET_MS = 30000;    // min gap between diagnostic logs
    private static final int HISTORY_MAX = 8;      // earlier messages sent as context

    private static class Msg {
        final boolean incoming;
        final String text;
        final String time;
        final int top;

        Msg(boolean incoming, String text, String time, int top) {
            this.incoming = incoming;
            this.text = text;
            this.time = time;
            this.top = top;
        }

        String sig() {
            return (incoming ? "in|" : "out|") + time + "|" + text;
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final ExecutorService logNet = Executors.newSingleThreadExecutor();

    private String chatTitle;          // conversation we are looking at
    private String baseline;           // signature of the last settled bottom message
    private long chatOpenedAt;
    private boolean busy;              // a reply is being fetched / typed
    private boolean scanPending;
    private int notBottomRetries;
    private long lastQuietLog;
    private AccessibilityNodeInfo listNode;   // scrollable message list

    private final Runnable scan = new Runnable() {
        @Override public void run() {
            scanPending = false;
            try {
                doScan();
            } catch (Exception e) {
                Log.e(TAG, "scan failed", e);
                debugQuiet("scan error: " + e);
            }
        }
    };

    // ---------- service lifecycle ----------
    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        debug("Chat watcher connected.");
    }

    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        net.shutdownNow();
        logNet.shutdownNow();
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null
                || !WA.contentEquals(e.getPackageName())) {
            return;
        }
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // chat (re)opened or a popup appeared: look first, reply later
            baseline = null;
            chatOpenedAt = System.currentTimeMillis();
        }
        notBottomRetries = 0;
        schedule();
    }

    private void schedule() {
        if (scanPending) return;
        scanPending = true;
        ui.postDelayed(scan, SETTLE_MS);
    }

    // ---------- the main check ----------
    private void doScan() {
        if (busy) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
                || !WA.contentEquals(root.getPackageName())) {
            return;   // not looking at WhatsApp right now
        }

        AccessibilityNodeInfo entry = byId(root, "entry");
        if (entry == null) {          // chat list, settings, ...
            chatTitle = null;
            baseline = null;
            dumpIds(root);
            return;
        }

        String title = textOf(byId(root, "conversation_contact_name"));
        if (title == null || title.isEmpty()) {
            debugQuiet("Message box found but no chat title (view id conversation_contact_name).");
            return;
        }
        long now = System.currentTimeMillis();
        if (!title.equals(chatTitle)) {
            chatTitle = title;
            baseline = null;
            chatOpenedAt = now;
        }

        List<Msg> msgs = readMessages(root);
        if (msgs.isEmpty()) {
            if (baseline == null) baseline = "";
            return;
        }

        // only trust what we see when the chat is scrolled to the very bottom,
        // otherwise scrolling through old messages would look like new ones
        if (!atBottom()) {
            if (notBottomRetries++ < 4) schedule();
            return;
        }

        Msg last = msgs.get(msgs.size() - 1);
        String sig = last.sig();
        if (baseline == null || now - chatOpenedAt < WARMUP_MS) {
            baseline = sig;           // what is already there is not new
            return;
        }
        if (sig.equals(baseline)) return;
        baseline = sig;
        if (!last.incoming) return;   // our own message

        String payload = buildPayload(title, msgs);
        if (payload == null) return;
        debug("New message in open chat from " + title + ": " + last.text);
        requestReply(title, payload);
    }

    private List<Msg> readMessages(AccessibilityNodeInfo root) {
        List<Msg> out = new ArrayList<>();
        listNode = null;
        List<AccessibilityNodeInfo> nodes =
                root.findAccessibilityNodeInfosByViewId(ID + "message_text");
        if (nodes == null) return out;
        int half = getResources().getDisplayMetrics().widthPixels / 2;
        Rect r = new Rect();
        for (AccessibilityNodeInfo n : nodes) {
            CharSequence t = n.getText();
            if (t == null || t.length() == 0) continue;
            n.getBoundsInScreen(r);
            // incoming bubbles sit on the left, outgoing on the right
            boolean incoming = r.centerX() < half;
            out.add(new Msg(incoming, t.toString(), timeFor(n), r.top));
            if (listNode == null) listNode = scrollableParent(n);
        }
        out.sort((a, b) -> Integer.compare(a.top, b.top));
        return out;
    }

    /** The little "11:41 PM" text that belongs to the same bubble. */
    private String timeFor(AccessibilityNodeInfo textNode) {
        AccessibilityNodeInfo p = textNode.getParent();
        for (int i = 0; i < 2 && p != null; i++, p = p.getParent()) {
            List<AccessibilityNodeInfo> d =
                    p.findAccessibilityNodeInfosByViewId(ID + "date");
            if (d != null && !d.isEmpty() && d.get(0).getText() != null) {
                return d.get(0).getText().toString();
            }
        }
        return "";
    }

    private AccessibilityNodeInfo scrollableParent(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n.getParent();
        for (int i = 0; i < 8 && p != null; i++, p = p.getParent()) {
            if (p.isScrollable()) return p;
        }
        return null;
    }

    /** True when the message list cannot scroll any further down. */
    private boolean atBottom() {
        if (listNode == null) return true;
        for (AccessibilityNodeInfo.AccessibilityAction a : listNode.getActionList()) {
            if (a.getId() == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return false;
        }
        return true;
    }

    private String buildPayload(String title, List<Msg> msgs) {
        try {
            JSONObject o = new JSONObject();
            o.put("sender", title);
            o.put("message", msgs.get(msgs.size() - 1).text);
            JSONArray hist = new JSONArray();
            int from = Math.max(0, msgs.size() - 1 - HISTORY_MAX);
            for (int i = from; i < msgs.size() - 1; i++) {
                Msg m = msgs.get(i);
                JSONObject h = new JSONObject();
                h.put("from", m.incoming ? "them" : "me");
                h.put("text", m.text);
                hist.put(h);
            }
            o.put("history", hist);
            return o.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    // ---------- ask Termux, then type + send ----------
    private void requestReply(final String title, final String payload) {
        busy = true;
        net.execute(() -> {
            String reply = null;
            try {
                reply = post("/reply", payload, 120000);
            } catch (Exception ex) {
                Log.w(TAG, "Termux server not reachable: " + ex);
            }
            final String r = reply;
            ui.post(() -> {
                if (r == null || r.isEmpty()) {
                    finish();
                    return;
                }
                try {
                    typeAndSend(title, r);
                } catch (Exception ex) {
                    debug("typeAndSend failed: " + ex);
                    finish();
                }
            });
        });
    }

    private void typeAndSend(String title, String reply) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo entry = (root == null) ? null : byId(root, "entry");
        String nowTitle = (root == null) ? null
                : textOf(byId(root, "conversation_contact_name"));
        if (entry == null || !title.equals(nowTitle)) {
            debug("Chat changed or closed before the reply was ready, not sending.");
            finish();
            return;
        }
        CharSequence draft = entry.getText();
        if (draft != null && draft.length() > 0 && !entry.isShowingHintText()) {
            debug("There is text in the message box already, not overwriting it.");
            finish();
            return;
        }
        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, reply);
        if (!entry.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            debug("Could not type into the message box.");
            finish();
            return;
        }
        clickSend(title, 0);
    }

    /** The send button only appears once there is text, so retry for a moment. */
    private void clickSend(final String title, final int attempt) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo send = (root == null) ? null : byId(root, "send");
        if (send != null && send.isEnabled()
                && send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            debug("Sent reply in open chat with " + title + ".");
            finish();
            return;
        }
        if (attempt < 8) {
            ui.postDelayed(() -> clickSend(title, attempt + 1), 250);
            return;
        }
        debug("Typed the reply but could not find the send button (view id send).");
        finish();
    }

    private void finish() {
        busy = false;
        schedule();
    }

    // ---------- small helpers ----------
    private AccessibilityNodeInfo byId(AccessibilityNodeInfo root, String name) {
        List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByViewId(ID + name);
        return (l == null || l.isEmpty()) ? null : l.get(0);
    }

    private String textOf(AccessibilityNodeInfo n) {
        if (n == null || n.getText() == null) return null;
        return n.getText().toString().trim();
    }

    private boolean quietOk() {
        long now = System.currentTimeMillis();
        if (now - lastQuietLog < QUIET_MS) return false;
        lastQuietLog = now;
        return true;
    }

    /** WhatsApp screen without the message box: log the view ids we can see. */
    private void dumpIds(AccessibilityNodeInfo root) {
        if (!quietOk()) return;
        Set<String> ids = new LinkedHashSet<>();
        collectIds(root, ids, 0);
        debug("WhatsApp screen without a message box. View ids seen: " + ids);
    }

    private void collectIds(AccessibilityNodeInfo n, Set<String> ids, int depth) {
        if (n == null || ids.size() >= 60 || depth > 40) return;
        String id = n.getViewIdResourceName();
        if (id != null) ids.add(id.replace(ID, ""));
        for (int i = 0; i < n.getChildCount(); i++) {
            collectIds(n.getChild(i), ids, depth + 1);
        }
    }

    private void debugQuiet(String s) {
        if (quietOk()) debug(s);
    }

    /** Log locally and to the Termux script (shows up in ~/wa_autoreply.log). */
    private void debug(final String s) {
        Log.d(TAG, s);
        logNet.execute(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("text", s);
                post("/log", o.toString(), 3000);
            } catch (Exception ignored) {
                // Termux script not running: nothing to tell
            }
        });
    }

    /** POST json to the Termux script; returns the "reply" field, or null. */
    private String post(String path, String json, int readTimeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(2000);
            c.setReadTimeout(readTimeoutMs);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
            if (c.getResponseCode() != 200) return null;
            try (InputStream is = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                JSONObject o = new JSONObject(bo.toString("UTF-8"));
                return o.isNull("reply") ? null : o.getString("reply");
            }
        } finally {
            c.disconnect();
        }
    }
}
