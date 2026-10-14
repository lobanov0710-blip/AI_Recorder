package com.nicko.airecorder.viewmodel;

import androidx.annotation.Nullable;

import com.nicko.airecorder.database.AiAnalysisEntity;
import com.nicko.airecorder.database.AiAnalysisStatus;
import com.nicko.airecorder.database.RecordEntity;

/** Immutable screen snapshot. Room owns results; active work owns queue availability. */
public final class AiScreenState {
    public enum Status { LOADING, EMPTY, PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED, MISSING }
    public enum WorkState { NONE, PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }
    public enum Operation { NONE, ENQUEUE, CANCEL }

    private final Status status;
    private final Operation operation;
    private final String recordTitle;
    private final String transcript;
    private final String summary;
    private final String suggestedTitle;
    private final String tasksJson;
    private final String errorCode;
    private final String operationErrorCode;
    private final boolean hasResult;

    private AiScreenState(Status status, Operation operation, @Nullable RecordEntity record,
                          @Nullable AiAnalysisEntity analysis, @Nullable String errorCode,
                          @Nullable String operationErrorCode) {
        this.status = status;
        this.operation = operation;
        this.recordTitle = record == null ? null : record.getTitle();
        boolean showResult = record != null && analysis != null;
        this.transcript = showResult ? analysis.getTranscript() : null;
        this.summary = showResult ? analysis.getSummary() : null;
        this.suggestedTitle = showResult ? analysis.getSuggestedTitle() : null;
        this.tasksJson = showResult ? analysis.getTasksJson() : null;
        this.hasResult = showResult && (analysis.getStatus() == AiAnalysisStatus.SUCCEEDED
                || transcript != null || summary != null || suggestedTitle != null || tasksJson != null);
        this.errorCode = errorCode;
        this.operationErrorCode = operationErrorCode;
    }

    public static AiScreenState from(boolean loaded, @Nullable RecordEntity record,
                                      @Nullable AiAnalysisEntity analysis, WorkState activeWork,
                                      WorkState ownerWork, Operation operation,
                                      @Nullable String operationErrorCode) {
        Status status;
        String error = analysis == null ? null : analysis.getErrorCode();
        if (!loaded) {
            status = Status.LOADING;
        } else if (record == null) {
            status = Status.MISSING;
        } else if (activeWork == WorkState.RUNNING || activeWork == WorkState.PENDING) {
            status = activeWork == WorkState.RUNNING ? Status.RUNNING : Status.PENDING;
            // A result/error from an earlier UUID must not describe the current attempt.
            if (analysis == null || ownerWork != activeWork) {
                error = null;
            }
        } else if (analysis == null) {
            status = Status.EMPTY;
        } else {
            switch (analysis.getStatus()) {
                case SUCCEEDED:
                    status = Status.SUCCEEDED;
                    break;
                case FAILED:
                    status = Status.FAILED;
                    break;
                case CANCELLED:
                    status = Status.CANCELLED;
                    break;
                default:
                    // WorkManager and Room emit independently. A terminal WorkInfo
                    // must release the controls even before onStopped updates Room.
                    if (ownerWork == WorkState.CANCELLED) {
                        status = Status.CANCELLED;
                        error = null;
                    } else if (ownerWork == WorkState.SUCCEEDED) {
                        status = Status.LOADING;
                    } else {
                        status = Status.FAILED;
                        error = ownerWork == WorkState.FAILED
                                ? "PROCESSING_FAILED" : "ANALYSIS_INTERRUPTED";
                    }
            }
        }
        return new AiScreenState(status, operation, record, analysis, error, operationErrorCode);
    }

    public Status getStatus() { return status; }
    public Operation getOperation() { return operation; }
    @Nullable public String getRecordTitle() { return recordTitle; }
    @Nullable public String getTranscript() { return transcript; }
    @Nullable public String getSummary() { return summary; }
    @Nullable public String getSuggestedTitle() { return suggestedTitle; }
    @Nullable public String getTasksJson() { return tasksJson; }
    @Nullable public String getErrorCode() { return errorCode; }
    @Nullable public String getOperationErrorCode() { return operationErrorCode; }
    public boolean hasResult() { return hasResult; }
    public boolean isBusy() {
        return operation != Operation.NONE || status == Status.PENDING || status == Status.RUNNING;
    }
    public boolean canStart() {
        return operation == Operation.NONE && (status == Status.EMPTY || status == Status.SUCCEEDED
                || status == Status.FAILED || status == Status.CANCELLED);
    }
    public boolean canCancel() {
        return operation == Operation.NONE && (status == Status.PENDING || status == Status.RUNNING);
    }
}
