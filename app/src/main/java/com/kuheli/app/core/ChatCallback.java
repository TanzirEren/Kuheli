package com.kuheli.app.core;

/** Implemented in Java, called from kuheli_core.py (Chaquopy) while a reply is being produced. */
public interface ChatCallback {
    void onToken(String text);

    void onToolCall(String name, String argsJson);

    void onToolResult(String name, String result);

    void onStatus(String message);

    void onDone(String resultJson);

    void onError(String message);

    /** Blocks until the user decides: 0 = deny, 1 = allow, 2 = allow for the rest of this reply. */
    int approve(String name, String argsJson);
}
