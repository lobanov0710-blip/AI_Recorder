package com.nicko.airecorder.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.LiveData;
import androidx.work.BackoffPolicy;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.common.util.concurrent.ListenableFuture;
import com.nicko.airecorder.ai.AiResult;
import com.nicko.airecorder.ai.RecordingFileValidator;
import com.nicko.airecorder.common.RecordOperationLock;
import com.nicko.airecorder.database.AiAnalysisDao;
import com.nicko.airecorder.database.AiAnalysisEntity;
import com.nicko.airecorder.database.AiAnalysisStatus;
import com.nicko.airecorder.database.AppDatabase;
import com.nicko.airecorder.database.RecordEntity;
import com.nicko.airecorder.worker.AiProcessingWorker;

import org.json.JSONArray;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Owns the AI queue and its persistent state; shared for the application lifetime. */
public final class AiRepository {
    private static volatile AiRepository instance;
    private final AppDatabase database;
    private final AiAnalysisDao analysisDao;
    private final WorkManager workManager;
    private final File filesDir;
    private final ExecutorService queueExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface QueueCallback {
        /** Success means the queue operation committed, not that AI has completed. */
        void onComplete(boolean success, @Nullable String errorCode);
    }

    public static AiRepository getInstance(Context context) {
        if (instance == null) {
            synchronized (AiRepository.class) {
                if (instance == null) {
                    Context application = context.getApplicationContext();
                    instance = new AiRepository(
                            AppDatabase.getInstance(application),
                            WorkManager.getInstance(application),
                            application.getFilesDir()
                    );
                }
            }
        }
        return instance;
    }

    @VisibleForTesting
    public AiRepository(AppDatabase database, WorkManager workManager, File filesDir) {
        this.database = database;
        this.analysisDao = database.aiAnalysisDao();
        this.workManager = workManager;
        this.filesDir = filesDir;
    }

    public static String workName(long recordId) {
        return "ai-record-" + recordId;
    }

    public LiveData<AiAnalysisEntity> observeAnalysis(long recordId) {
        return analysisDao.observeByRecordId(recordId);
    }

    public LiveData<List<WorkInfo>> observeWork(long recordId) {
        return workManager.getWorkInfosForUniqueWorkLiveData(workName(recordId));
    }

    public void enqueue(long recordId, @Nullable QueueCallback callback) {
        queueExecutor.execute(() -> {
            try {
                synchronized (RecordOperationLock.LOCK) {
                    if (recordId <= 0L) {
                        postCallback(callback, false, "INVALID_RECORD_ID");
                        return;
                    }
                    resolveRecording(recordId);
                    OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                            AiProcessingWorker.class
                    ).setInputData(new Data.Builder()
                            .putLong(AiProcessingWorker.KEY_RECORD_ID, recordId)
                            .build())
                            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                            .build();

                    // WorkManager persists the request first. If the process dies
                    // before the analysis row is written, the worker can recover it.
                    await(workManager.enqueueUniqueWork(
                            workName(recordId), ExistingWorkPolicy.KEEP, request
                    ).getResult());
                    WorkInfo accepted = await(workManager.getWorkInfoById(request.getId()));
                    // KEEP discards the new UUID when another request is active.
                    if (accepted != null && !accepted.getState().isFinished()) {
                        try {
                            // Keep an earlier result until a new attempt succeeds.
                            AiAnalysisEntity pending = analysisDao.getByRecordId(recordId);
                            if (pending == null) {
                                pending = new AiAnalysisEntity(recordId, AiAnalysisStatus.PENDING,
                                        System.currentTimeMillis(), AiProcessingWorker.PROCESSING_VERSION);
                            }
                            pending.setStatus(AiAnalysisStatus.PENDING);
                            pending.setErrorCode(null);
                            pending.setUpdatedAt(System.currentTimeMillis());
                            pending.setRequestId(request.getId().toString());
                            analysisDao.upsert(pending);
                        } catch (RuntimeException error) {
                            await(workManager.cancelWorkById(request.getId()).getResult());
                            throw error;
                        }
                    }
                }
                postCallback(callback, true, null);
            } catch (IOException error) {
                postCallback(callback, false, "INVALID_RECORDING");
            } catch (ExecutionException | RuntimeException error) {
                Log.e("AiRepository", "AI queue operation failed");
                postCallback(callback, false, "QUEUE_FAILED");
            }
        });
    }

    public void cancel(long recordId, @Nullable QueueCallback callback) {
        queueExecutor.execute(() -> {
            try {
                cancelAndWait(recordId);
                postCallback(callback, true, null);
            } catch (ExecutionException | RuntimeException error) {
                postCallback(callback, false, "QUEUE_FAILED");
            }
        });
    }

    /** Background-only; also called before deleting a recording under the shared lock. */
    public void cancelAndWait(long recordId) throws ExecutionException {
        if (recordId <= 0L) {
            throw new IllegalArgumentException("recordId must be positive");
        }
        synchronized (RecordOperationLock.LOCK) {
            await(workManager.cancelUniqueWork(workName(recordId)).getResult());
            analysisDao.cancelActive(recordId, System.currentTimeMillis());
        }
    }

    /** Returns a terminal own row on recovery, or claims a new/rerun request. */
    @Nullable
    public AiAnalysisEntity begin(long recordId, UUID requestId, BooleanSupplier stopped)
            throws ExecutionException {
        synchronized (RecordOperationLock.LOCK) {
            if (stopped.getAsBoolean()) {
                return null;
            }
            WorkInfo self = await(workManager.getWorkInfoById(requestId));
            if (self != null && self.getState().isFinished()) {
                return null;
            }
            AiAnalysisEntity current = analysisDao.getByRecordId(recordId);
            String owner = requestId.toString();
            if (current != null && current.getRequestId() != null
                    && !owner.equals(current.getRequestId())) {
                WorkInfo other = await(workManager.getWorkInfoById(
                        UUID.fromString(current.getRequestId())
                ));
                if (other != null && !other.getState().isFinished()) {
                    return null;
                }
            }
            return database.runInTransaction(() -> {
                if (stopped.getAsBoolean() || database.recordDao().getById(recordId) == null) {
                    return null;
                }
                AiAnalysisEntity analysis = analysisDao.getByRecordId(recordId);
                if (analysis != null && owner.equals(analysis.getRequestId())) {
                    AiAnalysisStatus status = analysis.getStatus();
                    if (status == AiAnalysisStatus.SUCCEEDED || status == AiAnalysisStatus.FAILED
                            || status == AiAnalysisStatus.CANCELLED) {
                        return analysis;
                    }
                } else {
                    if (analysis == null) {
                        analysis = new AiAnalysisEntity(recordId, AiAnalysisStatus.PENDING,
                                System.currentTimeMillis(), AiProcessingWorker.PROCESSING_VERSION);
                    }
                    analysis.setRequestId(owner);
                }
                analysis.setStatus(AiAnalysisStatus.RUNNING);
                analysis.setErrorCode(null);
                analysis.setUpdatedAt(System.currentTimeMillis());
                analysisDao.upsert(analysis);
                return analysis;
            });
        }
    }

    public File resolveRecording(long recordId) throws IOException {
        synchronized (RecordOperationLock.LOCK) {
            RecordEntity record = database.recordDao().getById(recordId);
            if (record == null) {
                throw new IOException("RECORD_NOT_FOUND");
            }
            return RecordingFileValidator.requireFinalFile(
                    filesDir, record.getFilePath(), record.getDuration()
            );
        }
    }

    public boolean complete(AiResult result, UUID requestId, BooleanSupplier stopped)
            throws IOException {
        synchronized (RecordOperationLock.LOCK) {
            if (stopped.getAsBoolean()) {
                return false;
            }
            resolveRecording(result.getRecordId());
            String tasksJson = new JSONArray(result.getTasks()).toString();
            return analysisDao.complete(result.getRecordId(), requestId.toString(),
                    result.getTranscript(), result.getSummary(), result.getSuggestedTitle(),
                    tasksJson, System.currentTimeMillis(), AiProcessingWorker.PROCESSING_VERSION) == 1;
        }
    }

    public boolean setActiveState(long recordId, UUID requestId, AiAnalysisStatus status,
                                  @Nullable String errorCode) {
        synchronized (RecordOperationLock.LOCK) {
            return analysisDao.setActiveState(recordId, requestId.toString(), status,
                    errorCode, System.currentTimeMillis()) == 1;
        }
    }

    /** onStopped must return immediately; it may run inside WorkManager cancellation. */
    public void onWorkerStopped(long recordId, UUID requestId) {
        queueExecutor.execute(() -> {
            try {
                synchronized (RecordOperationLock.LOCK) {
                    WorkInfo info = await(workManager.getWorkInfoById(requestId));
                    if (info != null && info.getState().isFinished()
                            && info.getState() != WorkInfo.State.CANCELLED) {
                        return;
                    }
                    boolean cancelled = info != null && info.getState() == WorkInfo.State.CANCELLED;
                    setActiveState(recordId, requestId,
                            cancelled ? AiAnalysisStatus.CANCELLED : AiAnalysisStatus.PENDING,
                            cancelled ? "CANCELLED" : "WORK_STOPPED");
                }
            } catch (ExecutionException | RuntimeException error) {
                Log.e("AiRepository", "Could not update stopped AI work");
            }
        });
    }

    private void postCallback(@Nullable QueueCallback callback, boolean success,
                              @Nullable String errorCode) {
        if (callback != null) {
            mainHandler.post(() -> callback.onComplete(success, errorCode));
        }
    }

    private static <T> T await(ListenableFuture<T> future) throws ExecutionException {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return future.get();
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @VisibleForTesting
    public void shutdownForTests() throws InterruptedException {
        queueExecutor.shutdown();
        if (!queueExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("AI queue did not stop");
        }
    }
}
