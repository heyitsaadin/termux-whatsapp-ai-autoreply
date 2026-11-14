package com.aadin.sendkb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;

/**
 * SendKB: a small QWERTY keyboard that ALSO listens for commands from Termux.
 *
 *   am broadcast -a com.aadin.sendkb.SEND -p com.aadin.sendkb
 *   am broadcast -a com.aadin.sendkb.TYPE -p com.aadin.sendkb --es text "hello"
 *
 * The layout is written from scratch (plain Android views). It is inspired by
 * the usual phone QWERTY layout; no FlorisBoard code is copied.
 */
public class SendIME extends InputMethodService {
    static final String SEND = "com.aadin.sendkb.SEND";
    static final String TYPE = "com.aadin.sendkb.TYPE";

    private static final String[] NUMBERS =
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};
    private static final String[][] LETTERS = {
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
            {"z", "x", "c", "v", "b", "n", "m"}
    };
    private static final String[][] SYMBOLS = {
            {"@", "#", "$", "%", "&", "-", "+", "(", ")", "/"},
            {"*", "\"", "'", ":", ";", "!", "?", "_", "~"},
            {"<", ">", "=", "[", "]", "{", "}"}
    };

    private boolean shift = false;
    private boolean symbols = false;
    private LinearLayout root;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable repeatDelete = new Runnable() {
        @Override public void run() {
            pressKey(KeyEvent.KEYCODE_DEL);
            handler.postDelayed(this, 50);
        }
    };

    // ---------- commands from Termux ----------
    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return;
            if (SEND.equals(i.getAction())) {
                pressKey(KeyEvent.KEYCODE_ENTER);
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

    @Override public boolean onEvaluateFullscreenMode() {
        return false;
    }

    // ---------- helpers ----------
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void pressKey(int code) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    private void typeText(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(s, 1);
        if (shift) {          // shift is single-use
            shift = false;
            rebuild();
        }
    }

    private GradientDrawable shape(String color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(color));
        g.setCornerRadius(dp(6));
        return g;
    }

    private StateListDrawable keyBg(String normal, String pressed) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, shape(pressed));
        s.addState(new int[]{}, shape(normal));
        return s;
    }

    private Button key(String label, float weight, String color,
                       View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(18);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setBackground(keyBg(color, "#5F6368"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(dp(2), dp(3), dp(2), dp(3));
        b.setLayoutParams(lp);
        if (click != null) b.setOnClickListener(click);
        return b;
    }

    private View spacer(float weight) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, weight));
        return v;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return r;
    }

    private Button charKey(String ch) {
        final String shown = (shift && !symbols) ? ch.toUpperCase() : ch;
        return key(shown, 1f, "#3C4043", v -> typeText(shown));
    }

    private Button backspaceKey() {
        Button b = key("\u232B", 1.5f, "#2B2D30", null);
        b.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    pressKey(KeyEvent.KEYCODE_DEL);
                    handler.postDelayed(repeatDelete, 400);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    handler.removeCallbacks(repeatDelete);
                    return true;
            }
            return true;
        });
        return b;
    }

    // ---------- layout ----------
    private void rebuild() {
        if (root == null) return;
        root.removeAllViews();

        // numbers
        LinearLayout r0 = row();
        for (String n : NUMBERS) r0.addView(key(n, 1f, "#3C4043", v -> typeText(n)));
        root.addView(r0);

        String[][] set = symbols ? SYMBOLS : LETTERS;

        // row 1
        LinearLayout r1 = row();
        for (String s : set[0]) r1.addView(charKey(s));
        root.addView(r1);

        // row 2
        LinearLayout r2 = row();
        r2.addView(spacer(0.5f));
        for (String s : set[1]) r2.addView(charKey(s));
        r2.addView(spacer(0.5f));
        root.addView(r2);

        // row 3: shift, keys, backspace
        LinearLayout r3 = row();
        if (symbols) {
            r3.addView(spacer(1.5f));
        } else {
            r3.addView(key("\u21E7", 1.5f, shift ? "#8AB4F8" : "#2B2D30", v -> {
                shift = !shift;
                rebuild();
            }));
        }
        for (String s : set[2]) r3.addView(charKey(s));
        r3.addView(backspaceKey());
        root.addView(r3);

        // bottom row
        LinearLayout r4 = row();
        r4.addView(key(symbols ? "abc" : "?123", 1.4f, "#2B2D30", v -> {
            symbols = !symbols;
            shift = false;
            rebuild();
        }));
        r4.addView(key("\uD83C\uDF10", 1f, "#2B2D30", v -> {
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        }));
        r4.addView(key(",", 1f, "#3C4043", v -> typeText(",")));
        r4.addView(key("space", 4f, "#3C4043", v -> typeText(" ")));
        r4.addView(key(".", 1f, "#3C4043", v -> typeText(".")));
        r4.addView(key("\u21B5", 1.5f, "#1A73E8", v -> pressKey(KeyEvent.KEYCODE_ENTER)));
        root.addView(r4);
    }

    @Override public View onCreateInputView() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#202124"));
        root.setPadding(dp(3), dp(3), dp(3), dp(3));
        // fixed height so it never fills the screen
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(270)));
        rebuild();
        return root;
    }

    @Override public void onStartInputView(android.view.inputmethod.EditorInfo info,
                                           boolean restarting) {
        super.onStartInputView(info, restarting);
        shift = false;
        symbols = false;
        rebuild();
    }
}
