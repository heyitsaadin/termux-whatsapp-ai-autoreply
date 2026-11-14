package com.aadin.sendkb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputConnection;

/**
 * A keyboard with no visible keys. It listens for broadcasts from Termux
 * and types into / presses Enter in whichever text box is focused.
 *
 *   am broadcast -a com.aadin.sendkb.SEND -p com.aadin.sendkb
 *   am broadcast -a com.aadin.sendkb.TYPE -p com.aadin.sendkb --es text "hello"
 */
public class SendIME extends InputMethodService {
    static final String SEND = "com.aadin.sendkb.SEND";
    static final String TYPE = "com.aadin.sendkb.TYPE";

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return;
            if (SEND.equals(i.getAction())) {
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
            } else if (TYPE.equals(i.getAction())) {
                String t = i.getStringExtra("text");
                if (t != null) ic.commitText(t, 1);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        IntentFilter f = new IntentFilter();
        f.addAction(SEND);
        f.addAction(TYPE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(rx, f);
        }
    }

    @Override public void onDestroy() {
        unregisterReceiver(rx);
        super.onDestroy();
    }

    @Override public View onCreateInputView() {
        return new View(this);  // empty keyboard, no visible keys
    }
}
