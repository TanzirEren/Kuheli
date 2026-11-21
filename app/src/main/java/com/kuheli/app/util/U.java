package com.kuheli.app.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** Small helpers shared by the screens. */
public final class U {
    private U() {}

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static void toast(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
    }

    public static void copy(Context c, String label, String text) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            toast(c, "Copied");
        }
    }

    public static void shareText(Context c, String title, String text) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, title);
        send.putExtra(Intent.EXTRA_TEXT, text);
        c.startActivity(Intent.createChooser(send, title));
    }

    public static void haptic(View v) {
        if (Prefs.b("haptics", true) && v != null) {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    public static String oneLine(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) + "..." : t;
    }

    public static String when(long ts) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
                .format(new Date(ts));
    }

    public static String timeOnly(long ts) {
        return DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(new Date(ts));
    }

    /** Rough token estimate (about four characters per token). */
    public static int tokens(String s) {
        return s == null ? 0 : (s.length() + 3) / 4;
    }

    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }
}
