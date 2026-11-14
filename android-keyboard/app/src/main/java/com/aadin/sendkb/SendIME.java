package com.aadin.sendkb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A keyboard with no keys. It listens for broadcasts from Termux
 * and types into / presses Enter in whichever text box is focused.
 *
 *   am broadcast -a com.aadin.sendkb.SEND -p com.aadin.sendkb
 *   am broadcast -a com.aadin.sendkb.TYPE -p com.aadin.sendkb --es text "hello"
 *
 * It shows a thin bar with a button to switch back to your normal keyboard.
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

    // never take over the whole screen (landscape / some phones do this by default)
    @Override public boolean onEvaluateFullscreenMode() {
        return false;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override public View onCreateInputView() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#202124"));
        bar.setPadding(dp(12), 0, dp(12), 0);
        // fixed height: this is what stops it filling the screen
        bar.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        TextView label = new TextView(this);
        label.setText("Send Keyboard (no keys)");
        label.setTextColor(Color.WHITE);
        label.setTextSize(14);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button switchBtn = new Button(this);
        switchBtn.setText("Switch keyboard");
        switchBtn.setAllCaps(false);
        switchBtn.setOnClickListener(v -> {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });

        bar.addView(label);
        bar.addView(switchBtn);
        return bar;
    }
}
