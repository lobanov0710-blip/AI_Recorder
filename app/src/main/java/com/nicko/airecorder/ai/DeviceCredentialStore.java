package com.nicko.airecorder.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores a paired device credential encrypted by an AES-GCM Android Keystore key.
 *
 * The Keystore key is non-exportable. Ciphertext is stored in app-private
 * SharedPreferences, which are excluded from backup by the existing manifest
 * backup rules. A deleted, restored or corrupted key MUST NOT silently bypass
 * authentication. This is storage only: no pairing UI or HTTP calls.
 */
public final class DeviceCredentialStore implements DeviceAccessTokenSource {
    private static final String PREFS = "ai_recorder_device_credential_v1";
    private static final String ENTRY = "encrypted_token_v1";
    private static final String ALIAS = "ai_recorder_device_credential_aes_v1";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String VERSION = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Pattern TOKEN = Pattern.compile("ar1_[A-Za-z0-9_-]{43}");

    private final Context context;
    private final SharedPreferences preferences;

    public DeviceCredentialStore(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context required");
        }
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** For trusted local provisioning only, after authenticated HTTPS pairing. */
    public synchronized void saveToken(String token) throws IOException {
        if (!validToken(token)) {
            throw new IllegalArgumentException("Invalid device credential format");
        }
        byte[] plaintext = token.getBytes(StandardCharsets.US_ASCII);
        try {
            SecretKey key = keyForEncrypt();
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            cipher.updateAAD(aad());
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_BYTES) {
                throw new GeneralSecurityException("Unsupported GCM initialization vector");
            }
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            String encoded = VERSION + Base64.encodeToString(payload, Base64.NO_WRAP);
            Arrays.fill(encrypted, (byte) 0);
            Arrays.fill(payload, (byte) 0);
            if (!preferences.edit().putString(ENTRY, encoded).commit()) {
                throw new IOException("Credential storage unavailable");
            }
        } catch (GeneralSecurityException error) {
            throw new IOException("Credential encryption unavailable");
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** Returns null only when no credential has ever been saved. */
    @Override
    public synchronized String readToken() throws IOException {
        String encoded = preferences.getString(ENTRY, null);
        if (encoded == null) {
            return null;
        }
        if (!encoded.startsWith(VERSION) || encoded.length() > 256) {
            throw new IOException("Credential data invalid");
        }
        byte[] payload;
        try {
            payload = Base64.decode(encoded.substring(VERSION.length()), Base64.NO_WRAP);
        } catch (IllegalArgumentException error) {
            throw new IOException("Credential data invalid");
        }
        try {
            if (payload.length < IV_BYTES + 16 || payload.length > 192) {
                throw new GeneralSecurityException("Credential ciphertext invalid");
            }
            SecretKey key = existingKey();
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOf(payload, IV_BYTES)));
            cipher.updateAAD(aad());
            byte[] plaintext = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
            try {
                String token = new String(plaintext, StandardCharsets.US_ASCII);
                if (!validToken(token)) {
                    throw new GeneralSecurityException("Credential format invalid");
                }
                return token;
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (GeneralSecurityException error) {
            throw new IOException("Credential decryption unavailable");
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    /** Local logout/revocation cleanup. Server-side revocation must be separate. */
    public synchronized void clearLocalToken() throws IOException {
        if (!preferences.edit().remove(ENTRY).commit()) {
            throw new IOException("Credential deletion unavailable");
        }
        try {
            KeyStore store = KeyStore.getInstance(KEYSTORE);
            store.load(null);
            if (store.containsAlias(ALIAS)) {
                store.deleteEntry(ALIAS);
            }
        } catch (GeneralSecurityException error) {
            throw new IOException("Credential key cleanup unavailable");
        }
    }

    private SecretKey keyForEncrypt() throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        Key existing = store.getKey(ALIAS, null);
        if (existing != null) {
            if (!(existing instanceof SecretKey)) {
                throw new GeneralSecurityException("Wrong key type");
            }
            return (SecretKey) existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false) // WorkManager background upload
                .build());
        return generator.generateKey();
    }

    private SecretKey existingKey() throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        Key key = store.getKey(ALIAS, null);
        if (!(key instanceof SecretKey)) {
            throw new GeneralSecurityException("Credential key missing");
        }
        return (SecretKey) key;
    }

    private byte[] aad() {
        return ("AIRecorder/device-credential/v1/" + context.getPackageName())
                .getBytes(StandardCharsets.UTF_8);
    }

    static boolean validToken(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }
}
