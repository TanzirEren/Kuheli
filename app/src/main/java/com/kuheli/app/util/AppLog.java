package com.kuheli.app.util;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small persistent log shown on the Logs page. Never records API keys or message text. */
public final class AppLog {
    private static final long MAX_BYTES = 256 * 1024;
    private static File file;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private AppLog() {}

    public static synchronized void init(Context c) {
        file = new File(c.getFilesDir(), "kuheli.log");
        if (file.exists() && file.length() > MAX_BYTES) {
            // keep the newest half
            try {
                byte[] all = Files.readAllBytes(file.toPath());
                int from = all.length / 2;
                Files.write(file.toPath(), java.util.Arrays.copyOfRange(all, from, all.length));
            } catch (IOException e) {
                file.delete();
            }
        }
        i("app", "started");
    }

    public static synchronized void i(String tag, String msg) {
        write("I", tag, msg);
    }

    public static synchronized void e(String tag, String msg) {
        write("E", tag, msg);
    }

    private static void write(String level, String tag, String msg) {
        if (file == null) return;
        try (FileWriter w = new FileWriter(file, true)) {
            w.write(FMT.format(new Date()) + " " + level + " " + tag + ": " + msg + "\n");
        } catch (IOException ignored) {
            // logging must never crash the app
        }
    }

    public static synchronized String dump() {
        if (file == null || !file.exists()) return "";
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Could not read the log: " + e.getMessage();
        }
    }

    public static synchronized void clear() {
        if (file != null) file.delete();
    }
}
