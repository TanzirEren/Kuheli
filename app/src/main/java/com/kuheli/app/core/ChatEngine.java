package com.kuheli.app.core;

import android.os.Handler;
import android.os.Looper;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.kuheli.app.data.Chat;
import com.kuheli.app.data.Msg;
import com.kuheli.app.data.Provider;
import com.kuheli.app.data.Skill;
import com.kuheli.app.data.Store;
import com.kuheli.app.util.AppLog;
import com.kuheli.app.util.Prefs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Runs kuheli_core.py (via Chaquopy) on a background thread and reports back through a Listener. */
public class ChatEngine {

    /** Called from the Python worker thread. Implementations must hop to the UI thread themselves. */
    public interface Listener {
        void onToken(String text);
        void onToolCall(String name, String args);
        void onToolResult(String name, String result);
        void onStatus(String message);
        void onDone(String resultJson);
        void onError(String message);
        int approve(String name, String args);
    }

    private static final ExecutorService POOL = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private volatile boolean running;

    /** Public and named so Chaquopy can always reach it by reflection. */
    public final class PyCallback implements ChatCallback {
        private final Listener l;

        public PyCallback(Listener l) { this.l = l; }

        @Override public void onToken(String t) { l.onToken(t); }
        @Override public void onToolCall(String n, String a) { l.onToolCall(n, a); }
        @Override public void onToolResult(String n, String r) { l.onToolResult(n, r); }
        @Override public void onStatus(String m) { AppLog.i("chat", m); l.onStatus(m); }
        @Override public void onDone(String j) { running = false; l.onDone(j); }
        @Override public void onError(String m) { running = false; AppLog.e("chat", m); l.onError(m); }
        @Override public int approve(String n, String a) { return l.approve(n, a); }
    }

    public boolean isRunning() {
        return running;
    }

    private static PyObject core() {
        return Python.getInstance().getModule("kuheli_core");
    }

    public void run(final JSONObject config, final Listener l) {
        running = true;
        POOL.execute(() -> {
            try {
                core().callAttr("run_chat", config.toString(), new PyCallback(l));
            } catch (Throwable t) {
                running = false;
                AppLog.e("chat", "Python failure: " + t);
                l.onError("Internal error: " + t.getMessage());
            } finally {
                running = false;
            }
        });
    }

    public void cancel() {
        POOL.execute(() -> {
            try {
                core().callAttr("cancel");
            } catch (Throwable t) {
                AppLog.e("chat", "cancel failed: " + t);
            }
        });
    }

    /** Runs a function of kuheli_core that takes and returns strings, then delivers the result on the UI thread. */
    public static void callAsync(final String function, final String arg1, final String arg2,
                                 final boolean hasArg2, final Consumer<String> done) {
        POOL.execute(() -> {
            String result;
            try {
                PyObject r = hasArg2 ? core().callAttr(function, arg1, arg2) : core().callAttr(function, arg1);
                result = r.toString();
            } catch (Throwable t) {
                AppLog.e("engine", function + " failed: " + t);
                result = "{\"ok\":false,\"reachable\":false,\"state\":\"error\",\"message\":"
                        + JSONObject.quote(String.valueOf(t.getMessage())) + ",\"models\":[],\"tools\":[]}";
            }
            final String out = result;
            MAIN.post(() -> done.accept(out));
        });
    }

    public static void async(final Callable<String> job, final Consumer<String> done) {
        POOL.execute(() -> {
            String r;
            try {
                r = job.call();
            } catch (Exception e) {
                r = "Error: " + e.getMessage();
            }
            final String out = r;
            MAIN.post(() -> done.accept(out));
        });
    }

    /** Builds the JSON that run_chat() expects from a chat and the current settings. */
    public static JSONObject buildConfig(Chat chat, Provider p, String slashSkill) throws JSONException {
        JSONObject cfg = new JSONObject();
        cfg.put("provider", p.toEngine(chat.model));

        JSONArray fallbacks = new JSONArray();
        if (Prefs.b("fallback", true)) {
            for (Provider other : Store.providers()) {
                if (!other.id.equals(p.id) && other.usable() && !other.apiKey.isEmpty()) {
                    fallbacks.put(other.toEngine(null));
                }
            }
        }
        cfg.put("fallbacks", fallbacks);

        JSONArray messages = new JSONArray();
        for (Msg m : chat.msgs) {
            if (m.content == null || m.content.trim().isEmpty()) continue;
            messages.put(new JSONObject().put("role", m.role).put("content", m.content));
        }
        cfg.put("messages", messages);

        String system = Prefs.s("system_prompt", "").trim();
        if (!chat.system.trim().isEmpty()) system = system.isEmpty() ? chat.system.trim() : system + "\n\n" + chat.system.trim();
        cfg.put("system", system);

        cfg.put("temperature", (double) Prefs.f("temperature", 0.7f));
        cfg.put("max_tokens", Prefs.i("max_tokens", 0));
        cfg.put("max_steps", Prefs.i("max_steps", 8));
        cfg.put("stream", Prefs.b("stream", true));
        cfg.put("tools_enabled", Prefs.b("tools_enabled", true));
        cfg.put("require_approval", Prefs.b("require_approval", true));
        cfg.put("bridge_url", Prefs.s("bridge_url", "http://127.0.0.1:8765"));
        cfg.put("tool_filter", Prefs.s("tool_filter", ""));
        cfg.put("max_tools", Prefs.i("max_tools", 64));
        cfg.put("tool_result_limit", Prefs.i("tool_result_limit", 20000));

        JSONArray skills = new JSONArray();
        for (Skill s : Store.skills()) skills.put(s.toEngine());
        cfg.put("skills", skills);
        if (slashSkill != null) cfg.put("slash_skill", slashSkill);
        return cfg;
    }
}
