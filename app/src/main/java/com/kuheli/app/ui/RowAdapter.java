package com.kuheli.app.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.kuheli.app.R;

import java.util.ArrayList;
import java.util.List;

/** One list-row adapter shared by History, Providers, Skills and Tools. */
public class RowAdapter extends RecyclerView.Adapter<RowAdapter.VH> {

    public static class Row {
        public String title, subtitle, trailing;
        public boolean showSwitch, checked;
        public Object tag;

        public Row(String title, String subtitle, String trailing, Object tag) {
            this.title = title;
            this.subtitle = subtitle;
            this.trailing = trailing;
            this.tag = tag;
        }
    }

    public interface Listener {
        default void onClick(Row row) { }
        default void onLong(View anchor, Row row) { }
        default void onSwitch(Row row, boolean on) { }
    }

    private final List<Row> rows = new ArrayList<>();
    private final Listener listener;

    public RowAdapter(Listener listener) {
        this.listener = listener;
    }

    public void set(List<Row> data) {
        rows.clear();
        rows.addAll(data);
        notifyDataSetChanged();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView title, sub, trailing;
        final CompoundButton sw;

        VH(View v) {
            super(v);
            title = v.findViewById(R.id.row_title);
            sub = v.findViewById(R.id.row_sub);
            trailing = v.findViewById(R.id.row_trailing);
            sw = v.findViewById(R.id.row_switch);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final Row row = rows.get(position);
        h.title.setText(row.title);
        h.sub.setText(row.subtitle);
        h.sub.setVisibility(row.subtitle == null || row.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        h.trailing.setText(row.trailing);
        h.trailing.setVisibility(row.trailing == null || row.trailing.isEmpty() ? View.GONE : View.VISIBLE);
        h.sw.setOnCheckedChangeListener(null);
        h.sw.setChecked(row.checked);
        h.sw.setVisibility(row.showSwitch ? View.VISIBLE : View.GONE);
        h.sw.setOnCheckedChangeListener((b, on) -> {
            row.checked = on;
            listener.onSwitch(row, on);
        });
        h.itemView.setOnClickListener(v -> listener.onClick(row));
        h.itemView.setOnLongClickListener(v -> {
            listener.onLong(v, row);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }
}
