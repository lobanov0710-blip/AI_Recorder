package com.nicko.airecorder.ai;

/** Creates processors after process restart; no in-memory provider registration. */
public final class AiProcessorFactory {
    private AiProcessorFactory() {
    }

    public static AiProcessor create() {
        // Replace this explicit failure with the selected provider on the AI stage.
        // A production build must never return test transcripts.
        return (recordId, audioFile) -> {
            throw new AiProcessingException("AI_NOT_CONFIGURED", false);
        };
    }
}
