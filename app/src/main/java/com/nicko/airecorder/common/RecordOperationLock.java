package com.nicko.airecorder.common;

/**
 * Serializes recording file/database changes with AI queue state changes.
 * Never hold this lock while running an AI processor or making network calls.
 */
public final class RecordOperationLock {
    public static final Object LOCK = new Object();

    private RecordOperationLock() {
    }
}
