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
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.widget.Toast;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Answers every WhatsApp message notification with the same status line,
 * without opening WhatsApp: it taps the notification's own "Reply" button.
 *
 *   WhatsApp notification -> read status tag (Status.java) -> reply
 *
 * Rules:
 *  - one-to-one chats only (groups are skipped)
 *  - no status tag set  ->  no reply (that is the off switch)
 *  - the same reply goes to a chat at most once per COOLDOWN_MS; if your
 *    status changes, the chat gets the new line right away
 *  - it only sees messages that raise a notification (not a chat that is
 *    open on screen)
 */
public class ReplyListener extends NotificationListenerService {
    private static final String TAG = "StatusReply";
    private static final String WHATSAPP = "com.whatsapp";
    private static final long COOLDOWN_MS = 30L * 60 * 1000;

    /** Shows a small toast for every skipped notification. Turn off once it works. */
    private static final boolean DEBUG = true;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    // all keyed by chat name
    private final Map<String, String> seen = new ConcurrentHashMap<>();           // last message handled
    private final Map<String, String> lastReplyText = new ConcurrentHashMap<>();  // last line we sent
    private final Map<String, Long> lastReplyTime = new ConcurrentHashMap<>();

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        if (DEBUG) toast("Status auto-reply is on");
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
        try {
            handle(sbn);
        } catch (Exception e) {
            Log.e(TAG, "handle failed", e);
        }
    }

    private void handle(StatusBarNotification sbn) {
        if (sbn == null || !WHATSAPP.equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

        Bundle x = n.extras;
        CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE);
        final String chat = (title == null) ? "" : title.toString().trim();
        if (chat.isEmpty()) return;

        if (isGroup(x, chat)) { skip("group chat"); return; }

        final Notification.Action action = findReplyAction(n);
        if (action == null) { skip("no reply button (" + chat + ")"); return; }

        String text = newestText(x);
        if (text == null) { skip("no message text"); return; }

        // WhatsApp re-posts notifications often: handle each message once
        String sig = newestTime(x, n) + "|" + text;
        if (sig.equals(seen.get(chat))) return;
        seen.put(chat, sig);

        // our own reply can come back as the newest message: ignore it
        if (text.equals(lastReplyText.get(chat))) { skip("that is our own reply"); return; }

        worker.execute(() -> answer(chat, action));
    }

    private void answer(String chat, Notification.Action action) {
        String msg;
        try {
            msg = Status.currentReply();
        } catch (Exception e) {
            Log.w(TAG, "could not read status", e);
            toast("Auto-reply: couldn't read your status");
            return;
        }
        if (msg == null) { skip("no status tag set"); return; }

        long now = System.currentTimeMillis();
        Long last = lastReplyTime.get(chat);
        if (msg.equals(lastReplyText.get(chat)) && last != null && now - last < COOLDOWN_MS) {
            skip(chat + " already got this reply");
            return;
        }

        // remember before sending, so the echo is recognised
        lastReplyText.put(chat, msg);
        lastReplyTime.put(chat, now);
        if (sendReply(action, msg)) {
            toast("Auto-replied to " + chat);
        } else {
            lastReplyText.remove(chat);
            lastReplyTime.remove(chat);
            toast("Auto-reply to " + chat + " failed");
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
