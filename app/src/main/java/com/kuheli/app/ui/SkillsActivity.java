package com.kuheli.app.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.kuheli.app.R;
import com.kuheli.app.core.ChatEngine;
import com.kuheli.app.data.Skill;
import com.kuheli.app.data.Store;
import com.kuheli.app.util.Files;
import com.kuheli.app.util.U;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Skills are SKILL.md files: front matter (name, description) plus instructions. Invoke one with /name. */
public class SkillsActivity extends BaseActivity {
    private static final int MAX_BYTES = 200 * 1024;
    private RowAdapter adapter;
    private TextView empty;

    private final ActivityResultLauncher<String[]> pickFile =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try {
                    editSkill(null, Files.readText(getContentResolver(), uri, MAX_BYTES));
                } catch (IOException e) {
                    U.toast(this, "Could not read the file: " + e.getMessage());
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        MaterialToolbar tb = findViewById(R.id.toolbar);
        setupToolbar(tb, true);
        tb.setTitle("Skills");
        tb.setSubtitle("Saved instructions you can call with /name");
        empty = findViewById(R.id.empty);

        adapter = new RowAdapter(new RowAdapter.Listener() {
            @Override public void onClick(RowAdapter.Row row) {
                Skill s = (Skill) row.tag;
                editSkill(s.name, s.raw);
            }
            @Override public void onLong(View anchor, RowAdapter.Row row) {
                final Skill s = (Skill) row.tag;
                PopupMenu pm = new PopupMenu(SkillsActivity.this, anchor);
                pm.getMenu().add(0, 1, 0, "Edit");
                pm.getMenu().add(0, 2, 1, "Delete");
                pm.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1) {
                        editSkill(s.name, s.raw);
                    } else {
                        Dialogs.confirm(SkillsActivity.this, "Delete /" + s.name + "?", "The skill file is removed.", () -> {
                            Store.deleteSkill(s.name);
                            reload();
                        });
                    }
                    return true;
                });
                pm.show();
            }
            @Override public void onSwitch(RowAdapter.Row row, boolean on) {
                Store.setSkillEnabled(((Skill) row.tag).name, on);
            }
        });
        RecyclerView rv = findViewById(R.id.recycler);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(v -> addSkill());
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        List<RowAdapter.Row> rows = new ArrayList<>();
        for (Skill s : Store.skills()) {
            RowAdapter.Row r = new RowAdapter.Row("/" + s.name, s.description, "", s);
            r.showSwitch = true;
            r.checked = s.enabled;
            rows.add(r);
        }
        adapter.set(rows);
        empty.setText("No skills yet. Tap + to create or import one.");
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void addSkill() {
        Dialogs.pick(this, "Add a skill",
                Arrays.asList("Write a new skill", "Import a SKILL.md file", "Import from a web address"), choice -> {
                    if (choice.startsWith("Write")) {
                        editSkill(null, Skill.TEMPLATE);
                    } else if (choice.contains("file")) {
                        pickFile.launch(new String[]{"*/*"});
                    } else {
                        Dialogs.input(this, "Web address", "https://raw.githubusercontent.com/.../SKILL.md", "",
                                false, this::importUrl);
                    }
                });
    }

    private void importUrl(final String url) {
        if (!url.trim().startsWith("http")) {
            U.toast(this, "Enter a full http or https address");
            return;
        }
        U.toast(this, "Downloading...");
        ChatEngine.async(() -> fetch(url), result -> {
            if (result.startsWith("Error:")) U.toast(this, result);
            else editSkill(null, result);   // opens in the editor so you can review it before saving
        });
    }

    private static String fetch(String url) throws IOException {
        String u = url.trim();
        if (u.contains("github.com/") && u.contains("/blob/")) {
            u = u.replace("github.com", "raw.githubusercontent.com").replace("/blob/", "/");
        }
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("User-Agent", "Kuheli/1.0");
        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < MAX_BYTES) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }

    private void editSkill(final String oldName, String raw) {
        Dialogs.input(this, oldName == null ? "New skill (SKILL.md)" : "Edit /" + oldName, "", raw, true, text -> {
            try {
                Store.saveSkill(oldName, text);
                reload();
            } catch (IOException e) {
                U.toast(this, e.getMessage());
            }
        });
    }
}
