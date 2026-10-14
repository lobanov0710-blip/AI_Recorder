package com.nicko.airecorder.ai;

import java.io.File;
import java.io.IOException;

/**
 * Processes one saved recording without depending on Android or Room.
 *
 * <p>This is a synchronous contract. Call it from a background worker,
 * never from the main thread or the microphone capture pipeline.
 */
public interface AiProcessor {

    /**
     * Produces a transcript and analysis for one recording.
     *
     * <p>The caller must resolve the file from a persisted record and check
     * that its M4A has been finalized successfully. A pending recording
     * must not be submitted. Implementations must leave the source file
     * unchanged and return a result with the same record ID.
     *
     * @param recordId positive ID of the saved recording
     * @param audioFile non-null, finalized audio file belonging to that ID
     * @return non-null analysis associated with {@code recordId}
     * @throws IOException if the audio cannot be read or processing fails
     * @throws InterruptedException if processing is cancelled by interruption
     */
    AiResult process(long recordId, File audioFile)
            throws IOException, InterruptedException;
}
