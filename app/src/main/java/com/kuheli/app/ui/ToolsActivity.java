package com.kuheli.app.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.kuheli.app.R;
import com.kuheli.app.core.ChatEngine;
import com.kuheli.app.core.Native;
import com.kuheli.app.core.NodeService;
import com.kuheli.app.util.Files;
import com.kuheli.app.util.Prefs;
import com.kuheli.app.util.U;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Status of the REA bridge, the tools REA offers, and how to run the bridge somewhere else. */
public class ToolsActivity extends BaseActivity {
    private static final String HELP =
            "REA needs Node.js 22.23.4 or newer (24.22.1+ or 26.12.1+ also work) and a native analysis "
                    + "tool such as Ghidra, Hopper, IDA, Kuna, Rizin or objdump for binaries.\n\n"
                    + "If this build cannot start child processes itself, run the bridge in Termux:\n"
                    + "1. Tap \"Save bridge.js\" and store it in Downloads.\n"
                    + "2. In Termux run: pkg install nodejs, then node -v to check the version.\n"
                    + "3. Run: node ~/storage/downloads/bridge.js (after termux-setup-storage).\n"
                    + "4. Keep Termux open. Kuheli reaches it at the bridge address in Settings.";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView state;
    private EditText filter;
    private RowAdapter adapter;
    private final List<RowAdapter.Row> allTools = new ArrayList<>();
    private int pollsLeft;
    private boolean destroyed;

    private final ActivityResultLauncher<String> saveBridge =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/javascript"), uri -> {
                if (uri == null) return;
                try {
                    Files.writeText(getContentResolver(), uri, Files.readAsset(this, "nodejs-project/bridge.js"));
                    U.toast(this, "Saved bridge.js");
                } catch (IOException e) {
                    U.toast(this, "Could not save: " + e.getMessage());
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tools);
        MaterialToolbar tb = findViewById(R.id.toolbar);
        setupToolbar(tb, true);
        tb.setTitle("REA tools");
        tb.setSubtitle("Reverse engineering over MCP");

        state = findViewById(R.id.tools_state);
        filter = findViewById(R.id.tools_search);
        ((TextView) findViewById(R.id.tools_help)).setText(HELP);

        findViewById(R.id.btn_start).setOnClickListener(v -> startBridge());
        findViewById(R.id.btn_stop).setOnClickListener(v -> stopBridge());
        findViewById(R.id.btn_refresh).setOnClickListener(v -> refresh());
        findViewById(R.id.btn_save_bridge).setOnClickListener(v -> saveBridge.launch("bridge.js"));

        adapter = new RowAdapter(new RowAdapter.Listener() { });
        RecyclerView rv = findViewById(R.id.recycler);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
        Dialogs.watch(filter, q -> applyFilter());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private String url() {
        return Prefs.s("bridge_url", "http://127.0.0.1:8765");
    }

    private void refresh() {
        ChatEngine.callAsync("bridge_health", url(), null, false, json -> {
            if (destroyed) return;
            boolean ready = false;
            try {
                JSONObject o = new JSONObject(json);
                String st = o.optString("state", "unknown");
                ready = "ready".equals(st);
                StringBuilder sb = new StringBuilder();
                sb.append("Status: ").append(st).append("\n").append(o.optString("message"));
                if (o.has("node")) sb.append("\nNode ").append(o.optString("node"));
                sb.append("\nAddress: ").append(url());
                sb.append("\nEmbedded runtime in this build: ").append(Native.hasNode() ? "yes" : "no");
                JSONArray tail = o.optJSONArray("tail");
                if (tail != null && !ready && tail.length() > 0) {
                    sb.append("\n\nLast output:");
                    for (int i = 0; i < tail.length(); i++) sb.append("\n  ").append(U.oneLine(tail.optString(i), 140));
                }
                state.setText(sb);
                if (("starting".equals(st)) && pollsLeft > 0) schedulePoll();
            } catch (JSONException e) {
                state.setText("Could not read the bridge status.");
            }
            if (ready) loadTools(); else showTools(new ArrayList<>());
        });
    }

    private void schedulePoll() {
        pollsLeft--;
        ui.postDelayed(this::refresh, 2500);
    }

    private void startBridge() {
        try {
            NodeService.writeConfig(this);
        } catch (IOException | JSONException e) {
            U.toast(this, "Could not write the bridge settings: " + e.getMessage());
            return;
        }
        pollsLeft = 60;
        if (!NodeService.isStarted() && Native.hasNode()) {
            state.setText(NodeService.start(this));
            ui.postDelayed(this::refresh, 1500);
        } else {
            ChatEngine.callAsync("bridge_restart", url(), null, false, json -> refresh());
            state.setText("Restarting the bridge...");
        }
    }

    private void stopBridge() {
        ChatEngine.callAsync("bridge_stop", url(), null, false, json -> refresh());
    }

    private void loadTools() {
        ChatEngine.callAsync("mcp_tools", url(), null, false, json -> {
            if (destroyed) return;
            List<RowAdapter.Row> rows = new ArrayList<>();
            try {
                JSONObject o = new JSONObject(json);
                JSONArray arr = o.optJSONArray("tools");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject t = arr.getJSONObject(i);
                        rows.add(new RowAdapter.Row(t.optString("name"), t.optString("description"), "", null));
                    }
                }
            } catch (JSONException ignored) {
                // show an empty list
            }
            showTools(rows);
        });
    }

    private void showTools(List<RowAdapter.Row> rows) {
        allTools.clear();
        allTools.addAll(rows);
        applyFilter();
    }

    private void applyFilter() {
        String q = filter.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<RowAdapter.Row> shown = new ArrayList<>();
        for (RowAdapter.Row r : allTools) {
            if (q.isEmpty() || r.title.toLowerCase(Locale.ROOT).contains(q)
                    || r.subtitle.toLowerCase(Locale.ROOT).contains(q)) shown.add(r);
        }
        adapter.set(shown);
        TextView count = findViewById(R.id.tools_count);
        count.setText(allTools.isEmpty() ? "No tools loaded" : shown.size() + " of " + allTools.size() + " tools");
    }
}
