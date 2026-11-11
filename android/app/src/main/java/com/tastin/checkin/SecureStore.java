package com.tastin.checkin;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 用 Android Keystore 里的 AES 密钥加密敏感字段（token）。
 *
 * 密钥由系统 TEE 保管，即使 SharedPreferences 文件被拷走也解不开。
 * 加密结果格式：Base64(IV(12 字节) + 密文)。
 *
 * 兼容旧版本：老数据是纯 Base64、没有 "v1:" 前缀，读取时按旧方式解，
 * 下次保存时自动升级为加密格式。
 */
final class SecureStore {

    private static final String TAG = "TastinSecure";
    private static final String KEY_ALIAS = "tastin_token_key";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final String PREFIX = "v1:";

    private SecureStore() {}

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    /** 加密；失败时退回 Base64（至少不丢数据，不至于让用户重新扫码） */
    static String encrypt(String plain) {
        if (plain == null) return "";
        if (plain.isEmpty()) return "";
        try {
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] body = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(body, 0, all, iv.length, body.length);
            return PREFIX + Base64.encodeToString(all, Base64.NO_WRAP);
        } catch (Throwable t) {
            Log.w(TAG, "Keystore 加密失败，退回 Base64", t);
            return Base64.encodeToString(plain.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        }
    }

    /** 解密；兼容旧版本的纯 Base64 数据 */
    static String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return "";

        if (!stored.startsWith(PREFIX)) {
            // 旧格式：纯 Base64
            try {
                return new String(Base64.decode(stored, Base64.NO_WRAP), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "";
            }
        }

        try {
            byte[] all = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
            if (all.length <= IV_LEN) return "";
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, all, 0, IV_LEN));
            byte[] plain = c.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            // 密钥被清除（如用户清应用数据后系统轮换）等情况，只能请用户重新扫码
            Log.w(TAG, "Keystore 解密失败", t);
            return "";
        }
    }
}
