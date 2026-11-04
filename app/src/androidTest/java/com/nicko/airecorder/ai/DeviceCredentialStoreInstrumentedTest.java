package com.nicko.airecorder.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.security.KeyStore;

/** Uses separate aliases: never touches actual paired credentials. */
@RunWith(AndroidJUnit4.class)
public final class DeviceCredentialStoreInstrumentedTest {
    private static final String TEST_PREFS = "ai_recorder_test_credentials_stage46";
    private static final String TEST_ALIAS = "ai_recorder_test_key_stage46";
    private static final String TOKEN_A = "ar1_" + "A".repeat(43);
    private static final String TOKEN_B = "ar1_" + "B".repeat(43);

    private Context context;
    private DeviceCredentialStore store;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        store = new DeviceCredentialStore(context, TEST_PREFS, TEST_ALIAS);
        store.clearLocalToken();
    }

    @After
    public void tearDown() throws Exception {
        store.clearLocalToken();
    }

    @Test
    public void absentTokenIsNotFabricated() throws Exception {
        assertNull(store.readToken());
    }

    @Test
    public void encryptedTokenSurvivesNewStoreInstance() throws Exception {
        store.saveToken(TOKEN_A);
        DeviceCredentialStore another = new DeviceCredentialStore(context, TEST_PREFS, TEST_ALIAS);
        assertEquals(TOKEN_A, another.readToken());
        String ciphertext = context.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE)
                .getString("encrypted_token_v1", "");
        assertTrue(ciphertext.startsWith("v1:"));
        assertFalse(ciphertext.contains(TOKEN_A));
    }

    @Test
    public void overwriteUsesFreshGcmIv() throws Exception {
        store.saveToken(TOKEN_A);
        String first = context.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE)
                .getString("encrypted_token_v1", "");
        store.saveToken(TOKEN_B);
        String second = context.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE)
                .getString("encrypted_token_v1", "");
        assertFalse(first.equals(second));
        assertEquals(TOKEN_B, store.readToken());
    }

    @Test
    public void invalidTokenNotSaved() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> store.saveToken("not-a-token"));
        assertNull(store.readToken());
    }

    @Test
    public void modifiedCiphertextCannotDecrypt() throws Exception {
        store.saveToken(TOKEN_A);
        context.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE)
                .edit().putString("encrypted_token_v1", "v1:invalid-ciphertext").commit();
        IOException error = assertThrows(IOException.class, () -> store.readToken());
        assertFalse(error.getMessage().contains(TOKEN_A));
    }

    @Test
    public void missingKeystoreKeyFailsClosed() throws Exception {
        store.saveToken(TOKEN_A);
        KeyStore androidStore = KeyStore.getInstance("AndroidKeyStore");
        androidStore.load(null);
        androidStore.deleteEntry(TEST_ALIAS);
        assertThrows(IOException.class, () -> store.readToken());
    }

    @Test
    public void clearDropsLocalToken() throws Exception {
        store.saveToken(TOKEN_A);
        store.clearLocalToken();
        assertNull(store.readToken());
    }
}
