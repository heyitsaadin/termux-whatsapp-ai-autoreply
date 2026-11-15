package com.aadin.sendkb;

import android.content.Context;

/**
 * What you are doing right now, and the fixed line built from it.
 *
 * Everything lives on the phone: you set it in the app (Set status), it is
 * stored in Prefs, and nothing is looked up on the internet.
 *
 *   Status "At school", name "Sam"  ->  what  = "at school"
 *                                      reply = "Sam is at school, pls message later."
 *   Status "At school", no name     ->  reply = "I'm at school, pls message later."
 *   No status set                   ->  null, which means: no auto-reply at all.
 *
 * To change the wording of the reply, edit the line in Info below.
 */
final class Status {
    /** What you are doing right now, and the fixed line built from it. */
    static final class Info {
        final String what;    // "at school"
        final String reply;   // "Sam is at school, pls message later."

        Info(String what, String name) {
            this.what = what;
            String who = (name == null || name.trim().isEmpty()) ? "I'm" : name.trim() + " is";
            this.reply = who + " " + what + ", pls message later.";
        }
    }

    private Status() { }

    /** The current status, or null when none is set. */
    static Info current(Context ctx) {
        String what = clean(Prefs.getTag(ctx));
        return what.isEmpty() ? null : new Info(what, Prefs.getName(ctx));
    }

    /** Just the fixed line, or null when no status is set. */
    static String currentReply(Context ctx) {
        Info info = current(ctx);
        return info == null ? null : info.reply;
    }

    /**
     * "\uD83C\uDFEB At school" -> "at school": drop emoji/symbols at both ends and
     * lower-case the first letter so it reads naturally after "is".
     */
    static String clean(String s) {
        int start = 0, end = s.length();
        while (start < end) {
            int cp = s.codePointAt(start);
            if (Character.isLetterOrDigit(cp)) break;
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = s.codePointBefore(end);
            if (Character.isLetterOrDigit(cp)) break;
            end -= Character.charCount(cp);
        }
        String t = s.substring(start, end).trim();
        if (t.length() > 1 && Character.isUpperCase(t.charAt(0))
                && Character.isLowerCase(t.charAt(1))) {
            t = Character.toLowerCase(t.charAt(0)) + t.substring(1);
        }
        return t;
    }
}
