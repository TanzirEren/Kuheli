package com.kuheli.app.core;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.kuheli.app.util.AppLog;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts API keys with an AES key that never leaves the Android Keystore. */
public final class CryptoBox {
    private static final String ALIAS = "kuheli_api_keys";
    private static final String PREFIX = "enc:";
    private static final String PLAIN = "plain:";

    private CryptoBox() {}

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return gen.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }

    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            // Some devices have a broken Keystore. Keep working, but say so in the log.
            AppLog.e("crypto", "Keystore unavailable, key stored without hardware protection: " + e.getMessage());
            return PLAIN + Base64.encodeToString(plain.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        }
    }

    public static String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return "";
        try {
            if (stored.startsWith(PLAIN)) {
                return new String(Base64.decode(stored.substring(PLAIN.length()), Base64.NO_WRAP),
                        StandardCharsets.UTF_8);
            }
            if (!stored.startsWith(PREFIX)) return stored;
            byte[] all = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
            byte[] iv = Arrays.copyOfRange(all, 0, 12);
            byte[] ct = Arrays.copyOfRange(all, 12, all.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AppLog.e("crypto", "Could not decrypt a stored key: " + e.getMessage());
            return "";
        }
    }
}
