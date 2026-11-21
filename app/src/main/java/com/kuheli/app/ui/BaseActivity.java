package com.kuheli.app.ui;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.kuheli.app.R;
import com.kuheli.app.util.Prefs;

/** Shared behaviour: optional device-lock gate, optional screenshot blocking, toolbar setup. */
public abstract class BaseActivity extends AppCompatActivity {
    private static final long RELOCK_AFTER_MS = 30_000;
    private static long lastPause;
    private static boolean unlockedOnce;
    private boolean authInProgress;

    private final ActivityResultLauncher<Intent> unlock = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                authInProgress = false;
                if (result.getResultCode() == RESULT_OK) {
                    unlockedOnce = true;
                    lastPause = System.currentTimeMillis();
                } else {
                    finishAffinity();
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Prefs.b("secure_screen", false)) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Prefs.b("app_lock", false) || authInProgress) return;
        boolean expired = !unlockedOnce || System.currentTimeMillis() - lastPause > RELOCK_AFTER_MS;
        if (!expired) return;
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km == null || !km.isDeviceSecure()) return;   // no screen lock on the phone, nothing to ask
        Intent i = km.createConfirmDeviceCredentialIntent(getString(R.string.app_name), "Unlock to continue");
        if (i != null) {
            authInProgress = true;
            unlock.launch(i);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!authInProgress) lastPause = System.currentTimeMillis();
    }

    protected void setupToolbar(MaterialToolbar toolbar, boolean showBack) {
        setSupportActionBar(toolbar);
        if (showBack && getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
