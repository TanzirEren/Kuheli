package com.kuheli.app.core;

/** JNI entry points implemented in app/src/main/cpp/native-lib.cpp. */
public final class Native {
    private static final boolean LOADED;

    static {
        boolean ok;
        try {
            System.loadLibrary("kuheli_native");
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        LOADED = ok;
    }

    private Native() {}

    public static boolean isLoaded() {
        return LOADED;
    }

    /** True when this APK was built with the embedded Node.js runtime. */
    public static boolean hasNode() {
        return LOADED && nodeAvailable();
    }

    public static String hash(byte[] data) {
        return LOADED ? sha256Bytes(data) : "";
    }

    public static native int startNode(String[] args);

    public static native boolean nodeAvailable();

    public static native String sha256(String path);

    public static native String sha256Bytes(byte[] data);
}
