package com.aadin.sendkb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Does nothing except stay alive as a foreground service. That raises the
 * priority of the whole app process, so Android is much less likely to freeze
 * or kill the notification listener when the screen is off.
 * Started by the Start button in the app, stopped by the Stop button.
 */
public class KeepAliveService extends Service {
    private static final String TAG = "KeepAlive";
    private static final String CHANNEL = "keepalive";
    private static final int NOTIF_ID = 1;

    /** Safe to call from anywhere; failures are only logged. */
    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, KeepAliveService.class));
        } catch (Exception e) {
            Log.w(TAG, "could not start keep-alive service", e);
        }
    }

    static void stop(Context c) {
        try {
            c.stopService(new Intent(c, KeepAliveService.class));
        } catch (Exception e) {
            Log.w(TAG, "could not stop keep-alive service", e);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL, "Auto-reply running", NotificationManager.IMPORTANCE_LOW));
            }
            PendingIntent open = PendingIntent.getActivity(this, 0,
                    new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_reply)
                    .setContentTitle("Status auto-reply is on")
                    .setContentText("Answering WhatsApp messages with your status")
                    .setContentIntent(open)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            Log.w(TAG, "could not go foreground", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
