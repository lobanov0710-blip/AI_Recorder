package com.nicko.airecorder.ai;

import com.nicko.airecorder.BuildConfig;

/** Creates processors after process restart; no in-memory provider registration. */
public final class AiProcessorFactory {
    private AiProcessorFactory() {
    }

    public static AiProcessor create() {
        return create(BuildConfig.AI_SERVER_BASE_URL, BuildConfig.DEBUG && BuildConfig.AI_ALLOW_LAN_HTTP);
    }

    static AiProcessor create(String baseUrl) {
        return create(baseUrl, false);
    }

    static AiProcessor create(String baseUrl, boolean allowLanHttp) {
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            try {
                return new RemoteAiProcessor(baseUrl, allowLanHttp);
            } catch (IllegalArgumentException invalidConfiguration) {
                // Fail inside process(), where the worker can persist a safe error code.
            }
        }
        return (recordId, audioFile) -> {
            throw new AiProcessingException("AI_NOT_CONFIGURED", false);
        };
    }
}
