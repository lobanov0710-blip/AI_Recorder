package com.nicko.airecorder.worker;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.nicko.airecorder.ai.AiProcessingException;
import com.nicko.airecorder.ai.AiProcessor;
import com.nicko.airecorder.ai.AiProcessorFactory;
import com.nicko.airecorder.ai.AiResult;
import com.nicko.airecorder.database.AiAnalysisEntity;
import com.nicko.airecorder.database.AiAnalysisStatus;
import com.nicko.airecorder.repository.AiRepository;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

public final class AiProcessingWorker extends Worker {
    public static final String KEY_RECORD_ID = "record_id";
    public static final String KEY_ERROR_CODE = "error_code";
    public static final int PROCESSING_VERSION = 1;
    private static final int MAX_ATTEMPTS = 3;

    private final AiRepository repository;
    private final AiProcessor processor;

    public AiProcessingWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        this(context, parameters, AiRepository.getInstance(context), AiProcessorFactory.create());
    }

    @VisibleForTesting
    public AiProcessingWorker(Context context, WorkerParameters parameters,
                              AiRepository repository, AiProcessor processor) {
        super(context, parameters);
        this.repository = repository;
        this.processor = processor;
    }

    @NonNull
    @Override
    public Result doWork() {
        long recordId = getInputData().getLong(KEY_RECORD_ID, -1L);
        if (recordId <= 0L) {
            return failure("INVALID_RECORD_ID");
        }
        try {
            AiAnalysisEntity analysis = repository.begin(recordId, getId(), this::isStopped);
            if (analysis == null) {
                return failure("STALE_OR_MISSING_RECORD");
            }
            if (analysis.getStatus() == AiAnalysisStatus.SUCCEEDED) {
                return Result.success();
            }
            if (analysis.getStatus() != AiAnalysisStatus.RUNNING) {
                return failure(analysis.getErrorCode() == null
                        ? "WORK_NOT_ACTIVE" : analysis.getErrorCode());
            }
        } catch (ExecutionException | RuntimeException error) {
            return failure("STORAGE_ERROR");
        }

        File recording;
        try {
            recording = repository.resolveRecording(recordId);
        } catch (IOException error) {
            return fail(recordId, "INVALID_RECORDING");
        }

        try {
            if (isStopped()) {
                return Result.retry();
            }
            AiResult result = processor.process(recordId, recording);
            if (result == null || result.getRecordId() != recordId) {
                return fail(recordId, "INVALID_AI_RESULT");
            }
            if (isStopped()) {
                return Result.retry();
            }
            return repository.complete(result, getId(), this::isStopped)
                    ? Result.success() : failure("STALE_REQUEST");
        } catch (AiProcessingException error) {
            if (error.isRetryable() && getRunAttemptCount() + 1 < MAX_ATTEMPTS) {
                return retry(recordId, error.getErrorCode());
            }
            return fail(recordId, error.isRetryable()
                    ? "RETRY_LIMIT_REACHED" : error.getErrorCode());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return retry(recordId, "WORK_INTERRUPTED");
        } catch (IOException | RuntimeException error) {
            return fail(recordId, "PROCESSING_FAILED");
        }
    }

    private Result retry(long recordId, String errorCode) {
        if (isStopped()) {
            return Result.retry();
        }
        if (getRunAttemptCount() + 1 >= MAX_ATTEMPTS) {
            return fail(recordId, "RETRY_LIMIT_REACHED");
        }
        try {
            return repository.setActiveState(recordId, getId(), AiAnalysisStatus.PENDING, errorCode)
                    ? Result.retry() : failure("STALE_REQUEST");
        } catch (RuntimeException error) {
            return failure("STORAGE_ERROR");
        }
    }

    private Result fail(long recordId, String errorCode) {
        try {
            if (!isStopped()) {
                repository.setActiveState(recordId, getId(), AiAnalysisStatus.FAILED, errorCode);
            }
        } catch (RuntimeException error) {
            return failure("STORAGE_ERROR");
        }
        return failure(errorCode);
    }

    private static Result failure(String errorCode) {
        return Result.failure(new Data.Builder().putString(KEY_ERROR_CODE, errorCode).build());
    }

    @Override
    public void onStopped() {
        long recordId = getInputData().getLong(KEY_RECORD_ID, -1L);
        if (recordId > 0L) {
            repository.onWorkerStopped(recordId, getId());
        }
    }
}
