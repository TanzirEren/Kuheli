package com.kuheli.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.tabs.TabLayout;
import com.kuheli.app.R;
import com.kuheli.app.data.Chat;
import com.kuheli.app.data.Skill;
import com.kuheli.app.data.Store;
import com.kuheli.app.util.Files;
import com.kuheli.app.util.U;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class HistoryActivity extends BaseActivity {
    private TextView empty;
    private EditText search;
    private TabLayout tabs;
    private RowAdapter adapter;
    private MaterialToolbar toolbar;
    private String pendingExport;

    private final ActivityResultLauncher<String> saveDoc =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/markdown"), uri -> {
                if (uri == null || pendingExport == null) return;
                try {
                    Files.writeText(getContentResolver(), uri, pendingExport);
                    U.toast(this, "Saved");
                } catch (IOException e) {
                    U.toast(this, "Could not save: " + e.getMessage());
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        toolbar = findViewById(R.id.toolbar);
        setupToolbar(toolbar, true);
        empty = findViewById(R.id.empty);
        search = findViewById(R.id.search);
        tabs = findViewById(R.id.tabs);

        tabs.addTab(tabs.newTab().setText("All"));
        tabs.addTab(tabs.newTab().setText("Pinned"));
        tabs.addTab(tabs.newTab().setText("Archived"));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab t) { reload(); }
            @Override public void onTabUnselected(TabLayout.Tab t) { }
            @Override public void onTabReselected(TabLayout.Tab t) { }
        });
        Dialogs.watch(search, q -> reload());

        adapter = new RowAdapter(new RowAdapter.Listener() {
            @Override public void onClick(RowAdapter.Row row) { open((Chat) row.tag); }
            @Override public void onLong(View anchor, RowAdapter.Row row) { menu(anchor, (Chat) row.tag); }
        });
        RecyclerView rv = findViewById(R.id.recycler);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        int tab = tabs.getSelectedTabPosition();
        String q = search.getText().toString();
        List<RowAdapter.Row> rows = new ArrayList<>();
        for (Chat c : Store.chats()) {
            boolean inTab = tab == 2 ? c.archived : (!c.archived && (tab != 1 || c.pinned));
            if (!inTab || !c.matches(q)) continue;
            rows.add(new RowAdapter.Row(c.title, c.preview(),
                    (c.pinned ? "pinned  " : "") + U.when(c.updated), c));
        }
        adapter.set(rows);
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText(q.trim().isEmpty() ? "Nothing here yet." : "No chats match your search.");
        toolbar.setSubtitle(rows.size() + (rows.size() == 1 ? " chat" : " chats"));
    }

    private void open(Chat c) {
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("chat_id", c.id);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private void menu(View anchor, final Chat c) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "Open");
        pm.getMenu().add(0, 2, 1, "Rename");
        pm.getMenu().add(0, 3, 2, c.pinned ? "Unpin" : "Pin");
        pm.getMenu().add(0, 4, 3, c.archived ? "Restore" : "Archive");
        pm.getMenu().add(0, 5, 4, "Save as Markdown");
        pm.getMenu().add(0, 6, 5, "Share");
        pm.getMenu().add(0, 7, 6, "Delete");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: open(c); break;
                case 2:
                    Dialogs.input(this, "Rename chat", "Title", c.title, false, t -> {
                        if (!t.trim().isEmpty()) {
                            c.title = t.trim();
                            Store.save(c);
                            reload();
                        }
                    });
                    break;
                case 3: c.pinned = !c.pinned; Store.save(c); reload(); break;
                case 4: c.archived = !c.archived; Store.save(c); reload(); break;
                case 5:
                    pendingExport = c.toMarkdown();
                    String name = Skill.slug(c.title);
                    saveDoc.launch((name.isEmpty() ? "chat" : name) + ".md");
                    break;
                case 6: U.shareText(this, c.title, c.toMarkdown()); break;
                case 7:
                    Dialogs.confirm(this, "Delete this chat?", "This cannot be undone.", () -> {
                        Store.deleteChat(c.id);
                        reload();
                    });
                    break;
                default: break;
            }
            return true;
        });
        pm.show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.history_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_delete_all) {
            Dialogs.confirm(this, "Delete all chats?", "Every chat, including pinned and archived ones, is removed.",
                    () -> {
                        Store.deleteAllChats();
                        reload();
                    });
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
