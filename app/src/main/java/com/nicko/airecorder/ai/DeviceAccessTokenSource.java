package com.nicko.airecorder.ai;

import java.io.IOException;

/** Optional HTTPS credential source. Must never embed tokens in an APK or BuildConfig. */
public interface DeviceAccessTokenSource {
    /** Returns the paired device token, or null if not paired. Never log the token. */
    String readToken() throws IOException;
}
