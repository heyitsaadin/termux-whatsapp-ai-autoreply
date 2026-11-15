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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers WhatsApp message notifications without opening WhatsApp, by tapping
 * the notification's own "Reply" button.
 *
 *   message arrives -> WhatsApp notification -> this service -> Reply button
 *
 * Rules:
 *  - one-to-one chats only (groups are skipped)
 *  - no status set, or Stop pressed in the app -> no reply
 *  - each person gets the note once; again only if your status changes or
 *    after NOTICE_COOLDOWN_MS
 *  - it only sees messages that raise a notification (not a chat that is
 *    open on screen)
 *
 * All per-chat memory is static, so even if Android runs two copies of this
 * service, a message is still only answered once.
 */
public class ReplyListener extends NotificationListenerService {
    private static final String TAG = "WhatsAppAutomation";
    private static final String WHATSAPP = "com.whatsapp";

    private static final long NOTICE_COOLDOWN_MS = 30L * 60 * 1000;  // one note per person per window
    private static final long DUP_WINDOW_MS = 30_000L;               // same text re-posted
    private static final long WAKE_LOCK_MS = 10_000L;

    /** Set to true to get a small toast explaining every skipped notification. */
    private static final boolean DEBUG = false;

    /** What we remember about one chat. Always access under synchronized (chat). */
    private static final class Chat {
        String lastSig = "";            // last notification handled
        String lastIncoming = "";
        long lastIncomingAt;
        String lastReplyText = "";      // last thing we sent (to spot the echo)
        String noticeStatus = "";       // status the note was sent for
        long noticeAt;
    }

    private static final Map<String, Chat> CHATS = new ConcurrentHashMap<>();

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        if (Prefs.isEnabled(this)) {
            KeepAliveService.start(this);
            if (DEBUG) toast("WhatsApp Automation is on");
        }
    }

    @Override public void onListenerDisconnected() {
        super.onListenerDisconnected();
        if (Build.VERSION.SDK_INT >= 24) {
            requestRebind(new ComponentName(this, ReplyListener.class));
        }
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !WHATSAPP.equals(sbn.getPackageName())) return;
        if (!Prefs.isEnabled(this)) return;   // paused from the app's Stop button

        // keep the CPU awake until the reply is out, even with the screen off
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wl =
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhatsAppAutomation:reply");
        wl.acquire(WAKE_LOCK_MS);
        try {
            handle(sbn);
        } catch (Exception e) {
            Log.e(TAG, "handle failed", e);
        } finally {
            release(wl);
        }
    }

    private void handle(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

        Bundle x = n.extras;
        CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE);
        String chat = (title == null) ? "" : title.toString().trim();
        if (chat.isEmpty()) return;

        if (isGroup(x, chat)) { skip("group chat"); return; }

        Notification.Action action = findReplyAction(n);
        if (action == null) { skip("no reply button (" + chat + ")"); return; }

        String text = newestText(x);
        if (text == null) { skip("no message text"); return; }

        Chat c = CHATS.computeIfAbsent(chat, k -> new Chat());
        long now = System.currentTimeMillis();
        String reply;
        synchronized (c) {
            // WhatsApp re-posts notifications often: handle each message once
            String sig = newestTime(x, n) + "|" + text;
            if (sig.equals(c.lastSig)) return;
            c.lastSig = sig;

            // our own reply can come back as the newest message
            if (text.equals(c.lastReplyText)) { skip("that is our own reply"); return; }

            // same text again a moment later = a re-post with a new timestamp
            if (text.equals(c.lastIncoming) && now - c.lastIncomingAt < DUP_WINDOW_MS) return;
            c.lastIncoming = text;
            c.lastIncomingAt = now;

            Status.Info st = Status.current(getApplicationContext());
            if (st == null) { skip("no status set"); return; }

            boolean alreadyTold = st.what.equals(c.noticeStatus)
                    && now - c.noticeAt < NOTICE_COOLDOWN_MS;
            if (alreadyTold) { skip(chat + " already got this reply"); return; }

            // reserve it before sending, so a second post can't send it again
            c.lastReplyText = st.reply;
            c.noticeStatus = st.what;
            c.noticeAt = now;
            reply = st.reply;
        }

        if (sendReply(action, reply)) {
            toastIfDebug("Auto-replied to " + chat);
        } else {
            synchronized (c) {
                c.noticeStatus = "";
                c.noticeAt = 0;
            }
            toast("Auto-reply to " + chat + " failed");
        }
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

    private void toastIfDebug(String s) {
        if (DEBUG) toast(s);
    }

    private void toast(final String s) {
        ui.post(() -> Toast.makeText(getApplicationContext(), s, Toast.LENGTH_SHORT).show());
    }
}
