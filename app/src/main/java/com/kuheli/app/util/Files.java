package com.kuheli.app.util;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** File and asset helpers. */
public final class Files {
    private Files() {}

    /** Reads at most maxBytes from a content Uri. */
    public static byte[] readBytes(ContentResolver cr, Uri uri, int maxBytes) throws IOException {
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open the file");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < maxBytes) {
                out.write(buf, 0, Math.min(n, maxBytes - out.size()));
            }
            return out.toByteArray();
        }
    }

    public static String readText(ContentResolver cr, Uri uri, int maxBytes) throws IOException {
        return new String(readBytes(cr, uri, maxBytes), StandardCharsets.UTF_8);
    }

    public static void writeText(ContentResolver cr, Uri uri, String text) throws IOException {
        try (OutputStream out = cr.openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("Cannot write the file");
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static boolean looksBinary(byte[] data) {
        int n = Math.min(data.length, 2048);
        for (int i = 0; i < n; i++) {
            if (data[i] == 0) return true;
        }
        return false;
    }

    public static String readAsset(Context c, String path) throws IOException {
        try (InputStream in = c.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    /** Copies an asset directory (or single file) to dest, overwriting. */
    public static void copyAssets(Context c, String assetPath, File dest) throws IOException {
        AssetManager am = c.getAssets();
        String[] children = am.list(assetPath);
        if (children != null && children.length > 0) {
            if (!dest.exists() && !dest.mkdirs()) throw new IOException("Cannot create " + dest);
            for (String child : children) copyAssets(c, assetPath + "/" + child, new File(dest, child));
        } else {
            File parent = dest.getParentFile();
            if (parent != null) parent.mkdirs();
            try (InputStream in = am.open(assetPath); FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
    }

    public static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }
}
