package com.nicko.airecorder.database;

/** Persisted processing state, separate from the microphone recording state. */
public enum AiAnalysisStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
