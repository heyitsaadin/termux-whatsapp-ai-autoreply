package com.aadin.sendkb;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

/**
 * The app screen (Material 3 Expressive):
 *  - a big card with Start / Stop for the auto-reply
 *  - one row per permission, with its state and a button to fix it
 *  - the status the replies are built from
 */
public class MainActivity extends AppCompatActivity {
    private static final int OK = 0, REQUIRED = 1, OPTIONAL = 2;

    private static final int A_PRIMARY = androidx.appcompat.R.attr.colorPrimary;
    private static final int A_ERROR = androidx.appcompat.R.attr.colorError;
    private static final int A_ON_SURFACE_VARIANT = com.google.android.material.R.attr.colorOnSurfaceVariant;
    private static final int A_PRIMARY_CONTAINER = com.google.android.material.R.attr.colorPrimaryContainer;
    private static final int A_ON_PRIMARY_CONTAINER = com.google.android.material.R.attr.colorOnPrimaryContainer;
    private static final int A_ERROR_CONTAINER = com.google.android.material.R.attr.colorErrorContainer;
    private static final int A_ON_ERROR_CONTAINER = com.google.android.material.R.attr.colorOnErrorContainer;
    private static final int A_SURFACE_HIGHEST = com.google.android.material.R.attr.colorSurfaceContainerHighest;
    private static final int A_ON_SURFACE = com.google.android.material.R.attr.colorOnSurface;

    private MaterialCardView heroCard;
    private ImageView heroIcon;
    private TextView heroTitle;
    private TextView heroSub;
    private MaterialButton heroButton;
    private View rowListener;
    private View rowBattery;
    private View rowNotif;
    private TextView statusValue;
    private TextView statusReply;
    private int statusGen = 0;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        heroCard = findViewById(R.id.heroCard);
        heroIcon = findViewById(R.id.heroIcon);
        heroTitle = findViewById(R.id.heroTitle);
        heroSub = findViewById(R.id.heroSub);
        heroButton = findViewById(R.id.heroButton);
        rowListener = findViewById(R.id.rowListener);
        rowBattery = findViewById(R.id.rowBattery);
        rowNotif = findViewById(R.id.rowNotif);
        statusValue = findViewById(R.id.statusValue);
        statusReply = findViewById(R.id.statusReply);

        findViewById(R.id.refreshStatus).setOnClickListener(v -> loadStatus());

        findViewById(R.id.setTag).setOnClickListener(view ->
                TagDialog.show(this, this::loadStatus));
    }

    /** Also runs when you come back from a settings screen, so the rows update. */
    @Override protected void onResume() {
        super.onResume();
        refresh();
        loadStatus();
    }

    // ---------- state ----------

    private boolean isListenerEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(getPackageName());
    }

    private boolean isBatteryUnrestricted() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private boolean notificationsAllowed() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void refresh() {
        boolean listener = isListenerEnabled();
        boolean running = Prefs.isEnabled(this);
        if (listener && running) KeepAliveService.start(getApplicationContext());

        bindHero(listener, running);

        bindRow(rowListener, "Notification access",
                listener ? OK : REQUIRED,
                listener ? "On. WhatsApp notifications can be read."
                         : "Required. Nothing works without it.",
                "Grant",
                () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        boolean battery = isBatteryUnrestricted();
        bindRow(rowBattery, "Run in background",
                battery ? OK : REQUIRED,
                battery ? "Unrestricted. Keeps working with the screen off."
                        : "Needed so replies still go out with the screen off.",
                "Allow", this::askBattery);

        boolean notif = notificationsAllowed();
        bindRow(rowNotif, "Show notification",
                notif ? OK : OPTIONAL,
                notif ? "Allowed. Shows \"auto-reply is on\" while running."
                      : "Optional. Lets Android show the \"auto-reply is on\" notice.",
                "Allow", this::askNotifications);
    }

    // ---------- hero card (Start / Stop) ----------

    private void bindHero(boolean listener, final boolean running) {
        int bg;
        int fg;
        if (!listener) {
            bg = MaterialColors.getColor(heroCard, A_ERROR_CONTAINER);
            fg = MaterialColors.getColor(heroCard, A_ON_ERROR_CONTAINER);
            heroIcon.setImageResource(R.drawable.ic_warn);
            heroTitle.setText("Setup needed");
            heroSub.setText("Turn on notification access so the app can see WhatsApp messages.");
            heroButton.setText("Grant access");
            heroButton.setIconResource(R.drawable.ic_check);
            heroButton.setOnClickListener(v ->
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        } else if (running) {
            bg = MaterialColors.getColor(heroCard, A_PRIMARY_CONTAINER);
            fg = MaterialColors.getColor(heroCard, A_ON_PRIMARY_CONTAINER);
            heroIcon.setImageResource(R.drawable.ic_stat_reply);
            heroTitle.setText("Auto-reply is on");
            heroSub.setText("Answering WhatsApp messages with your status.");
            heroButton.setText("Stop");
            heroButton.setIconResource(R.drawable.ic_stop);
            heroButton.setOnClickListener(v -> setRunning(false));
        } else {
            bg = MaterialColors.getColor(heroCard, A_SURFACE_HIGHEST);
            fg = MaterialColors.getColor(heroCard, A_ON_SURFACE);
            heroIcon.setImageResource(R.drawable.ic_stat_reply);
            heroTitle.setText("Auto-reply is paused");
            heroSub.setText("Nobody gets a reply until you start it again.");
            heroButton.setText("Start");
            heroButton.setIconResource(R.drawable.ic_play);
            heroButton.setOnClickListener(v -> setRunning(true));
        }
        heroCard.setCardBackgroundColor(bg);
        heroTitle.setTextColor(fg);
        heroSub.setTextColor(fg);
        heroIcon.setColorFilter(fg);
    }

    private void setRunning(boolean on) {
        Prefs.setEnabled(this, on);
        if (on) {
            KeepAliveService.start(getApplicationContext());
        } else {
            KeepAliveService.stop(getApplicationContext());
        }
        toast(on ? "Auto-reply started" : "Auto-reply paused");
        refresh();
    }

    // ---------- rows ----------

    private void bindRow(View row, String title, int state, String sub,
                         String action, final Runnable onAction) {
        ImageView icon = row.findViewById(R.id.itemIcon);
        TextView t = row.findViewById(R.id.itemTitle);
        TextView s = row.findViewById(R.id.itemSubtitle);
        MaterialButton btn = row.findViewById(R.id.itemAction);

        t.setText(title);
        s.setText(sub);

        int tint;
        if (state == OK) {
            icon.setImageResource(R.drawable.ic_check);
            tint = MaterialColors.getColor(row, A_PRIMARY);
        } else if (state == REQUIRED) {
            icon.setImageResource(R.drawable.ic_warn);
            tint = MaterialColors.getColor(row, A_ERROR);
        } else {
            icon.setImageResource(R.drawable.ic_warn);
            tint = MaterialColors.getColor(row, A_ON_SURFACE_VARIANT);
        }
        icon.setColorFilter(tint);

        boolean showAction = (state == REQUIRED || state == OPTIONAL)
                && action != null && onAction != null;
        btn.setVisibility(showAction ? View.VISIBLE : View.GONE);
        if (showAction) {
            btn.setText(action);
            btn.setOnClickListener(v -> onAction.run());
        }
    }

    private void askBattery() {
        String pkg = getPackageName();
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + pkg)));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void askNotifications() {
        SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33 && !sp.getBoolean("asked_notif", false)) {
            sp.edit().putBoolean("asked_notif", true).apply();
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 100);
        } else {
            // already asked once: Android won't show the popup again, so open the settings page
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }

    // ---------- status ----------

    private void loadStatus() {
        statusValue.setText("Checking...");
        statusReply.setText("");
        final int gen = ++statusGen;
        final Context app = getApplicationContext();
        new Thread(() -> {
            String value;
            String reply;
            try {
                Status.Info info = Status.current(app);
                if (info == null) {
                    value = "No status set";
                    reply = "Nobody gets an auto-reply until you set a status. Tap Set status below.";
                } else {
                    value = Character.toUpperCase(info.what.charAt(0)) + info.what.substring(1);
                    reply = "Everyone gets:\n" + info.reply;
                }
            } catch (Exception e) {
                value = "Can't read your status";
                reply = "Something went wrong. Tap Refresh to try again.";
            }
            final String v = value;
            final String r = reply;
            runOnUiThread(() -> {
                if (gen != statusGen || isFinishing() || isDestroyed()) return;
                statusValue.setText(v);
                statusReply.setText(r);
            });
        }).start();
    }

    private void toast(String s) {
        Toast.makeText(getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }
}
