package com.aadin.sendkb;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Tap the app icon. It walks you through the two setup steps, one per tap:
 *   1. Notification access on
 *   2. Battery: allow it to run in the background (needed with the screen off)
 * Once both are done it shows the reply everyone would get right now.
 */
public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Context app = getApplicationContext();
        final String pkg = getPackageName();

        String enabled = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        if (enabled == null || !enabled.contains(pkg)) {
            say("Step 1/2: turn on Status Auto-Reply, then tap the app icon again.");
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            finish();
            return;
        }

        KeepAliveService.start(app);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(pkg)) {
            say("Step 2/2: choose Allow, then tap the app icon again.");
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + pkg)));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
            finish();
            return;
        }

        // all set: show what everyone would get
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String text;
            try {
                String msg = Status.currentReply(app);
                text = (msg == null)
                        ? "All set. No status tag is set, so no auto-reply is sent."
                        : "All set. Everyone gets:\n" + msg;
            } catch (Exception e) {
                text = "Couldn't read your status (check the internet).";
            }
            final String shown = text;
            ui.post(() -> Toast.makeText(app, shown, Toast.LENGTH_LONG).show());
        }).start();
        finish();
    }

    private void say(String s) {
        Toast.makeText(getApplicationContext(), s, Toast.LENGTH_LONG).show();
    }
}
