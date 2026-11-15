package com.aadin.sendkb;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Locale;

/**
 * "Set your tag" dialog: the in-app version of the /tag Discord command.
 * Quick tag chip, custom text, place, and Clear. Builds the tag text the same
 * way the bot does, then writes it to Lanyard with the key you typed once.
 */
final class TagDialog {
    private static final int MAX_TAG = 70;
    private static final String PIN = "\uD83D\uDCCD";

    private TagDialog() { }

    static void show(final Activity a, final Runnable onDone) {
        final View v = a.getLayoutInflater().inflate(R.layout.dialog_tag, null);
        final ChipGroup chips = v.findViewById(R.id.tagChips);
        final TextInputEditText custom = v.findViewById(R.id.tagCustom);
        final TextInputEditText place = v.findViewById(R.id.tagPlace);
        final TextInputLayout keyLayout = v.findViewById(R.id.keyLayout);
        final TextInputEditText keyField = v.findViewById(R.id.keyField);
        keyLayout.setVisibility(Prefs.getKey(a) == null ? View.VISIBLE : View.GONE);

        final AlertDialog d = new MaterialAlertDialogBuilder(a)
                .setTitle("Set your tag")
                .setView(v)
                .setPositiveButton("Save", null)
                .setNeutralButton("Clear tag", null)
                .setNegativeButton("Cancel", null)
                .create();
        d.show();

        // set after show() so a problem keeps the dialog open instead of closing it
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            String text = compose(selectedChip(chips), textOf(custom), textOf(place));
            if (text.isEmpty()) {
                toast(a, "Pick a quick tag, type one, or add a place");
                return;
            }
            run(a, d, keyLayout, keyField, text, onDone);
        });
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(b ->
                run(a, d, keyLayout, keyField, null, onDone));
    }

    /** text == null means clear the tag. */
    private static void run(final Activity a, final AlertDialog d,
                            final TextInputLayout keyLayout, final TextInputEditText keyField,
                            final String text, final Runnable onDone) {
        String saved = Prefs.getKey(a);
        final String key = saved != null ? saved : textOf(keyField);
        if (key.isEmpty()) {
            keyLayout.setVisibility(View.VISIBLE);
            keyLayout.setError("Paste your Lanyard API key");
            return;
        }
        keyLayout.setError(null);
        setBusy(d, true);
        final Context app = a.getApplicationContext();

        new Thread(() -> {
            int r = 0; // 0 ok, 1 bad key, 2 failed
            try {
                if (text == null) {
                    Lanyard.clearTag(key);
                } else {
                    Lanyard.setTag(key, text);
                }
            } catch (Lanyard.BadKey e) {
                r = 1;
            } catch (Exception e) {
                r = 2;
            }
            final int result = r;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (result == 0) {
                    Prefs.setKey(app, key);
                    d.dismiss();
                    toast(a, text == null ? "Tag cleared. No auto-reply until you set one."
                                          : "Tag set to " + text);
                    onDone.run();
                } else if (result == 1) {
                    Prefs.clearKey(app);
                    keyLayout.setVisibility(View.VISIBLE);
                    keyLayout.setError("Lanyard rejected that key. Paste it again.");
                    setBusy(d, false);
                } else {
                    toast(a, "Couldn't reach Lanyard. Check the internet and try again.");
                    setBusy(d, false);
                }
            });
        }).start();
    }

    private static void setBusy(AlertDialog d, boolean busy) {
        d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(!busy);
        d.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!busy);
    }

    private static String selectedChip(ChipGroup chips) {
        int id = chips.getCheckedChipId();
        if (id == View.NO_ID) return "";
        Chip c = chips.findViewById(id);
        return c == null ? "" : c.getText().toString().trim();
    }

    private static String textOf(TextView t) {
        CharSequence s = t.getText();
        return s == null ? "" : s.toString().trim();
    }

    /** Same rules as the /tag bot: custom beats preset; place adds "to"/"at". */
    static String compose(String preset, String custom, String place) {
        String text = !custom.isEmpty() ? custom : preset;
        if (!place.isEmpty()) {
            if (text.isEmpty()) {
                text = PIN + " At " + place;
            } else {
                String word = text.toLowerCase(Locale.ROOT).matches(".*travell?ing.*") ? "to" : "at";
                text = text + " " + word + " " + place;
            }
        }
        int points = text.codePointCount(0, text.length());
        if (points > MAX_TAG) text = text.substring(0, text.offsetByCodePoints(0, MAX_TAG));
        return text;
    }

    private static void toast(Context c, String s) {
        Toast.makeText(c.getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }
}
