package com.nicko.airecorder.viewmodel;

import android.app.Application;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
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

    private static final String TAG =
            "RecordViewModel";

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
     * STORAGE RECONCILIATION
     * =========================================================
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
                RecordActions.ACTION_REQUEST_STATE,
                false
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

        boolean started =
                sendServiceAction(
                        RecordActions.ACTION_START,
                        true
                );

        /*
         * UI не должен переходить в RECORDING
         * до подтверждения от RecordService.
         *
         * При системном отказе явно оставляем IDLE.
         */
        if (!started) {

            recordingState.setValue(
                    RecordingState.IDLE
            );
        }
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
                RecordActions.ACTION_PAUSE,
                false
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
                RecordActions.ACTION_RESUME,
                false
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
                RecordActions.ACTION_STOP,
                false
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
     * DELETE
     * =========================================================
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
     *
     * foregroundStart = true
     *
     * только для ACTION_START.
     *
     * Pause / Resume / Stop / RequestState работают
     * с уже существующим Service либо создают обычный
     * short-lived Service для state request.
     */
    private boolean sendServiceAction(
            String action,
            boolean foregroundStart
    ) {

        if (action == null
                || action.trim().isEmpty()) {

            return false;
        }

        Intent intent =
                new Intent(
                        getApplication(),
                        RecordService.class
                );

        intent.setAction(
                action
        );

        try {

            if (foregroundStart) {

                /*
                 * API 26+:
                 * Context.startForegroundService()
                 *
                 * API < 26:
                 * Context.startService()
                 */
                ContextCompat.startForegroundService(
                        getApplication(),
                        intent
                );

            } else {

                getApplication()
                        .startService(
                                intent
                        );
            }

            return true;

        } catch (SecurityException e) {

            /*
             * Возможные причины:
             *
             * - отсутствует необходимое permission;
             * - FGS type не соответствует manifest;
             * - microphone while-in-use permission
             *   недоступен в текущем состоянии.
             */
            Log.e(
                    TAG,
                    "Система запретила запуск RecordService. action="
                            + action,
                    e
            );

            return false;

        } catch (IllegalStateException e) {

            /*
             * Включает:
             *
             * ForegroundServiceStartNotAllowedException
             * на Android 12+,
             *
             * а также ограничения background service
             * на более старых Android.
             */
            Log.e(
                    TAG,
                    "RecordService нельзя запустить в текущем состоянии. action="
                            + action,
                    e
            );

            return false;
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