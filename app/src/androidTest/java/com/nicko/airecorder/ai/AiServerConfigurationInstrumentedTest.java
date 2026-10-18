package com.nicko.airecorder.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.os.Build;
import android.security.NetworkSecurityPolicy;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.nicko.airecorder.BuildConfig;
import com.nicko.airecorder.R;
import com.nicko.airecorder.activities.AiAnalysisActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import okhttp3.HttpUrl;

/** Checks actual debug variant configuration without contacting an AI server. */
@RunWith(AndroidJUnit4.class)
public class AiServerConfigurationInstrumentedTest {
    @Test
    public void debugFactory_selectsTheConfiguredRemoteProvider() {
        assertTrue(BuildConfig.DEBUG);
        HttpUrl base = HttpUrl.get(BuildConfig.AI_SERVER_BASE_URL);
        assertEquals("http", base.scheme());
        assertEquals(8000, base.port());
        assertEquals(!base.host().equals("10.0.2.2"), BuildConfig.AI_ALLOW_LAN_HTTP);
        assertTrue(AiProcessorFactory.create() instanceof RemoteAiProcessor);
    }

    @Test
    public void cleartextHttp_respectsDebugHostAndPlatformLoopback() {
        NetworkSecurityPolicy policy = NetworkSecurityPolicy.getInstance();
        String serverHost = HttpUrl.get(BuildConfig.AI_SERVER_BASE_URL).host();
        assertTrue(policy.isCleartextTrafficPermitted(serverHost));
        if (!serverHost.equals("10.0.2.2")) {
            assertFalse(policy.isCleartextTrafficPermitted("10.0.2.2"));
        }
        assertFalse(policy.isCleartextTrafficPermitted("example.com"));
        assertFalse(policy.isCleartextTrafficPermitted("192.0.2.1"));
        // Android 17+ supplies an implicit localhost config when none is declared.
        // This exception does not apply to arbitrary IP addresses or external hosts.
        assertEquals("Loopback policy must match the Android platform default",
                Build.VERSION.SDK_INT >= 37,
                policy.isCleartextTrafficPermitted("127.0.0.1"));
    }

    @Test
    public void debugScreen_showsThePackagedServerAndActualHttpPolicy() {
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), AiAnalysisActivity.class);
        // No record ID: this only opens the screen and never uploads audio.
        try (ActivityScenario<AiAnalysisActivity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                TextView hint = activity.findViewById(R.id.txtAnalysisHint);
                String text = hint.getText().toString();
                String host = HttpUrl.get(BuildConfig.AI_SERVER_BASE_URL).host();
                int policyText = NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)
                        ? R.string.ai_debug_http_allowed : R.string.ai_debug_http_blocked;
                assertTrue(text.contains(BuildConfig.AI_SERVER_BASE_URL));
                assertTrue(text.contains(activity.getString(policyText)));
            });
        }
    }
}
