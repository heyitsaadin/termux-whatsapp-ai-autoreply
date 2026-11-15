package com.aadin.sendkb;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot "where am I" using the coarse (network) location, which is plenty for
 * a town name. Calls back on the main thread, with null if nothing was found.
 */
final class Locator {
    interface Done {
        void got(Location l);
    }

    private static final long FRESH_MS = 10 * 60 * 1000L;

    private Locator() { }

    @SuppressLint("MissingPermission")
    static void find(final Context ctx, final Done done) {
        final LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            done.got(null);
            return;
        }

        Location best = null;
        for (String p : lm.getProviders(true)) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException e) {
                // e.g. GPS needs the precise permission; skip that provider
            }
        }
        if (best != null && System.currentTimeMillis() - best.getTime() < FRESH_MS) {
            done.got(best);
            return;
        }

        final Location stale = best;
        if (!lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            done.got(stale);
            return;
        }

        final AtomicBoolean finished = new AtomicBoolean(false);
        final Done once = l -> {
            if (finished.compareAndSet(false, true)) done.got(l != null ? l : stale);
        };
        new Handler(Looper.getMainLooper()).postDelayed(() -> once.got(null), 8000);

        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null,
                        ctx.getMainExecutor(), once::got);
            } else {
                lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, new LocationListener() {
                    @Override public void onLocationChanged(Location l) { once.got(l); }
                    @Override public void onStatusChanged(String p, int s, Bundle e) { }
                    @Override public void onProviderEnabled(String p) { }
                    @Override public void onProviderDisabled(String p) { }
                }, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            once.got(null);
        }
    }
}
