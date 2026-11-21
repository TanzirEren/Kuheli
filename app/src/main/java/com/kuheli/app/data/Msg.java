package com.kuheli.app.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Msg {
    public String role;      // "user" or "assistant"
    public String content;
    public long ts;
    public int tokens;       // reply size, from the provider when it reports it, else estimated
    public final List<String> tools = new ArrayList<>();

    public Msg(String role, String content) {
        this.role = role;
        this.content = content;
        this.ts = System.currentTimeMillis();
    }

    public boolean isUser() {
        return "user".equals(role);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("r", role);
        o.put("c", content);
        o.put("t", ts);
        o.put("k", tokens);
        o.put("tools", new JSONArray(tools));
        return o;
    }

    public static Msg fromJson(JSONObject o) {
        Msg m = new Msg(o.optString("r", "user"), o.optString("c", ""));
        m.ts = o.optLong("t", System.currentTimeMillis());
        m.tokens = o.optInt("k", 0);
        JSONArray t = o.optJSONArray("tools");
        if (t != null) for (int i = 0; i < t.length(); i++) m.tools.add(t.optString(i));
        return m;
    }
}
