package com.kuheli.app;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.kuheli.app.data.Store;
import com.kuheli.app.util.AppLog;
import com.kuheli.app.util.Prefs;

public class KuheliApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        Prefs.init(this);
        AppLog.init(this);
        Store.init(this);
        Store.seedSkills(this);
        if (!Python.isStarted()) Python.start(new AndroidPlatform(this));
        applyTheme();
    }

    /** Dark by default; the Settings page can switch to light or follow the system. */
    public static void applyTheme() {
        String t = Prefs.s("theme", "dark");
        int mode = "light".equals(t) ? AppCompatDelegate.MODE_NIGHT_NO
                : "system".equals(t) ? AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                : AppCompatDelegate.MODE_NIGHT_YES;
        AppCompatDelegate.setDefaultNightMode(mode);
    }
}
