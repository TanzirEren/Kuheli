package com.kuheli.app.data;

import com.kuheli.app.util.U;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class Chat {
    public String id = UUID.randomUUID().toString();
    public String title = "New chat";
    public String providerId = "";
    public String model = "";      // empty means "use the provider's default model"
    public String system = "";     // extra system prompt for this chat only
    public long created = System.currentTimeMillis();
    public long updated = created;
    public boolean pinned;
    public boolean archived;
    public final List<Msg> msgs = new ArrayList<>();

    public String preview() {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            String c = msgs.get(i).content;
            if (c != null && !c.trim().isEmpty()) return U.oneLine(c, 140);
        }
        return "Empty";
    }

    public int approxTokens() {
        int total = 0;
        for (Msg m : msgs) total += U.tokens(m.content);
        return total;
    }

    public boolean matches(String query) {
        if (query == null || query.trim().isEmpty()) return true;
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (title.toLowerCase(Locale.ROOT).contains(q)) return true;
        for (Msg m : msgs) {
            if (m.content != null && m.content.toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("provider", providerId);
        o.put("model", model);
        o.put("system", system);
        o.put("created", created);
        o.put("updated", updated);
        o.put("pinned", pinned);
        o.put("archived", archived);
        JSONArray arr = new JSONArray();
        for (Msg m : msgs) arr.put(m.toJson());
        o.put("msgs", arr);
        return o;
    }

    public static Chat fromJson(JSONObject o) {
        Chat c = new Chat();
        c.id = o.optString("id", c.id);
        c.title = o.optString("title", "New chat");
        c.providerId = o.optString("provider", "");
        c.model = o.optString("model", "");
        c.system = o.optString("system", "");
        c.created = o.optLong("created", c.created);
        c.updated = o.optLong("updated", c.created);
        c.pinned = o.optBoolean("pinned");
        c.archived = o.optBoolean("archived");
        JSONArray arr = o.optJSONArray("msgs");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m != null) c.msgs.add(Msg.fromJson(m));
            }
        }
        return c;
    }

    /** Markdown transcript used by Export and Share. */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        sb.append("_").append(U.when(created)).append("_\n\n");
        for (Msg m : msgs) {
            if (m.content == null || m.content.trim().isEmpty()) continue;
            sb.append(m.isUser() ? "## You\n\n" : "## Kuheli\n\n").append(m.content.trim()).append("\n\n");
        }
        return sb.toString();
    }
}
