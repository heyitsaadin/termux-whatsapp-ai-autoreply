package com.aadin.sendkb;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Tap the app icon: shows what everyone would get right now, and opens
 * Notification access if the auto-reply is not turned on yet.
 */
public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Context app = getApplicationContext();
        final Handler ui = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            String text;
            try {
                String msg = Status.currentReply();
                text = (msg == null)
                        ? "No status tag set, so no auto-reply is sent."
                        : "Everyone gets:\n" + msg;
            } catch (Exception e) {
                text = "Couldn't read your status (check the internet).";
            }
            final String shown = text;
            ui.post(() -> Toast.makeText(app, shown, Toast.LENGTH_LONG).show());
        }).start();

        String enabled = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        if (enabled == null || !enabled.contains(getPackageName())) {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
        finish();
    }
}
