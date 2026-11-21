package com.kuheli.app.ui;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kuheli.app.R;
import com.kuheli.app.util.U;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Small dialog builders used across the app. */
public final class Dialogs {
    private Dialogs() {}

    public static void watch(EditText et, Consumer<String> onChange) {
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { onChange.accept(e.toString()); }
        });
    }

    public static void input(Context c, String title, String hint, String initial, boolean multiline,
                             Consumer<String> ok) {
        final EditText et = new EditText(c);
        et.setHint(hint);
        et.setText(initial == null ? "" : initial);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        if (multiline) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            et.setMinLines(8);
            et.setGravity(Gravity.TOP | Gravity.START);
            et.setHorizontallyScrolling(false);
        } else {
            et.setSingleLine(true);
        }
        FrameLayout box = new FrameLayout(c);
        int pad = U.dp(c, 20);
        box.setPadding(pad, U.dp(c, 8), pad, 0);
        box.addView(et);
        new MaterialAlertDialogBuilder(c).setTitle(title).setView(box)
                .setPositiveButton("OK", (d, w) -> ok.accept(et.getText().toString()))
                .setNegativeButton("Cancel", null).show();
    }

    /** A list you can filter by typing. Returns the chosen item text. */
    public static void pick(Context c, String title, List<String> items, Consumer<String> onPick) {
        View v = LayoutInflater.from(c).inflate(R.layout.dialog_pick, null);
        EditText filter = v.findViewById(R.id.pick_filter);
        ListView list = v.findViewById(R.id.pick_list);
        final List<String> all = new ArrayList<>(items);
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(c, R.layout.item_pick, R.id.pick_text,
                new ArrayList<>(all));
        list.setAdapter(adapter);
        final AlertDialog dlg = new MaterialAlertDialogBuilder(c).setTitle(title).setView(v)
                .setNegativeButton("Cancel", null).create();
        list.setOnItemClickListener((p, view, pos, id) -> {
            String chosen = adapter.getItem(pos);
            dlg.dismiss();
            if (chosen != null) onPick.accept(chosen);
        });
        if (all.size() < 8) filter.setVisibility(View.GONE);
        watch(filter, q -> {
            String needle = q.trim().toLowerCase(Locale.ROOT);
            adapter.clear();
            for (String s : all) if (needle.isEmpty() || s.toLowerCase(Locale.ROOT).contains(needle)) adapter.add(s);
            adapter.notifyDataSetChanged();
        });
        dlg.show();
    }

    public static void confirm(Context c, String title, String message, Runnable yes) {
        new MaterialAlertDialogBuilder(c).setTitle(title).setMessage(message)
                .setPositiveButton("Yes", (d, w) -> yes.run())
                .setNegativeButton("Cancel", null).show();
    }

    public static void markdown(Context c, String title, String md) {
        TextView tv = new TextView(c);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        int pad = U.dp(c, 20);
        tv.setPadding(pad, U.dp(c, 8), pad, U.dp(c, 8));
        Md.get(c).setMarkdown(tv, md);
        ScrollView sv = new ScrollView(c);
        sv.addView(tv);
        new MaterialAlertDialogBuilder(c).setTitle(title).setView(sv).setPositiveButton("Close", null).show();
    }

    public static void selectable(Context c, String title, String text) {
        TextView tv = new TextView(c);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextIsSelectable(true);
        int pad = U.dp(c, 20);
        tv.setPadding(pad, U.dp(c, 8), pad, U.dp(c, 8));
        tv.setText(text);
        ScrollView sv = new ScrollView(c);
        sv.addView(tv);
        new MaterialAlertDialogBuilder(c).setTitle(title).setView(sv)
                .setPositiveButton("Close", null)
                .setNeutralButton("Copy all", (d, w) -> U.copy(c, "Kuheli", text)).show();
    }
}
