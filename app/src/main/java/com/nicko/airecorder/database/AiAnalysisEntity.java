package com.nicko.airecorder.database;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.PrimaryKey;

import java.util.Objects;

/**
 * One analysis per recording. Deleting the parent record deletes this row.
 *
 * <p>Result fields are nullable while processing has not completed. Tasks
 * are stored as a JSON array of strings; encoding and validation belong
 * to the AI repository. updatedAt uses Unix milliseconds. processingVersion
 * identifies the analysis format/pipeline, independently of the Room version.
 */
@Entity(
        tableName = "ai_analyses",
        foreignKeys = @ForeignKey(
                entity = RecordEntity.class,
                parentColumns = "id",
                childColumns = "recordId",
                onDelete = ForeignKey.CASCADE
        )
)
public class AiAnalysisEntity {

    @PrimaryKey
    private final long recordId;

    @NonNull
    private AiAnalysisStatus status;

    @Nullable
    private String transcript;

    @Nullable
    private String summary;

    @Nullable
    private String suggestedTitle;

    @Nullable
    private String tasksJson;

    @Nullable
    private String errorCode;

    private long updatedAt;

    private int processingVersion;

    @Nullable
    private String requestId;

    public AiAnalysisEntity(
            long recordId,
            @NonNull AiAnalysisStatus status,
            long updatedAt,
            int processingVersion
    ) {
        if (recordId <= 0L) {
            throw new IllegalArgumentException("recordId must be positive");
        }
        this.recordId = recordId;
        setStatus(status);
        setUpdatedAt(updatedAt);
        setProcessingVersion(processingVersion);
    }

    public long getRecordId() {
        return recordId;
    }

    @NonNull
    public AiAnalysisStatus getStatus() {
        return status;
    }

    public void setStatus(@NonNull AiAnalysisStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    @Nullable
    public String getTranscript() {
        return transcript;
    }

    public void setTranscript(@Nullable String transcript) {
        this.transcript = transcript;
    }

    @Nullable
    public String getSummary() {
        return summary;
    }

    public void setSummary(@Nullable String summary) {
        this.summary = summary;
    }

    @Nullable
    public String getSuggestedTitle() {
        return suggestedTitle;
    }

    public void setSuggestedTitle(@Nullable String suggestedTitle) {
        this.suggestedTitle = suggestedTitle;
    }

    @Nullable
    public String getTasksJson() {
        return tasksJson;
    }

    public void setTasksJson(@Nullable String tasksJson) {
        this.tasksJson = tasksJson;
    }

    @Nullable
    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(@Nullable String errorCode) {
        this.errorCode = errorCode;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        if (updatedAt < 0L) {
            throw new IllegalArgumentException("updatedAt must not be negative");
        }
        this.updatedAt = updatedAt;
    }

    public int getProcessingVersion() {
        return processingVersion;
    }

    public void setProcessingVersion(int processingVersion) {
        if (processingVersion <= 0) {
            throw new IllegalArgumentException("processingVersion must be positive");
        }
        this.processingVersion = processingVersion;
    }

    /** UUID of the WorkManager request owning this analysis. Null for legacy rows. */
    @Nullable
    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(@Nullable String requestId) {
        this.requestId = requestId;
    }
}
