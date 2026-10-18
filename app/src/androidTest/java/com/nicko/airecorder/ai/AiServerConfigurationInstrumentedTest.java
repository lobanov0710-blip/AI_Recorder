package com.nicko.airecorder.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.security.NetworkSecurityPolicy;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.nicko.airecorder.BuildConfig;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Checks actual debug variant configuration without contacting an AI server. */
@RunWith(AndroidJUnit4.class)
public class AiServerConfigurationInstrumentedTest {
    @Test
    public void debugFactory_selectsTheConfiguredRemoteProvider() {
        assertTrue(BuildConfig.DEBUG);
        assertEquals("http://10.0.2.2:8000/", BuildConfig.AI_SERVER_BASE_URL);
        assertTrue(AiProcessorFactory.create() instanceof RemoteAiProcessor);
    }

    @Test
    public void cleartextHttp_isLimitedToTheEmulatorHost() {
        NetworkSecurityPolicy policy = NetworkSecurityPolicy.getInstance();
        assertTrue(policy.isCleartextTrafficPermitted("10.0.2.2"));
        assertFalse(policy.isCleartextTrafficPermitted("example.com"));
        assertFalse(policy.isCleartextTrafficPermitted("127.0.0.1"));
    }
}
