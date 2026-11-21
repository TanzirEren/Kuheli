package com.kuheli.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.kuheli.app.R;
import com.kuheli.app.core.ChatEngine;
import com.kuheli.app.data.Provider;
import com.kuheli.app.data.Store;
import com.kuheli.app.util.Prefs;
import com.kuheli.app.util.U;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ProvidersActivity extends BaseActivity {
    private RowAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        MaterialToolbar tb = findViewById(R.id.toolbar);
        setupToolbar(tb, true);
        tb.setTitle("Providers");
        tb.setSubtitle("Any OpenAI-compatible endpoint");

        adapter = new RowAdapter(new RowAdapter.Listener() {
            @Override public void onClick(RowAdapter.Row row) { edit((Provider) row.tag, false); }
            @Override public void onSwitch(RowAdapter.Row row, boolean on) {
                Provider p = (Provider) row.tag;
                p.enabled = on;
                Store.saveProviders(Store.providers());
            }
        });
        RecyclerView rv = findViewById(R.id.recycler);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(v -> addProvider());
        TextView empty = findViewById(R.id.empty);
        empty.setVisibility(View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        Provider active = Store.activeProvider();
        List<RowAdapter.Row> rows = new ArrayList<>();
        for (Provider p : Store.providers()) {
            boolean isActive = active != null && active.id.equals(p.id);
            RowAdapter.Row r = new RowAdapter.Row(p.name + (isActive ? "  (default)" : ""),
                    p.baseUrl + "  /  " + (p.model.isEmpty() ? "no model" : p.model),
                    p.apiKey.isEmpty() ? "no key" : "key saved", p);
            r.showSwitch = true;
            r.checked = p.enabled;
            rows.add(r);
        }
        adapter.set(rows);
    }

    private void addProvider() {
        final List<Provider> presets = Provider.presets();
        List<String> names = new ArrayList<>();
        for (Provider p : presets) names.add(p.name);
        Dialogs.pick(this, "Add provider", names, chosen -> {
            for (Provider preset : presets) {
                if (preset.name.equals(chosen)) {
                    Provider copy = new Provider(preset.name, preset.baseUrl, preset.model);
                    edit(copy, true);
                    return;
                }
            }
        });
    }

    private static void readFields(Provider p, View v) {
        p.name = text(v, R.id.prov_name);
        p.baseUrl = text(v, R.id.prov_url);
        p.apiKey = text(v, R.id.prov_key);
        p.model = text(v, R.id.prov_model);
        p.headers = ((EditText) v.findViewById(R.id.prov_headers)).getText().toString().trim();
    }

    private static String text(View v, int id) {
        return ((EditText) v.findViewById(id)).getText().toString().trim();
    }

    private void edit(final Provider p, final boolean isNew) {
        final View v = LayoutInflater.from(this).inflate(R.layout.dialog_provider, null);
        ((EditText) v.findViewById(R.id.prov_name)).setText(p.name);
        ((EditText) v.findViewById(R.id.prov_url)).setText(p.baseUrl);
        ((EditText) v.findViewById(R.id.prov_key)).setText(p.apiKey);
        ((EditText) v.findViewById(R.id.prov_model)).setText(p.model);
        ((EditText) v.findViewById(R.id.prov_headers)).setText(p.headers);
        final TextView result = v.findViewById(R.id.prov_result);

        v.findViewById(R.id.btn_test).setOnClickListener(b -> {
            Provider tmp = new Provider();
            readFields(tmp, v);
            result.setText("Testing...");
            try {
                ChatEngine.callAsync("test_connection", tmp.toEngine(null).toString(), null, false, json -> {
                    try {
                        JSONObject o = new JSONObject(json);
                        result.setText((o.optBoolean("ok") ? "OK: " : "Failed: ") + o.optString("message"));
                    } catch (JSONException e) {
                        result.setText("Unexpected reply");
                    }
                });
            } catch (JSONException e) {
                result.setText("Check the fields and try again.");
            }
        });

        v.findViewById(R.id.btn_models).setOnClickListener(b -> {
            Provider tmp = new Provider();
            readFields(tmp, v);
            result.setText("Loading models...");
            try {
                ChatEngine.callAsync("list_models", tmp.toEngine(null).toString(), null, false, json -> {
                    try {
                        JSONObject o = new JSONObject(json);
                        JSONArray arr = o.optJSONArray("models");
                        List<String> ids = new ArrayList<>();
                        if (arr != null) for (int i = 0; i < arr.length(); i++) ids.add(arr.getString(i));
                        if (ids.isEmpty()) {
                            result.setText("No models: " + o.optString("message", "the list is empty"));
                            return;
                        }
                        result.setText(ids.size() + " models found.");
                        Dialogs.pick(this, "Choose a model", ids,
                                m -> ((EditText) v.findViewById(R.id.prov_model)).setText(m));
                    } catch (JSONException e) {
                        result.setText("Unexpected reply");
                    }
                });
            } catch (JSONException e) {
                result.setText("Check the fields and try again.");
            }
        });

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(isNew ? "Add provider" : "Edit provider")
                .setView(v)
                .setPositiveButton("Save", (d, w) -> {
                    readFields(p, v);
                    List<Provider> all = Store.providers();
                    boolean found = false;
                    for (Provider x : all) if (x.id.equals(p.id)) found = true;
                    if (!found) all.add(p);
                    Store.saveProviders(all);
                    if (Prefs.s("active_provider", "").isEmpty()) Prefs.put("active_provider", p.id);
                    reload();
                })
                .setNegativeButton("Cancel", null)
                .setNeutralButton(isNew ? "Make default" : "More", null);
        final AlertDialog dlg = builder.create();
        dlg.show();
        // The neutral button opens a small menu, so it must not auto-dismiss the dialog.
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(b -> {
            List<String> actions = new ArrayList<>();
            actions.add("Make default provider");
            if (!isNew) actions.add("Delete provider");
            Dialogs.pick(this, p.name, actions, choice -> {
                if (choice.startsWith("Make")) {
                    Prefs.put("active_provider", p.id);
                    U.toast(this, p.name + " is now the default");
                } else {
                    Dialogs.confirm(this, "Delete " + p.name + "?", "Its saved key is removed too.", () -> {
                        List<Provider> all = Store.providers();
                        all.removeIf(x -> x.id.equals(p.id));
                        Store.saveProviders(all);
                        dlg.dismiss();
                        reload();
                    });
                }
            });
        });
    }
}
