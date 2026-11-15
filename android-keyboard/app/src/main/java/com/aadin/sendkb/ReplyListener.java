package com.aadin.sendkb;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.PowerManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Answers WhatsApp message notifications without opening WhatsApp, by tapping
 * the notification's own "Reply" button.
 *
 *  1st message from someone (or status changed, or 30 min passed):
 *      the fixed line, e.g. "Aadin is at school, pls message him later."
 *  Later messages from the same person:
 *      spam / filler is ignored; a real message goes to the AI helper in
 *      Termux, which knows your status and the chat so far, and either
 *      writes a short custom reply or decides none is needed.
 *
 * Rules:
 *  - one-to-one chats only (groups are skipped)
 *  - no status tag set  ->  no reply (that is the off switch)
 *  - at most MAX_FOLLOWUPS AI replies per person per notice window
 *  - never two sends to the same chat within MIN_GAP_MS
 *  - it only sees messages that raise a notification (not a chat that is
 *    open on screen)
 *
 * All per-chat memory is static, so even if Android runs two copies of this
 * service, a message is still only answered once.
 */
public class ReplyListener extends NotificationListenerService {
    private static final String TAG = "StatusReply";
    private static final String WHATSAPP = "com.whatsapp";

    private static final long NOTICE_COOLDOWN_MS = 30L * 60 * 1000;  // fixed line: once per window
    private static final long MIN_GAP_MS = 8_000L;                    // never 2 sends this close
    private static final long DUP_WINDOW_MS = 30_000L;               // same text re-posted
    private static final int MAX_FOLLOWUPS = 3;                      // AI replies per window
    private static final int HISTORY_MAX = 10;                       // turns the AI remembers
    private static final long WAKE_LOCK_MS = 75_000L;

    /** Shows a small toast for every skipped notification. Turn off once it works. */
    private static final boolean DEBUG = true;

    /** Messages that never deserve an AI call. */
    private static final Set<String> FILLER = new HashSet<>(Arrays.asList(
            "hi", "hii", "hiii", "hello", "hlo", "hey", "hmm", "ok", "okay", "kk",
            "yes", "no", "thanks", "thank you", "thx", "test", "ping", "oi"));

    /** What we remember about one chat. Always access under synchronized (chat). */
    private static final class Chat {
        String lastSig = "";            // last notification handled
        String lastIncoming = "";
        long lastIncomingAt;
        String lastReplyText = "";      // last thing we sent (to spot the echo)
        long lastSendAt;
        String noticeStatus = "";       // status the fixed line was sent for
        long noticeAt;
        int followups;
        final ArrayList<String[]> history = new ArrayList<>();   // {"them"|"me", text}

        void remember(String who, String text) {
            history.add(new String[] { who, text });
            while (history.size() > HISTORY_MAX) history.remove(0);
        }
    }

    private static final Map<String, Chat> CHATS = new ConcurrentHashMap<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        if (Prefs.isEnabled(this)) {
            KeepAliveService.start(this);
            if (DEBUG) toast("Status auto-reply is on");
        }
    }

    @Override public void onListenerDisconnected() {
        super.onListenerDisconnected();
        if (Build.VERSION.SDK_INT >= 24) {
            requestRebind(new ComponentName(this, ReplyListener.class));
        }
    }

    @Override public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !WHATSAPP.equals(sbn.getPackageName())) return;

        // keep the CPU awake until the reply is out, even with the screen off
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        final PowerManager.WakeLock wl =
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StatusReply:answer");
        wl.acquire(WAKE_LOCK_MS);

        boolean handedOff = false;
        try {
            handedOff = handle(sbn, wl);
        } catch (Exception e) {
            Log.e(TAG, "handle failed", e);
        } finally {
            if (!handedOff) release(wl);
        }
    }

    /** Returns true when the work was handed to the worker (which releases the lock). */
    private boolean handle(StatusBarNotification sbn, final PowerManager.WakeLock wl) {
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return false;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return false;

        Bundle x = n.extras;
        CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE);
        final String chat = (title == null) ? "" : title.toString().trim();
        if (chat.isEmpty()) return false;

        if (isGroup(x, chat)) { skip("group chat"); return false; }

        final Notification.Action action = findReplyAction(n);
        if (action == null) { skip("no reply button (" + chat + ")"); return false; }

        final String text = newestText(x);
        if (text == null) { skip("no message text"); return false; }

        Chat c = CHATS.computeIfAbsent(chat, k -> new Chat());
        long now = System.currentTimeMillis();
        synchronized (c) {
            // WhatsApp re-posts notifications often: handle each message once
            String sig = newestTime(x, n) + "|" + text;
            if (sig.equals(c.lastSig)) return false;
            c.lastSig = sig;

            // our own reply can come back as the newest message
            if (text.equals(c.lastReplyText)) { skip("that is our own reply"); return false; }

            // same text again a moment later = a re-post with a new timestamp
            if (text.equals(c.lastIncoming) && now - c.lastIncomingAt < DUP_WINDOW_MS) return false;
            c.lastIncoming = text;
            c.lastIncomingAt = now;
            c.remember("them", text);
        }

        worker.execute(() -> {
            try {
                answer(chat, action, text);
            } finally {
                release(wl);
            }
        });
        return true;
    }

    private void answer(String chat, Notification.Action action, String text) {
        Chat c = CHATS.get(chat);
        if (c == null) return;

        // a second message right behind our reply waits instead of being lost
        long wait;
        synchronized (c) {
            wait = MIN_GAP_MS - (System.currentTimeMillis() - c.lastSendAt);
        }
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        Status.Info st;
        try {
            st = Status.current(getApplicationContext());
        } catch (Exception e) {
            Log.w(TAG, "could not read status", e);
            toast("Auto-reply: couldn't read your status");
            return;
        }
        if (st == null) { skip("no status tag set"); return; }

        boolean needNotice;
        List<String[]> history;
        synchronized (c) {
            long now = System.currentTimeMillis();
            needNotice = !(st.what.equals(c.noticeStatus) && now - c.noticeAt < NOTICE_COOLDOWN_MS);
            if (needNotice) c.followups = 0;
            history = new ArrayList<>(c.history);
            // the newest "them" entry is the message being answered itself
            if (!history.isEmpty() && history.get(history.size() - 1)[0].equals("them")) {
                history.remove(history.size() - 1);
            }
        }

        String reply;
        if (needNotice) {
            reply = st.reply;
        } else {
            if (looksLikeSpam(text)) { skip("not worth a reply: " + text); return; }
            synchronized (c) {
                if (c.followups >= MAX_FOLLOWUPS) { skip("already answered " + chat + " enough"); return; }
            }
            try {
                reply = Ai.ask(chat, text, st.what, history);
            } catch (Exception e) {
                Log.w(TAG, "AI helper not reachable", e);
                skip("Termux AI helper isn't running (python main.py --standby)");
                return;
            }
            if (reply == null) { skip("AI: no reply needed"); return; }
        }

        synchronized (c) {
            long now = System.currentTimeMillis();
            if (now - c.lastSendAt < MIN_GAP_MS) { skip("just replied to " + chat); return; }
            c.lastSendAt = now;
            c.lastReplyText = reply;
            if (needNotice) {
                c.noticeStatus = st.what;
                c.noticeAt = now;
            } else {
                c.followups++;
            }
            c.remember("me", reply);
        }

        if (sendReply(action, reply)) {
            toast((needNotice ? "Auto-replied to " : "AI replied to ") + chat);
        } else {
            synchronized (c) {
                c.lastSendAt = 0;
                if (needNotice) c.noticeAt = 0; else c.followups--;
            }
            toast("Auto-reply to " + chat + " failed");
        }
    }

    /** Greetings, filler, emoji-only and media placeholders: not worth an AI call. */
    private boolean looksLikeSpam(String text) {
        String t = Status.clean(text).toLowerCase(Locale.ROOT);
        if (t.length() < 3) return true;            // "k", "?", emoji only
        if (FILLER.contains(t)) return true;
        return t.matches("(photo|video|sticker|gif|audio|voice message|document|location|contact)[\\s\\d:().]*");
    }

    private static void release(PowerManager.WakeLock wl) {
        try {
            if (wl.isHeld()) wl.release();
        } catch (RuntimeException ignored) {
            // already released by its timeout
        }
    }

    // ---------- notification parsing ----------

    /** One-to-one only. Groups are skipped so nobody's group gets spammed. */
    private boolean isGroup(Bundle x, String chat) {
        // older notification style: "Sender @ Group" or "Group: Sender"
        if (chat.contains(" @ ") || chat.contains(": ")) return true;
        if (x.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION)) {
            return x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION);
        }
        return x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null;
    }

    /** The notification's "Reply" button: the action that takes typed text. */
    private Notification.Action findReplyAction(Notification n) {
        if (n.actions != null) {
            for (Notification.Action a : n.actions) {
                if (isReply(a)) return a;
            }
        }
        for (Notification.Action a : new Notification.WearableExtender(n).getActions()) {
            if (isReply(a)) return a;
        }
        return null;
    }

    private boolean isReply(Notification.Action a) {
        RemoteInput[] ins = a.getRemoteInputs();
        return a.actionIntent != null && ins != null && ins.length > 0;
    }

    private Bundle newestMessage(Bundle x) {
        Parcelable[] msgs = x.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs != null && msgs.length > 0 && msgs[msgs.length - 1] instanceof Bundle) {
            return (Bundle) msgs[msgs.length - 1];
        }
        return null;
    }

    private String newestText(Bundle x) {
        Bundle m = newestMessage(x);
        if (m != null) {
            CharSequence s = m.getCharSequence("text");
            if (s != null && s.toString().trim().length() > 0) return s.toString();
        }
        CharSequence s = x.getCharSequence(Notification.EXTRA_TEXT);
        return (s == null || s.toString().trim().isEmpty()) ? null : s.toString();
    }

    private long newestTime(Bundle x, Notification n) {
        Bundle m = newestMessage(x);
        return (m != null) ? m.getLong("time", n.when) : n.when;
    }

    // ---------- sending ----------

    /** Fill the Reply box of the notification with `text` and press its send. */
    private boolean sendReply(Notification.Action action, String text) {
        RemoteInput[] inputs = action.getRemoteInputs();
        Intent intent = new Intent();
        Bundle results = new Bundle();
        for (RemoteInput ri : inputs) {
            results.putCharSequence(ri.getResultKey(), text);
        }
        RemoteInput.addResultsToIntent(inputs, intent, results);
        try {
            action.actionIntent.send(this, 0, intent);
            return true;
        } catch (PendingIntent.CanceledException e) {
            Log.w(TAG, "reply button is no longer valid", e);
            return false;
        }
    }

    // ---------- feedback ----------

    private void skip(String why) {
        if (DEBUG) toast("Auto-reply skipped: " + why);
    }

    private void toast(final String s) {
        ui.post(() -> Toast.makeText(getApplicationContext(), s, Toast.LENGTH_SHORT).show());
    }
}
