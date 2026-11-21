package com.kuheli.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

/** Typed access to the settings stored by the Settings page. */
public final class Prefs {
    private static SharedPreferences sp;

    private Prefs() {}

    public static void init(Context c) {
        sp = PreferenceManager.getDefaultSharedPreferences(c.getApplicationContext());
    }

    public static String s(String key, String def) {
        try {
            String v = sp.getString(key, def);
            return v == null ? def : v;
        } catch (ClassCastException e) {
            return def;
        }
    }

    public static boolean b(String key, boolean def) {
        try {
            return sp.getBoolean(key, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    public static float f(String key, float def) {
        try {
            return Float.parseFloat(s(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static int i(String key, int def) {
        try {
            return Integer.parseInt(s(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static void put(String key, String value) {
        sp.edit().putString(key, value).apply();
    }

    public static void putB(String key, boolean value) {
        sp.edit().putBoolean(key, value).apply();
    }

    /** Chat text size in sp. The whole app is deliberately small, 12 by default. */
    public static float textSize() {
        return Math.max(9f, Math.min(20f, f("text_size", 12f)));
    }
}
