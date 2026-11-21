package com.kuheli.app.ui;

import android.os.Bundle;
import android.text.InputType;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.kuheli.app.KuheliApp;
import com.kuheli.app.R;
import com.kuheli.app.data.Store;
import com.kuheli.app.util.Files;
import com.kuheli.app.util.U;

import org.json.JSONException;

import java.io.IOException;

public class SettingsActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        MaterialToolbar tb = findViewById(R.id.toolbar);
        setupToolbar(tb, true);
        tb.setTitle("Settings");
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_container, new Prefs()).commit();
        }
    }

    public static class Prefs extends PreferenceFragmentCompat {
        private final ActivityResultLauncher<String> backupOut =
                registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                    if (uri == null) return;
                    try {
                        Files.writeText(requireContext().getContentResolver(), uri, Store.exportAll());
                        U.toast(requireContext(), "Backup saved. API keys are not included.");
                    } catch (IOException | JSONException e) {
                        U.toast(requireContext(), "Backup failed: " + e.getMessage());
                    }
                });

        private final ActivityResultLauncher<String[]> backupIn =
                registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri == null) return;
                    try {
                        String json = Files.readText(requireContext().getContentResolver(), uri, 50 * 1024 * 1024);
                        int n = Store.importAll(json);
                        U.toast(requireContext(), "Restored " + n + " chats");
                    } catch (IOException | JSONException e) {
                        U.toast(requireContext(), "Restore failed: " + e.getMessage());
                    }
                });

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);

            numeric("text_size", false);
            numeric("temperature", true);
            numeric("max_tokens", false);
            numeric("max_steps", false);
            numeric("max_tools", false);
            numeric("tool_result_limit", false);
            multiline("system_prompt");
            multiline("node_env");

            Preference theme = findPreference("theme");
            if (theme != null) {
                theme.setOnPreferenceChangeListener((p, value) -> {
                    // Write first so applyTheme() reads the new value, then recreate the screens.
                    com.kuheli.app.util.Prefs.put("theme", String.valueOf(value));
                    KuheliApp.applyTheme();
                    return true;
                });
            }

            click("backup_export", () -> backupOut.launch("kuheli-backup.json"));
            click("backup_import", () -> backupIn.launch(new String[]{"*/*"}));
            click("clear_history", () -> Dialogs.confirm(requireContext(), "Delete all chats?",
                    "Every chat is removed from this device.", () -> {
                        Store.deleteAllChats();
                        U.toast(requireContext(), "History cleared");
                    }));
            click("wipe_all", () -> Dialogs.confirm(requireContext(), "Erase everything?",
                    "Chats, skills, providers and saved API keys are removed. Settings stay.", () -> {
                        Store.wipeEverything();
                        Store.seedSkills(requireContext());
                        U.toast(requireContext(), "All data erased");
                    }));
        }

        private void click(String key, Runnable r) {
            Preference p = findPreference(key);
            if (p != null) p.setOnPreferenceClickListener(x -> {
                r.run();
                return true;
            });
        }

        private void numeric(String key, boolean decimal) {
            EditTextPreference p = findPreference(key);
            if (p != null) p.setOnBindEditTextListener(et -> et.setInputType(InputType.TYPE_CLASS_NUMBER
                    | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0)));
        }

        private void multiline(String key) {
            EditTextPreference p = findPreference(key);
            if (p != null) p.setOnBindEditTextListener(et -> {
                et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                et.setMinLines(4);
                et.setSingleLine(false);
            });
        }
    }
}
