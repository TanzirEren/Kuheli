package com.kuheli.app.core;

import android.content.Context;
import android.net.Uri;

import com.kuheli.app.util.AppLog;
import com.kuheli.app.util.Files;
import com.kuheli.app.util.Prefs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Starts the bundled Node.js runtime (if this build has it) and runs bridge.js, which launches
 * REA's MCP server and exposes it on a local HTTP port.
 */
public final class NodeService {
    private static boolean started;

    private NodeService() {}

    public static synchronized boolean isStarted() {
        return started;
    }

    public static File bridgeFile(Context c) {
        return new File(new File(c.getFilesDir(), "nodejs"), "bridge.js");
    }

    /** Called on app start; does nothing when the user turned auto-start off. */
    public static synchronized void ensureStarted(Context c) {
        if (!started && Prefs.b("auto_start_node", true)) start(c);
    }

    /** Starts Node once per process. Returns a short human-readable status. */
    public static synchronized String start(Context c) {
        if (started) return "The embedded runtime is already running.";
        if (!Native.hasNode()) {
            return "This build has no embedded Node.js runtime. Run the bridge in Termux instead "
                    + "(see the steps below).";
        }
        try {
            File dir = new File(c.getFilesDir(), "nodejs");
            Files.copyAssets(c, "nodejs-project", dir);
            writeConfig(c);
            final String[] args = {"node", bridgeFile(c).getAbsolutePath(), new File(dir, "config.json").getAbsolutePath()};
            Thread t = new Thread(null, () -> {
                int code = Native.startNode(args);
                AppLog.i("node", "Node exited with code " + code);
            }, "kuheli-node", 4L * 1024 * 1024);
            t.start();
            started = true;
            AppLog.i("node", "Embedded Node started");
            return "Starting the embedded runtime...";
        } catch (IOException | JSONException e) {
            AppLog.e("node", "Could not start Node: " + e.getMessage());
            return "Could not start: " + e.getMessage();
        }
    }

    /** Writes config.json from the settings. The bridge re-reads it on /restart. */
    public static void writeConfig(Context c) throws IOException, JSONException {
        File dir = new File(c.getFilesDir(), "nodejs");
        dir.mkdirs();
        int port = Uri.parse(Prefs.s("bridge_url", "http://127.0.0.1:8765")).getPort();
        JSONObject cfg = new JSONObject();
        cfg.put("port", port > 0 ? port : 8765);
        cfg.put("command", Prefs.s("node_cmd", "npx").trim());
        JSONArray args = new JSONArray();
        for (String a : Prefs.s("node_args", "-y rea-agents@latest mcp").trim().split("\\s+")) {
            if (!a.isEmpty()) args.put(a);
        }
        cfg.put("args", args);
        JSONObject env = new JSONObject();
        for (String line : Prefs.s("node_env", "").split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) env.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        cfg.put("env", env);
        java.nio.file.Files.write(new File(dir, "config.json").toPath(),
                cfg.toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
