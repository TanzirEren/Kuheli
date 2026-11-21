# Keep the JNI entry points and the Python callback (called from Python through Chaquopy).
-keep class com.kuheli.app.core.Native { *; }
-keep interface com.kuheli.app.core.ChatCallback { *; }
-keep class com.kuheli.app.core.ChatEngine$* { *; }
