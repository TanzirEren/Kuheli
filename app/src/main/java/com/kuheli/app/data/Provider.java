package com.kuheli.app.data;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One OpenAI-compatible endpoint: Gemini, OpenRouter, Groq, OpenAI, OmniRoute or anything custom. */
public class Provider {
    public String id = UUID.randomUUID().toString();
    public String name = "";
    public String baseUrl = "";
    public String apiKey = "";      // plain text in memory only; encrypted on disk
    public String model = "";
    public String headers = "";     // optional "Name: value" lines
    public boolean enabled = true;

    public Provider() {}

    public Provider(String name, String baseUrl, String model) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    /** Starting points. Model names change often, so use "Fetch models" in the editor to pick a current one. */
    public static List<Provider> presets() {
        List<Provider> l = new ArrayList<>();
        l.add(new Provider("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"));
        l.add(new Provider("OpenRouter", "https://openrouter.ai/api/v1", "openrouter/auto"));
        l.add(new Provider("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"));
        l.add(new Provider("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"));
        l.add(new Provider("OmniRoute (local)", "http://localhost:20128/v1", "auto"));
        l.add(new Provider("Custom", "https://", ""));
        return l;
    }

    public boolean usable() {
        return enabled && !baseUrl.trim().isEmpty() && !model.trim().isEmpty();
    }

    /** The shape kuheli_core.py expects. */
    public JSONObject toEngine(String modelOverride) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("base_url", baseUrl.trim());
        o.put("api_key", apiKey);
        o.put("model", modelOverride != null && !modelOverride.trim().isEmpty() ? modelOverride.trim() : model.trim());
        o.put("headers", headers);
        return o;
    }

    public JSONObject toJson(boolean encryptKey) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("baseUrl", baseUrl);
        o.put("apiKey", encryptKey ? com.kuheli.app.core.CryptoBox.encrypt(apiKey) : "");
        o.put("model", model);
        o.put("headers", headers);
        o.put("enabled", enabled);
        return o;
    }

    public static Provider fromJson(JSONObject o) {
        Provider p = new Provider();
        p.id = o.optString("id", p.id);
        p.name = o.optString("name", "");
        p.baseUrl = o.optString("baseUrl", "");
        p.apiKey = com.kuheli.app.core.CryptoBox.decrypt(o.optString("apiKey", ""));
        p.model = o.optString("model", "");
        p.headers = o.optString("headers", "");
        p.enabled = o.optBoolean("enabled", true);
        return p;
    }
}
