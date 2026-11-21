package com.kuheli.app.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.kuheli.app.R;
import com.kuheli.app.data.Msg;
import com.kuheli.app.util.Prefs;
import com.kuheli.app.util.U;

import java.util.List;

import io.noties.markwon.Markwon;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.VH> {
    /** Payload meaning "only the text/tool lines changed" (used while streaming). */
    public static final Object PAYLOAD_TEXT = new Object();

    public interface OnLong {
        void onLong(View anchor, int position);
    }

    private final List<Msg> items;
    private final Markwon markwon;
    private final OnLong onLong;

    public MessageAdapter(List<Msg> items, Markwon markwon, OnLong onLong) {
        this.items = items;
        this.markwon = markwon;
        this.onLong = onLong;
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView role, content, tools, meta;

        VH(View v) {
            super(v);
            role = v.findViewById(R.id.msg_role);
            content = v.findViewById(R.id.msg_content);
            tools = v.findViewById(R.id.msg_tools);
            meta = v.findViewById(R.id.msg_meta);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Msg m = items.get(position);
        boolean user = m.isUser();
        int side = U.dp(h.itemView.getContext(), 12);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) h.itemView.getLayoutParams();
        lp.setMargins(user ? U.dp(h.itemView.getContext(), 44) : side, U.dp(h.itemView.getContext(), 4), side,
                U.dp(h.itemView.getContext(), 4));
        h.itemView.setLayoutParams(lp);
        if (user) h.itemView.setBackgroundResource(R.drawable.bg_user_msg);
        else h.itemView.setBackground(null);

        h.role.setText(user ? "You" : "Kuheli");
        h.content.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Prefs.textSize());
        bindText(h, m);

        View.OnLongClickListener l = v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) onLong.onLong(h.itemView, pos);
            return true;
        };
        h.itemView.setOnLongClickListener(l);
        h.content.setOnLongClickListener(l);

        StringBuilder meta = new StringBuilder();
        if (Prefs.b("show_time", false)) meta.append(U.timeOnly(m.ts));
        if (!user && m.tokens > 0) {
            if (meta.length() > 0) meta.append("  ");
            meta.append("~").append(m.tokens).append(" tokens");
        }
        h.meta.setVisibility(meta.length() == 0 ? View.GONE : View.VISIBLE);
        h.meta.setText(meta);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position, @NonNull List<Object> payloads) {
        if (payloads.contains(PAYLOAD_TEXT)) {
            bindText(h, items.get(position));
        } else {
            super.onBindViewHolder(h, position, payloads);
        }
    }

    private void bindText(VH h, Msg m) {
        if (m.content == null || m.content.isEmpty()) {
            h.content.setText(m.isUser() ? "" : "...");
        } else if (m.isUser()) {
            h.content.setText(m.content);          // what you typed is shown as typed
        } else {
            markwon.setMarkdown(h.content, m.content);
        }
        if (m.tools.isEmpty()) {
            h.tools.setVisibility(View.GONE);
        } else {
            h.tools.setVisibility(View.VISIBLE);
            h.tools.setText(String.join("\n", m.tools));
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
}
