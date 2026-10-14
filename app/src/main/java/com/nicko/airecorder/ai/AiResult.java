package com.nicko.airecorder.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable analysis of one saved recording.
 *
 * <p>Text fields may be empty, for example when no speech was recognized.
 * Tasks may be empty. Null values are rejected. A suggested title is a
 * proposal; creating this result does not rename the recording.
 */
public final class AiResult {

    private final long recordId;
    private final String transcript;
    private final String summary;
    private final String suggestedTitle;
    private final List<String> tasks;

    public AiResult(
            long recordId,
            String transcript,
            String summary,
            String suggestedTitle,
            List<String> tasks
    ) {
        if (recordId <= 0L) {
            throw new IllegalArgumentException("recordId must be positive");
        }

        this.recordId = recordId;
        this.transcript = Objects.requireNonNull(transcript, "transcript");
        this.summary = Objects.requireNonNull(summary, "summary");
        this.suggestedTitle = Objects.requireNonNull(
                suggestedTitle,
                "suggestedTitle"
        );

        List<String> taskCopy = new ArrayList<>(
                Objects.requireNonNull(tasks, "tasks")
        );
        for (String task : taskCopy) {
            Objects.requireNonNull(task, "task");
        }
        this.tasks = Collections.unmodifiableList(taskCopy);
    }

    public long getRecordId() {
        return recordId;
    }

    public String getTranscript() {
        return transcript;
    }

    public String getSummary() {
        return summary;
    }

    public String getSuggestedTitle() {
        return suggestedTitle;
    }

    public List<String> getTasks() {
        return tasks;
    }
}
