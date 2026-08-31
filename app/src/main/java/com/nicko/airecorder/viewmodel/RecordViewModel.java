package com.nicko.airecorder.viewmodel;

import android.app.Application;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.nicko.airecorder.common.RecordActions;
import com.nicko.airecorder.database.RecordEntity;
import com.nicko.airecorder.model.RecordingState;
import com.nicko.airecorder.repository.RecordRepository;
import com.nicko.airecorder.service.RecordService;

import java.util.List;

public class RecordViewModel
        extends AndroidViewModel {

    /*
     * =========================================================
     * REPOSITORY
     * =========================================================
     */

    private final RecordRepository repository;

    /*
     * =========================================================
     * RECORDING STATE
     * =========================================================
     */

    private final MutableLiveData<RecordingState> recordingState =
            new MutableLiveData<>();

    /*
     * =========================================================
     * RECORD LIST
     * =========================================================
     */

    private final LiveData<List<RecordEntity>> records;

    /*
     * =========================================================
     * CONSTRUCTOR
     * =========================================================
     */

    public RecordViewModel(
            @NonNull Application application
    ) {

        super(
                application
        );

        repository =
                new RecordRepository(
                        application
                );

        records =
                repository.getAll();

        recordingState.setValue(
                RecordingState.IDLE
        );
    }

    /*
     * =========================================================
     * GETTERS
     * =========================================================
     */

    public LiveData<RecordingState> getRecordingState() {

        return recordingState;
    }

    public LiveData<List<RecordEntity>> getRecords() {

        return records;
    }

    /*
     * =========================================================
     * RECONCILIATION
     * =========================================================
     *
     * Запускается MainActivity при создании.
     *
     * Repository выполняет всю работу
     * на background executor.
     */
    public void reconcileStorage() {

        repository.reconcileStorage();
    }

    /*
     * =========================================================
     * REQUEST CURRENT SERVICE STATE
     * =========================================================
     */

    public void requestRecordingState() {

        sendServiceAction(
                RecordActions.ACTION_REQUEST_STATE
        );
    }

    /*
     * =========================================================
     * START
     * =========================================================
     */

    public void startRecording() {

        RecordingState state =
                recordingState.getValue();

        if (state == RecordingState.RECORDING
                || state == RecordingState.PAUSED) {

            return;
        }

        sendServiceAction(
                RecordActions.ACTION_START
        );
    }

    /*
     * =========================================================
     * PAUSE
     * =========================================================
     */

    public void pauseRecording() {

        if (recordingState.getValue()
                != RecordingState.RECORDING) {

            return;
        }

        sendServiceAction(
                RecordActions.ACTION_PAUSE
        );
    }

    /*
     * =========================================================
     * RESUME
     * =========================================================
     */

    public void resumeRecording() {

        if (recordingState.getValue()
                != RecordingState.PAUSED) {

            return;
        }

        sendServiceAction(
                RecordActions.ACTION_RESUME
        );
    }

    /*
     * =========================================================
     * STOP
     * =========================================================
     */

    public void stopRecording() {

        RecordingState state =
                recordingState.getValue();

        if (state != RecordingState.RECORDING
                && state != RecordingState.PAUSED) {

            return;
        }

        sendServiceAction(
                RecordActions.ACTION_STOP
        );
    }

    /*
     * =========================================================
     * STATE FROM SERVICE
     * =========================================================
     */

    public void setRecording() {

        if (recordingState.getValue()
                == RecordingState.RECORDING) {

            return;
        }

        recordingState.setValue(
                RecordingState.RECORDING
        );
    }

    public void setPaused() {

        if (recordingState.getValue()
                == RecordingState.PAUSED) {

            return;
        }

        recordingState.setValue(
                RecordingState.PAUSED
        );
    }

    public void setResumed() {

        if (recordingState.getValue()
                == RecordingState.RECORDING) {

            return;
        }

        recordingState.setValue(
                RecordingState.RECORDING
        );
    }

    public void setStopped() {

        if (recordingState.getValue()
                == RecordingState.IDLE) {

            return;
        }

        recordingState.setValue(
                RecordingState.IDLE
        );
    }

    /*
     * =========================================================
     * RENAME
     * =========================================================
     */

    public void rename(
            long id,
            String title
    ) {

        if (id <= 0L
                || title == null) {

            return;
        }

        String newTitle =
                title.trim();

        if (newTitle.isEmpty()) {

            return;
        }

        repository.rename(
                id,
                newTitle
        );
    }

    /*
     * =========================================================
     * CONSISTENT DELETE
     * =========================================================
     *
     * Activity больше не удаляет File самостоятельно.
     *
     * Repository управляет:
     *
     * filesystem
     * +
     * Room
     */
    public void deleteRecord(
            long id,
            String filePath,
            RecordRepository.OperationCallback callback
    ) {

        if (id <= 0L) {

            if (callback != null) {

                callback.onComplete(
                        false
                );
            }

            return;
        }

        repository.deleteRecord(
                id,
                filePath,
                callback
        );
    }

    /*
     * =========================================================
     * SERVICE COMMAND
     * =========================================================
     */

    private void sendServiceAction(
            String action
    ) {

        if (action == null
                || action.trim().isEmpty()) {

            return;
        }

        Intent intent =
                new Intent(
                        getApplication(),
                        RecordService.class
                );

        intent.setAction(
                action
        );

        if (RecordActions.ACTION_START.equals(
                action
        )
                && Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.O) {

            getApplication()
                    .startForegroundService(
                            intent
                    );

        } else {

            getApplication()
                    .startService(
                            intent
                    );
        }
    }

    /*
     * =========================================================
     * CLEAR
     * =========================================================
     */

    @Override
    protected void onCleared() {

        repository.shutdown();

        super.onCleared();
    }
}