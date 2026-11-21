package com.kuheli.app.ui;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import com.kuheli.app.R;
import com.kuheli.app.core.ChatEngine;
import com.kuheli.app.core.NodeService;
import com.kuheli.app.core.Native;
import com.kuheli.app.data.Chat;
import com.kuheli.app.data.Msg;
import com.kuheli.app.data.Provider;
import com.kuheli.app.data.Skill;
import com.kuheli.app.data.Store;
import com.kuheli.app.util.AppLog;
import com.kuheli.app.util.Files;
import com.kuheli.app.util.Prefs;
import com.kuheli.app.util.U;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ChatActivity extends BaseActivity {
    private static final String[] COMMANDS = {"new", "clear", "model", "system", "rename", "pin", "archive",
            "export", "share", "history", "skills", "tools", "providers", "settings", "logs", "help"};
    private static final int MAX_ATTACH_BYTES = 200 * 1024;

    private static final String HELP = "**Commands**\n\n"
            + "- `/new` start a new chat\n- `/clear` empty this chat\n- `/model [name]` choose or set the model\n"
            + "- `/system [text]` extra instructions for this chat\n- `/rename <title>`\n- `/pin`, `/archive`\n"
            + "- `/export` save as Markdown, `/share` send as text\n"
            + "- `/history`, `/skills`, `/tools`, `/providers`, `/settings`, `/logs`\n\n"
            + "**Skills**\n\nType `/` followed by a skill name and your request, for example `/code-review this function`.\n\n"
            + "**Tips**\n\nLong-press a message to copy, edit, regenerate or delete it. Tap the title to switch model.";

    private DrawerLayout drawer;
    private RecyclerView recycler;
    private MessageAdapter adapter;
    private EditText input;
    private ImageButton btnSend;
    private TextView status, attachChip;
    private View slashRow;
    private LinearLayout slashList;
    private FloatingActionButton fabDown;

    private final ChatEngine engine = new ChatEngine();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Chat chat;
    private Msg streaming;
    private final StringBuilder streamBuf = new StringBuilder();
    private final AtomicBoolean renderPending = new AtomicBoolean(false);
    private boolean destroyed;

    private String attachName, attachText;
    private String pendingExport, pendingExportName;

    private final ActivityResultLauncher<String[]> pickFile =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) attach(uri);
            });

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

    // ------------------------------------------------------------------------------------ setup

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        drawer = findViewById(R.id.drawer);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        recycler = findViewById(R.id.recycler);
        input = findViewById(R.id.input);
        btnSend = findViewById(R.id.btn_send);
        ImageButton btnAttach = findViewById(R.id.btn_attach);
        status = findViewById(R.id.status);
        attachChip = findViewById(R.id.attach_chip);
        slashRow = findViewById(R.id.slash_row);
        slashList = findViewById(R.id.slash_list);
        fabDown = findViewById(R.id.fab_down);
        NavigationView nav = findViewById(R.id.nav);

        setupToolbar(toolbar, false);
        toolbar.setNavigationIcon(R.drawable.ic_menu);
        toolbar.setNavigationOnClickListener(v -> drawer.openDrawer(GravityCompat.START));
        toolbar.setOnClickListener(v -> pickModel());

        LinearLayoutManager lm = new LinearLayoutManager(this);
        recycler.setLayoutManager(lm);
        recycler.setItemAnimator(null);
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                fabDown.setVisibility(rv.canScrollVertically(1) ? View.VISIBLE : View.GONE);
            }
        });
        fabDown.setOnClickListener(v -> scrollToBottom());

        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Prefs.textSize());
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { updateSlash(e.toString()); }
        });
        input.setOnKeyListener((v, code, ev) -> {
            if (Prefs.b("send_on_enter", false) && code == KeyEvent.KEYCODE_ENTER
                    && ev.getAction() == KeyEvent.ACTION_DOWN && !ev.isShiftPressed()) {
                onSendClicked();
                return true;
            }
            return false;
        });
        btnSend.setOnClickListener(v -> onSendClicked());
        btnAttach.setOnClickListener(v -> pickFile.launch(new String[]{"*/*"}));
        attachChip.setOnClickListener(v -> clearAttachment());

        nav.setNavigationItemSelectedListener(item -> {
            drawer.closeDrawers();
            int id = item.getItemId();
            if (id == R.id.nav_new) openChatSafely(newChat());
            else if (id == R.id.nav_history) startActivity(new Intent(this, HistoryActivity.class));
            else if (id == R.id.nav_skills) startActivity(new Intent(this, SkillsActivity.class));
            else if (id == R.id.nav_providers) startActivity(new Intent(this, ProvidersActivity.class));
            else if (id == R.id.nav_tools) startActivity(new Intent(this, ToolsActivity.class));
            else if (id == R.id.nav_logs) startActivity(new Intent(this, LogsActivity.class));
            else if (id == R.id.nav_settings) startActivity(new Intent(this, SettingsActivity.class));
            else if (id == R.id.nav_about) startActivity(new Intent(this, AboutActivity.class));
            return true;
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.START)) {
                    drawer.closeDrawers();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        Chat start = Store.chat(getIntent().getStringExtra("chat_id"));
        if (start == null) start = Store.chat(Prefs.s("last_chat", ""));
        openChat(start != null ? start : newChat());

        NodeService.ensureStarted(this);
        if (Store.activeProvider() == null) {
            status.setText("No provider is ready. Open Providers from the menu and add your API key.");
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Chat c = Store.chat(intent.getStringExtra("chat_id"));
        if (c != null) openChatSafely(c);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) adapter.notifyDataSetChanged();
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Prefs.textSize());
        refreshHeader();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ------------------------------------------------------------------------------------ chats

    private Chat newChat() {
        Chat c = new Chat();
        Provider p = Store.activeProvider();
        if (p != null) c.providerId = p.id;
        return c;
    }

    private void openChatSafely(Chat c) {
        if (engine.isRunning()) {
            U.toast(this, "Wait for the current reply to finish, or stop it first.");
            return;
        }
        openChat(c);
    }

    private void openChat(Chat c) {
        chat = c;
        streaming = null;
        adapter = new MessageAdapter(chat.msgs, Md.get(this), this::showMessageMenu);
        recycler.setAdapter(adapter);
        Prefs.put("last_chat", chat.id);
        clearAttachment();
        refreshHeader();
        refreshStatus();
        scrollToBottom();
    }

    private void persist() {
        if (!chat.msgs.isEmpty()) Store.save(chat);
    }

    private Provider resolveProvider() {
        Provider p = Store.provider(chat.providerId);
        if (p == null || !p.usable()) p = Store.activeProvider();
        return p;
    }

    private void refreshHeader() {
        if (getSupportActionBar() == null || chat == null) return;
        getSupportActionBar().setTitle(chat.title);
        Provider p = resolveProvider();
        String model = !chat.model.isEmpty() ? chat.model : (p != null ? p.model : "");
        getSupportActionBar().setSubtitle(p == null ? "No provider" : p.name + "  /  " + model);
    }

    private void refreshStatus() {
        if (chat == null) return;
        if (engine.isRunning()) {
            status.setText("Working...");
        } else if (status != null) {
            status.setText("~" + chat.approxTokens() + " tokens  /  " + chat.msgs.size() + " messages");
        }
    }

    private void scrollToBottom() {
        if (adapter != null && adapter.getItemCount() > 0) {
            recycler.scrollToPosition(adapter.getItemCount() - 1);
        }
    }

    // ------------------------------------------------------------------------------------ menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.chat_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem pin = menu.findItem(R.id.action_pin);
        if (pin != null && chat != null) pin.setTitle(chat.pinned ? "Unpin" : "Pin");
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_model) pickModel();
        else if (id == R.id.action_rename) renameChat(null);
        else if (id == R.id.action_pin) togglePin();
        else if (id == R.id.action_archive) archiveChat();
        else if (id == R.id.action_export) exportChat();
        else if (id == R.id.action_share) U.shareText(this, chat.title, chat.toMarkdown());
        else if (id == R.id.action_system) editSystem(null);
        else if (id == R.id.action_clear) clearChat();
        else if (id == R.id.action_delete) deleteChat();
        else return super.onOptionsItemSelected(item);
        return true;
    }

    private void renameChat(String preset) {
        if (preset != null) {
            chat.title = preset;
            persist();
            refreshHeader();
            return;
        }
        Dialogs.input(this, "Rename chat", "Title", chat.title, false, t -> {
            if (!t.trim().isEmpty()) {
                chat.title = t.trim();
                persist();
                refreshHeader();
            }
        });
    }

    private void togglePin() {
        chat.pinned = !chat.pinned;
        persist();
        U.toast(this, chat.pinned ? "Pinned" : "Unpinned");
    }

    private void archiveChat() {
        if (chat.msgs.isEmpty()) return;
        chat.archived = true;
        persist();
        U.toast(this, "Archived. Find it in History.");
        openChat(newChat());
    }

    private void exportChat() {
        if (chat.msgs.isEmpty()) {
            U.toast(this, "Nothing to export yet");
            return;
        }
        pendingExport = chat.toMarkdown();
        pendingExportName = Skill.slug(chat.title);
        saveDoc.launch((pendingExportName.isEmpty() ? "chat" : pendingExportName) + ".md");
    }

    private void editSystem(String preset) {
        if (preset != null) {
            chat.system = preset;
            persist();
            U.toast(this, "Chat instructions updated");
            return;
        }
        Dialogs.input(this, "Instructions for this chat", "Added after your global system prompt",
                chat.system, true, t -> {
                    chat.system = t.trim();
                    persist();
                });
    }

    private void clearChat() {
        if (engine.isRunning()) return;
        int n = chat.msgs.size();
        chat.msgs.clear();
        adapter.notifyItemRangeRemoved(0, n);
        persist();
        refreshStatus();
    }

    private void deleteChat() {
        Dialogs.confirm(this, "Delete this chat?", "This cannot be undone.", () -> {
            if (engine.isRunning()) return;
            Store.deleteChat(chat.id);
            openChat(newChat());
        });
    }

    // ------------------------------------------------------------------------------------ models

    private void pickModel() {
        if (engine.isRunning()) return;
        final List<Provider> list = new ArrayList<>();
        for (Provider p : Store.providers()) if (p.enabled) list.add(p);
        if (list.isEmpty()) {
            startActivity(new Intent(this, ProvidersActivity.class));
            return;
        }
        List<String> names = new ArrayList<>();
        for (Provider p : list) names.add(p.name);
        Dialogs.pick(this, "Provider", names, chosen -> {
            for (Provider p : list) {
                if (p.name.equals(chosen)) {
                    chat.providerId = p.id;
                    chat.model = "";
                    persist();
                    refreshHeader();
                    chooseModel(p);
                    return;
                }
            }
        });
    }

    private void chooseModel(final Provider p) {
        final JSONObject pj;
        try {
            pj = p.toEngine(null);
        } catch (JSONException e) {
            return;
        }
        status.setText("Loading models...");
        ChatEngine.callAsync("list_models", pj.toString(), null, false, json -> {
            if (destroyed) return;
            refreshStatus();
            List<String> items = new ArrayList<>();
            String manual = "Type a model name...";
            String keep = "Keep default: " + p.model;
            items.add(keep);
            try {
                JSONObject o = new JSONObject(json);
                JSONArray arr = o.optJSONArray("models");
                if (arr != null) for (int i = 0; i < arr.length(); i++) items.add(arr.getString(i));
                if (!o.optBoolean("ok")) U.toast(this, o.optString("message", "Could not load models"));
            } catch (JSONException ignored) {
                // fall through with just the manual options
            }
            items.add(manual);
            Dialogs.pick(this, "Model", items, m -> {
                if (m.equals(keep)) {
                    chat.model = "";
                } else if (m.equals(manual)) {
                    Dialogs.input(this, "Model name", "e.g. gpt-4o-mini", chat.model, false, t -> {
                        chat.model = t.trim();
                        persist();
                        refreshHeader();
                    });
                    return;
                } else {
                    chat.model = m;
                }
                persist();
                refreshHeader();
            });
        });
    }

    // ------------------------------------------------------------------------------------ sending

    private void onSendClicked() {
        if (engine.isRunning()) {
            engine.cancel();
            status.setText("Stopping...");
            return;
        }
        String raw = input.getText().toString().trim();
        if (raw.isEmpty() && attachText == null) return;
        if (raw.startsWith("/") && handleSlash(raw)) {
            input.setText("");
            return;
        }

        String skillName = null;
        String text = raw;
        if (raw.startsWith("/")) {
            String[] parts = raw.substring(1).split("\\s+", 2);
            Skill s = Store.skill(Skill.slug(parts[0]));
            if (s != null && s.enabled) {
                skillName = s.name;
                text = parts.length > 1 ? parts[1] : "Use the " + s.name + " skill.";
            }
        }
        if (attachText != null) {
            text = text + "\n\nAttached file `" + attachName + "`:\n```\n" + attachText + "\n```";
            clearAttachment();
        }

        Provider p = resolveProvider();
        if (p == null) {
            new MaterialAlertDialogBuilder(this).setTitle("No provider yet")
                    .setMessage("Add an API key for Google Gemini, OpenRouter, Groq or another provider first.")
                    .setPositiveButton("Open Providers", (d, w) -> startActivity(new Intent(this, ProvidersActivity.class)))
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        if (chat.providerId.isEmpty()) chat.providerId = p.id;

        Msg user = new Msg("user", text);
        chat.msgs.add(user);
        adapter.notifyItemInserted(chat.msgs.size() - 1);
        if (chat.msgs.size() == 1 && Prefs.b("auto_title", true)) {
            chat.title = U.oneLine(raw.isEmpty() ? "Attachment" : raw.replaceFirst("^/\\S+\\s*", ""), 40);
            if (chat.title.isEmpty()) chat.title = "New chat";
        }
        input.setText("");
        U.haptic(btnSend);
        persist();
        refreshHeader();
        startGeneration(p, skillName);
    }

    private void startGeneration(Provider p, String skillName) {
        final JSONObject cfg;
        try {
            cfg = ChatEngine.buildConfig(chat, p, skillName);
        } catch (JSONException e) {
            U.toast(this, "Could not prepare the request: " + e.getMessage());
            return;
        }
        streaming = new Msg("assistant", "");
        synchronized (streamBuf) {
            streamBuf.setLength(0);
        }
        chat.msgs.add(streaming);
        adapter.notifyItemInserted(chat.msgs.size() - 1);
        scrollToBottom();
        setBusy(true);
        engine.run(cfg, listener);
    }

    private void setBusy(boolean busy) {
        btnSend.setImageResource(busy ? R.drawable.ic_stop : R.drawable.ic_send);
        btnSend.setContentDescription(busy ? "Stop" : "Send");
        if (busy && Prefs.b("keep_awake", true)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (busy) status.setText("Working..."); else refreshStatus();
    }

    // ------------------------------------------------------------------------------------ streaming

    private final Runnable renderRunnable = () -> {
        renderPending.set(false);
        if (destroyed || streaming == null) return;
        String text;
        synchronized (streamBuf) {
            text = streamBuf.toString();
        }
        int idx = chat.msgs.indexOf(streaming);
        if (idx < 0) return;
        boolean atBottom = !recycler.canScrollVertically(1);
        streaming.content = text;
        adapter.notifyItemChanged(idx, MessageAdapter.PAYLOAD_TEXT);
        if (atBottom) recycler.post(this::scrollToBottom);
    };

    private final ChatEngine.Listener listener = new ChatEngine.Listener() {
        @Override
        public void onToken(String t) {
            synchronized (streamBuf) {
                streamBuf.append(t);
            }
            if (renderPending.compareAndSet(false, true)) ui.postDelayed(renderRunnable, 60);
        }

        @Override
        public void onToolCall(String name, String args) {
            ui.post(() -> addToolLine("> " + name + " " + U.oneLine(args, 90)));
        }

        @Override
        public void onToolResult(String name, String result) {
            ui.post(() -> addToolLine("< " + U.oneLine(result, 110)));
        }

        @Override
        public void onStatus(String message) {
            ui.post(() -> {
                if (!destroyed) status.setText(U.oneLine(message, 120));
            });
        }

        @Override
        public void onDone(String json) {
            ui.post(() -> finishReply(json));
        }

        @Override
        public void onError(String message) {
            ui.post(() -> failReply(message));
        }

        @Override
        public int approve(String name, String args) {
            final CountDownLatch latch = new CountDownLatch(1);
            final int[] decision = {0};
            ui.post(() -> showApprove(name, args, decision, latch));
            try {
                if (!latch.await(5, TimeUnit.MINUTES)) return 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            }
            return decision[0];
        }
    };

    private void addToolLine(String line) {
        if (destroyed || streaming == null) return;
        streaming.tools.add(line);
        int idx = chat.msgs.indexOf(streaming);
        if (idx >= 0) adapter.notifyItemChanged(idx, MessageAdapter.PAYLOAD_TEXT);
    }

    private void showApprove(String name, String args, int[] decision, CountDownLatch latch) {
        if (destroyed || isFinishing()) {
            latch.countDown();
            return;
        }
        new MaterialAlertDialogBuilder(this).setTitle("Run this tool?")
                .setMessage(name + "\n\n" + U.oneLine(args, 600))
                .setCancelable(false)
                .setPositiveButton("Allow", (d, w) -> { decision[0] = 1; latch.countDown(); })
                .setNeutralButton("Allow for this reply", (d, w) -> { decision[0] = 2; latch.countDown(); })
                .setNegativeButton("Deny", (d, w) -> { decision[0] = 0; latch.countDown(); })
                .show();
    }

    private void finishReply(String json) {
        if (destroyed) return;
        ui.removeCallbacks(renderRunnable);
        renderPending.set(false);
        String content = "";
        int completion = 0;
        try {
            JSONObject o = new JSONObject(json);
            content = o.optString("content", "");
            JSONObject usage = o.optJSONObject("usage");
            if (usage != null) completion = usage.optInt("completion", 0);
        } catch (JSONException e) {
            AppLog.e("chat", "Bad result JSON: " + e.getMessage());
        }
        if (streaming != null) {
            int idx = chat.msgs.indexOf(streaming);
            streaming.content = content;
            streaming.tokens = completion > 0 ? completion : U.tokens(content);
            if (content.trim().isEmpty() && streaming.tools.isEmpty() && idx >= 0) {
                chat.msgs.remove(idx);
                adapter.notifyItemRemoved(idx);
            } else if (idx >= 0) {
                adapter.notifyItemChanged(idx);
            }
        }
        endReply();
    }

    private void failReply(String message) {
        if (destroyed) return;
        ui.removeCallbacks(renderRunnable);
        renderPending.set(false);
        if (streaming != null) {
            String partial;
            synchronized (streamBuf) {
                partial = streamBuf.toString();
            }
            streaming.content = partial.isEmpty() ? "**Error:** " + message
                    : partial + "\n\n**Error:** " + message;
            int idx = chat.msgs.indexOf(streaming);
            if (idx >= 0) adapter.notifyItemChanged(idx);
        }
        endReply();
    }

    private void endReply() {
        streaming = null;
        setBusy(false);
        persist();
        scrollToBottom();
    }

    // ------------------------------------------------------------------------------------ message actions

    private void showMessageMenu(View anchor, int pos) {
        if (pos < 0 || pos >= chat.msgs.size()) return;
        final Msg m = chat.msgs.get(pos);
        if (m == streaming) return;
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "Copy");
        pm.getMenu().add(0, 2, 1, "Select text");
        pm.getMenu().add(0, 3, 2, "Share");
        if (m.isUser()) pm.getMenu().add(0, 4, 3, "Edit and resend");
        else pm.getMenu().add(0, 5, 3, "Regenerate");
        pm.getMenu().add(0, 6, 4, "Delete");
        pm.setOnMenuItemClickListener(item -> {
            int idx = chat.msgs.indexOf(m);
            if (idx < 0) return true;
            switch (item.getItemId()) {
                case 1: U.copy(this, "Kuheli", m.content); break;
                case 2: Dialogs.selectable(this, "Select text", m.content); break;
                case 3: U.shareText(this, chat.title, m.content); break;
                case 4:
                    if (engine.isRunning()) break;
                    input.setText(m.content);
                    input.setSelection(input.length());
                    truncateFrom(idx);
                    break;
                case 5: regenerateFrom(idx); break;
                case 6:
                    if (engine.isRunning()) break;
                    chat.msgs.remove(idx);
                    adapter.notifyItemRemoved(idx);
                    persist();
                    refreshStatus();
                    break;
                default: break;
            }
            return true;
        });
        pm.show();
    }

    private void truncateFrom(int idx) {
        int count = chat.msgs.size() - idx;
        if (count <= 0) return;
        while (chat.msgs.size() > idx) chat.msgs.remove(chat.msgs.size() - 1);
        adapter.notifyItemRangeRemoved(idx, count);
        persist();
        refreshStatus();
    }

    private void regenerateFrom(int idx) {
        if (engine.isRunning()) return;
        truncateFrom(idx);
        if (chat.msgs.isEmpty() || !chat.msgs.get(chat.msgs.size() - 1).isUser()) return;
        Provider p = resolveProvider();
        if (p != null) startGeneration(p, null);
    }

    // ------------------------------------------------------------------------------------ attachments

    private void attach(Uri uri) {
        try {
            byte[] data = Files.readBytes(getContentResolver(), uri, MAX_ATTACH_BYTES);
            if (Files.looksBinary(data)) {
                U.toast(this, "Only text files can be attached.");
                return;
            }
            attachName = displayName(uri);
            attachText = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            String hash = Native.hash(data);
            attachChip.setText("Attached: " + attachName + "  " + U.size(data.length)
                    + (hash.isEmpty() ? "" : "  sha256 " + hash.substring(0, 8)) + "   (tap to remove)");
            attachChip.setVisibility(View.VISIBLE);
        } catch (IOException e) {
            U.toast(this, "Could not read the file: " + e.getMessage());
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return "file";
    }

    private void clearAttachment() {
        attachName = null;
        attachText = null;
        if (attachChip != null) attachChip.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------------------------ slash commands

    private void updateSlash(String text) {
        slashList.removeAllViews();
        if (!text.startsWith("/") || text.contains(" ")) {
            slashRow.setVisibility(View.GONE);
            return;
        }
        String q = text.substring(1).toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>(Arrays.asList(COMMANDS));
        for (Skill s : Store.skills()) if (s.enabled) names.add(s.name);
        int shown = 0;
        for (String n : names) {
            if (!n.startsWith(q)) continue;
            TextView t = new TextView(this);
            t.setText("/" + n);
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
            t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            t.setTextColor(getColor(R.color.k_text));
            t.setBackgroundResource(R.drawable.bg_chip);
            int h = U.dp(this, 10), v = U.dp(this, 5);
            t.setPadding(h, v, h, v);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(U.dp(this, 6));
            t.setLayoutParams(lp);
            t.setOnClickListener(x -> {
                input.setText("/" + n + " ");
                input.setSelection(input.length());
            });
            slashList.addView(t);
            shown++;
        }
        slashRow.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
    }

    /** Returns true when the text was a built-in command (skills fall through to the normal send path). */
    private boolean handleSlash(String raw) {
        String[] parts = raw.substring(1).trim().split("\\s+", 2);
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].trim() : "";
        switch (cmd) {
            case "new": openChatSafely(newChat()); return true;
            case "clear": clearChat(); return true;
            case "model":
                if (arg.isEmpty()) {
                    pickModel();
                } else {
                    chat.model = arg;
                    persist();
                    refreshHeader();
                    U.toast(this, "Model set to " + arg);
                }
                return true;
            case "system": editSystem(arg.isEmpty() ? null : arg); return true;
            case "rename": renameChat(arg.isEmpty() ? null : arg); return true;
            case "pin": togglePin(); return true;
            case "archive": archiveChat(); return true;
            case "export": exportChat(); return true;
            case "share": U.shareText(this, chat.title, chat.toMarkdown()); return true;
            case "history": startActivity(new Intent(this, HistoryActivity.class)); return true;
            case "skills": startActivity(new Intent(this, SkillsActivity.class)); return true;
            case "tools": startActivity(new Intent(this, ToolsActivity.class)); return true;
            case "providers": startActivity(new Intent(this, ProvidersActivity.class)); return true;
            case "settings": startActivity(new Intent(this, SettingsActivity.class)); return true;
            case "logs": startActivity(new Intent(this, LogsActivity.class)); return true;
            case "help": Dialogs.markdown(this, "Help", HELP); return true;
            default: return false;
        }
    }
}
