package com.nicko.airecorder.viewmodel;

import android.app.Application;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.work.WorkInfo;

import com.nicko.airecorder.database.AiAnalysisEntity;
import com.nicko.airecorder.database.RecordEntity;
import com.nicko.airecorder.repository.AiDataSource;
import com.nicko.airecorder.repository.AiRepository;

import java.util.Collections;
import java.util.List;

public final class AiViewModel extends AndroidViewModel {
    private final AiDataSource source;
    private final MediatorLiveData<AiScreenState> state = new MediatorLiveData<>();
    private long recordId;
    private boolean initialized;
    private boolean recordLoaded;
    private boolean analysisLoaded;
    private boolean workLoaded;
    private boolean cleared;
    private RecordEntity record;
    private AiAnalysisEntity analysis;
    private List<WorkInfo> work = Collections.emptyList();
    private AiScreenState.Operation operation = AiScreenState.Operation.NONE;
    private String operationError;

    public AiViewModel(@NonNull Application application) {
        this(application, AiRepository.getInstance(application));
    }

    @VisibleForTesting
    public AiViewModel(@NonNull Application application, AiDataSource source) {
        super(application);
        this.source = source;
        publish();
    }

    public LiveData<AiScreenState> getState() { return state; }

    /** Intent identity is restored by Android; observation alone never starts processing. */
    @MainThread
    public void initialize(long id) {
        if (initialized) {
            if (recordId != id) {
                throw new IllegalStateException("The analysis screen cannot change its recording");
            }
            return;
        }
        initialized = true;
        recordId = id;
        if (id <= 0L) {
            recordLoaded = analysisLoaded = workLoaded = true;
            publish();
            return;
        }
        state.addSource(source.observeRecord(id), value -> {
            record = value;
            recordLoaded = true;
            publish();
        });
        state.addSource(source.observeAnalysis(id), value -> {
            analysis = value;
            analysisLoaded = true;
            publish();
        });
        state.addSource(source.observeWork(id), value -> {
            work = value == null ? Collections.emptyList() : value;
            workLoaded = true;
            publish();
        });
    }

    @MainThread
    public void startAnalysis() {
        AiScreenState current = state.getValue();
        if (current == null || !current.canStart()) {
            return;
        }
        operation = AiScreenState.Operation.ENQUEUE;
        operationError = null;
        publish();
        source.enqueue(recordId, this::operationCompleted);
    }

    @MainThread
    public void cancelAnalysis() {
        AiScreenState current = state.getValue();
        if (current == null || !current.canCancel()) {
            return;
        }
        operation = AiScreenState.Operation.CANCEL;
        operationError = null;
        publish();
        source.cancel(recordId, this::operationCompleted);
    }

    private void operationCompleted(boolean success, String errorCode) {
        if (cleared) {
            return;
        }
        operation = AiScreenState.Operation.NONE;
        operationError = success ? null : (errorCode == null ? "QUEUE_FAILED" : errorCode);
        publish();
    }

    private void publish() {
        AiScreenState.WorkState active = AiScreenState.WorkState.NONE;
        AiScreenState.WorkState owner = AiScreenState.WorkState.NONE;
        for (WorkInfo info : work) {
            AiScreenState.WorkState phase = phase(info.getState());
            if (!info.getState().isFinished()) {
                active = phase;
            }
            if (analysis != null && info.getId().toString().equals(analysis.getRequestId())) {
                owner = phase;
            }
        }
        state.setValue(AiScreenState.from(recordLoaded && analysisLoaded && workLoaded,
                record, analysis, active, owner, operation, operationError));
    }

    private static AiScreenState.WorkState phase(WorkInfo.State value) {
        switch (value) {
            case RUNNING: return AiScreenState.WorkState.RUNNING;
            case SUCCEEDED: return AiScreenState.WorkState.SUCCEEDED;
            case FAILED: return AiScreenState.WorkState.FAILED;
            case CANCELLED: return AiScreenState.WorkState.CANCELLED;
            default: return AiScreenState.WorkState.PENDING;
        }
    }

    @Override
    protected void onCleared() {
        cleared = true;
        // The application repository and WorkManager outlive this screen.
        super.onCleared();
    }
}
