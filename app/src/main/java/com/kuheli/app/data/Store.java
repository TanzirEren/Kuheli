package com.kuheli.app.data;

import android.content.Context;

import com.kuheli.app.util.AppLog;
import com.kuheli.app.util.Files;
import com.kuheli.app.util.Prefs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** All persistence: chats, providers and skills live as small files in the app's private storage. */
public final class Store {
    private static File root;
    private static File chatDir;
    private static File skillDir;
    private static final Map<String, Chat> CHATS = new LinkedHashMap<>();
    private static boolean chatsLoaded;
    private static List<Provider> providerCache;

    private Store() {}

    public static synchronized void init(Context c) {
        root = c.getFilesDir();
        chatDir = new File(root, "chats");
        skillDir = new File(root, "skills");
        chatDir.mkdirs();
        skillDir.mkdirs();
    }

    // ------------------------------------------------------------------ chats

    private static void loadChats() {
        if (chatsLoaded) return;
        File[] files = chatDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.getName().endsWith(".json")) continue;
                try {
                    String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    Chat c = Chat.fromJson(new JSONObject(text));
                    CHATS.put(c.id, c);
                } catch (IOException | JSONException e) {
                    AppLog.e("store", "Skipped unreadable chat file " + f.getName());
                }
            }
        }
        chatsLoaded = true;
    }

    /** Pinned first, then most recently updated. */
    public static synchronized List<Chat> chats() {
        loadChats();
        List<Chat> l = new ArrayList<>(CHATS.values());
        Collections.sort(l, (a, b) -> {
            if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
            return Long.compare(b.updated, a.updated);
        });
        return l;
    }

    public static synchronized Chat chat(String id) {
        loadChats();
        return id == null ? null : CHATS.get(id);
    }

    public static synchronized void save(Chat c) {
        loadChats();
        c.updated = System.currentTimeMillis();
        CHATS.put(c.id, c);
        try {
            java.nio.file.Files.write(new File(chatDir, c.id + ".json").toPath(),
                    c.toJson().toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            AppLog.e("store", "Could not save chat: " + e.getMessage());
        }
    }

    public static synchronized void deleteChat(String id) {
        loadChats();
        CHATS.remove(id);
        new File(chatDir, id + ".json").delete();
    }

    public static synchronized void deleteAllChats() {
        loadChats();
        for (String id : new ArrayList<>(CHATS.keySet())) deleteChat(id);
    }

    // ------------------------------------------------------------------ providers

    public static synchronized List<Provider> providers() {
        if (providerCache == null) {
            providerCache = new ArrayList<>();
            File f = new File(root, "providers.json");
            if (f.exists()) {
                try {
                    JSONArray arr = new JSONArray(new String(java.nio.file.Files.readAllBytes(f.toPath()),
                            StandardCharsets.UTF_8));
                    for (int i = 0; i < arr.length(); i++) providerCache.add(Provider.fromJson(arr.getJSONObject(i)));
                } catch (IOException | JSONException e) {
                    AppLog.e("store", "Could not read providers: " + e.getMessage());
                }
            } else {
                providerCache.addAll(Provider.presets());   // first run: presets without keys
                saveProviders(providerCache);
            }
        }
        return new ArrayList<>(providerCache);
    }

    public static synchronized void saveProviders(List<Provider> list) {
        providerCache = new ArrayList<>(list);
        try {
            JSONArray arr = new JSONArray();
            for (Provider p : list) arr.put(p.toJson(true));
            java.nio.file.Files.write(new File(root, "providers.json").toPath(),
                    arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            AppLog.e("store", "Could not save providers: " + e.getMessage());
        }
    }

    public static synchronized Provider provider(String id) {
        for (Provider p : providers()) if (p.id.equals(id)) return p;
        return null;
    }

    /** The provider a new chat starts with: the chosen default, else the first one that has a key. */
    public static synchronized Provider activeProvider() {
        Provider chosen = provider(Prefs.s("active_provider", ""));
        if (chosen != null && chosen.usable()) return chosen;
        for (Provider p : providers()) if (p.usable() && !p.apiKey.isEmpty()) return p;
        for (Provider p : providers()) if (p.usable()) return p;
        return null;
    }

    // ------------------------------------------------------------------ skills

    private static Set<String> disabledSkills() {
        Set<String> s = new HashSet<>();
        for (String part : Prefs.s("skills_disabled", "").split(",")) if (!part.trim().isEmpty()) s.add(part.trim());
        return s;
    }

    public static synchronized List<Skill> skills() {
        List<Skill> out = new ArrayList<>();
        Set<String> off = disabledSkills();
        File[] dirs = skillDir.listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                File f = new File(d, "SKILL.md");
                if (!f.isFile()) continue;
                try {
                    Skill s = Skill.parse(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                    if (s.name.isEmpty()) s.name = Skill.slug(d.getName());
                    s.enabled = !off.contains(s.name);
                    out.add(s);
                } catch (IOException e) {
                    AppLog.e("store", "Could not read skill " + d.getName());
                }
            }
        }
        Collections.sort(out, (a, b) -> a.name.compareTo(b.name));
        return out;
    }

    public static synchronized Skill skill(String name) {
        for (Skill s : skills()) if (s.name.equals(name)) return s;
        return null;
    }

    /** Saves the raw SKILL.md text. If oldName differs, the old skill is removed (rename). */
    public static synchronized String saveSkill(String oldName, String raw) throws IOException {
        Skill s = Skill.parse(raw);
        if (s.name.isEmpty()) throw new IOException("The skill needs a name in its front matter, for example: name: my-skill");
        if (oldName != null && !oldName.equals(s.name)) deleteSkill(oldName);
        File dir = new File(skillDir, s.name);
        dir.mkdirs();
        java.nio.file.Files.write(new File(dir, "SKILL.md").toPath(), raw.getBytes(StandardCharsets.UTF_8));
        return s.name;
    }

    public static synchronized void deleteSkill(String name) {
        Files.deleteRecursive(new File(skillDir, name));
    }

    public static synchronized void setSkillEnabled(String name, boolean enabled) {
        Set<String> off = disabledSkills();
        if (enabled) off.remove(name); else off.add(name);
        Prefs.put("skills_disabled", String.join(",", off));
    }

    /** Copies the bundled example skills on the very first run. */
    public static synchronized void seedSkills(Context c) {
        if (Prefs.b("skills_seeded", false)) return;
        try {
            String[] names = c.getAssets().list("skills");
            if (names != null) {
                for (String n : names) {
                    File dir = new File(skillDir, n);
                    if (!dir.exists()) Files.copyAssets(c, "skills/" + n, dir);
                }
            }
            Prefs.putB("skills_seeded", true);
        } catch (IOException e) {
            AppLog.e("store", "Could not copy bundled skills: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ backup

    /** Chats, skills and provider settings. API keys are deliberately NOT included. */
    public static synchronized String exportAll() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("app", "kuheli");
        o.put("version", 1);
        JSONArray chats = new JSONArray();
        for (Chat c : chats()) chats.put(c.toJson());
        o.put("chats", chats);
        JSONArray skills = new JSONArray();
        for (Skill s : skills()) skills.put(new JSONObject().put("name", s.name).put("raw", s.raw));
        o.put("skills", skills);
        JSONArray provs = new JSONArray();
        for (Provider p : providers()) provs.put(p.toJson(false));
        o.put("providers", provs);
        return o.toString(1);
    }

    /** Merges a backup into the current data and returns how many chats were added or replaced. */
    public static synchronized int importAll(String json) throws JSONException, IOException {
        JSONObject o = new JSONObject(json);
        if (!"kuheli".equals(o.optString("app"))) throw new JSONException("This is not a Kuheli backup");
        int n = 0;
        JSONArray chats = o.optJSONArray("chats");
        if (chats != null) {
            for (int i = 0; i < chats.length(); i++) {
                save(Chat.fromJson(chats.getJSONObject(i)));
                n++;
            }
        }
        JSONArray skills = o.optJSONArray("skills");
        if (skills != null) {
            for (int i = 0; i < skills.length(); i++) {
                JSONObject s = skills.getJSONObject(i);
                saveSkill(null, s.optString("raw"));
            }
        }
        JSONArray provs = o.optJSONArray("providers");
        if (provs != null) {
            List<Provider> current = providers();
            for (int i = 0; i < provs.length(); i++) {
                Provider incoming = Provider.fromJson(provs.getJSONObject(i));
                boolean found = false;
                for (Provider p : current) {
                    if (p.id.equals(incoming.id)) {
                        found = true;   // keep the local key and settings
                        break;
                    }
                }
                if (!found) current.add(incoming);
            }
            saveProviders(current);
        }
        return n;
    }

    public static synchronized void wipeEverything() {
        deleteAllChats();
        Files.deleteRecursive(skillDir);
        skillDir.mkdirs();
        new File(root, "providers.json").delete();
        providerCache = null;
        Prefs.putB("skills_seeded", false);
        Prefs.put("active_provider", "");
    }
}
