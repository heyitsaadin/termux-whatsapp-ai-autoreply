package com.aadin.sendkb;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * "Set your tag" dialog: the in-app version of the /tag Discord command.
 * Quick tag chip, custom text, place (with search + use my location), and Clear.
 * Builds the tag text the same way the bot does, then writes it to Lanyard with
 * the key you typed once.
 */
final class TagDialog {
    private static final int MAX_TAG = 70;
    private static final String PIN = "\uD83D\uDCCD";

    private TagDialog() { }

    static void show(final ComponentActivity a, final Runnable onDone) {
        final View v = a.getLayoutInflater().inflate(R.layout.dialog_tag, null);
        final ChipGroup chips = v.findViewById(R.id.tagChips);
        final TextInputEditText custom = v.findViewById(R.id.tagCustom);
        final TextInputEditText place = v.findViewById(R.id.tagPlace);
        final ChipGroup placeChips = v.findViewById(R.id.placeChips);
        final TextInputLayout keyLayout = v.findViewById(R.id.keyLayout);
        final TextInputEditText keyField = v.findViewById(R.id.keyField);
        keyLayout.setVisibility(Prefs.getKey(a) == null ? View.VISIBLE : View.GONE);

        final PlacePicker picker = new PlacePicker(a, place, placeChips);

        final AlertDialog d = new MaterialAlertDialogBuilder(a)
                .setTitle("Set your tag")
                .setView(v)
                .setPositiveButton("Save", null)
                .setNeutralButton("Clear tag", null)
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnDismissListener(x -> picker.release());
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

    /** Suggestion chips under the place field: use my location, saved places, live search. */
    private static final class PlacePicker {
        private final ComponentActivity a;
        private final TextInputEditText field;
        private final ChipGroup box;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final AtomicInteger seq = new AtomicInteger();
        private final AtomicReference<ActivityResultLauncher<String>> launcher = new AtomicReference<>();
        private boolean muted;
        private final Runnable search = new Runnable() {
            @Override public void run() { refresh(); }
        };

        PlacePicker(ComponentActivity a, TextInputEditText field, ChipGroup box) {
            this.a = a;
            this.field = field;
            this.box = box;
            field.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
                @Override public void afterTextChanged(Editable s) {
                    if (muted) return;
                    handler.removeCallbacks(search);
                    handler.postDelayed(search, 350);
                }
            });
            render("", null);
        }

        void release() {
            handler.removeCallbacks(search);
            seq.incrementAndGet();
            ActivityResultLauncher<String> l = launcher.getAndSet(null);
            if (l != null) l.unregister();
        }

        private void refresh() {
            final String q = textOf(field);
            final int mine = seq.incrementAndGet();
            render(q, null);
            if (q.length() < 2) return;
            new Thread(() -> {
                final List<String[]> live = PlaceFinder.search(q);
                a.runOnUiThread(() -> {
                    if (seq.get() == mine && !a.isFinishing() && !a.isDestroyed()) render(q, live);
                });
            }).start();
        }

        private void render(String q, List<String[]> live) {
            box.removeAllViews();
            addChip("\uD83C\uDFAF Use my location", true, null);
            String low = q.toLowerCase(Locale.ROOT);
            Set<String> seen = new HashSet<>();
            for (String f : PlaceFinder.FAVORITES) {
                if (low.isEmpty() || f.toLowerCase(Locale.ROOT).contains(low)) {
                    seen.add(f.toLowerCase(Locale.ROOT));
                    addChip("\u2B50 " + f, false, f);
                }
            }
            if (live != null) {
                for (String[] p : live) {
                    if (seen.add(p[1].toLowerCase(Locale.ROOT))) {
                        addChip(PIN + " " + p[0], false, p[1]);
                    }
                }
            }
        }

        private void addChip(String label, final boolean locate, final String value) {
            Chip c = new Chip(box.getContext());
            c.setText(label);
            c.setOnClickListener(x -> {
                if (locate) {
                    useMyLocation();
                } else {
                    pick(value);
                }
            });
            box.addView(c);
        }

        private void pick(String value) {
            handler.removeCallbacks(search);
            seq.incrementAndGet();
            muted = true;
            field.setText(value);
            field.setSelection(field.length());
            muted = false;
            box.removeAllViews();
        }

        private void useMyLocation() {
            if (a.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                locate();
                return;
            }
            ActivityResultLauncher<String> l = launcher.get();
            if (l == null) {
                l = a.getActivityResultRegistry().register(
                        "tag-loc-" + System.nanoTime(),
                        new ActivityResultContracts.RequestPermission(),
                        granted -> {
                            if (granted) {
                                locate();
                            } else {
                                toast(a, "Allow location to use this, or type the place instead");
                            }
                        });
                launcher.set(l);
            }
            l.launch(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        private void locate() {
            toast(a, "Finding where you are...");
            Locator.find(a, loc -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (loc == null) {
                    toast(a, "Couldn't get your location. Is location turned on?");
                    return;
                }
                final double lat = loc.getLatitude();
                final double lon = loc.getLongitude();
                new Thread(() -> {
                    final String name = PlaceFinder.reverse(lat, lon);
                    a.runOnUiThread(() -> {
                        if (a.isFinishing() || a.isDestroyed()) return;
                        if (name == null) {
                            toast(a, "Couldn't work out the place name. Type it instead.");
                        } else {
                            pick(name);
                        }
                    });
                }).start();
            });
        }
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
