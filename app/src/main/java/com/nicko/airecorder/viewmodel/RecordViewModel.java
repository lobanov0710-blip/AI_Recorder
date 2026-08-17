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

public class RecordViewModel extends AndroidViewModel {

    private final RecordRepository repository;

    private final MutableLiveData<RecordingState> recordingState =
            new MutableLiveData<>();

    private final LiveData<List<RecordEntity>> records;

    public RecordViewModel(
            @NonNull Application application
    ) {

        super(application);

        repository =
                new RecordRepository(application);

        records =
                repository.getAll();

        recordingState.setValue(
                RecordingState.IDLE
        );

    }

    public LiveData<RecordingState> getRecordingState() {

        return recordingState;

    }

    public LiveData<List<RecordEntity>> getRecords() {

        return records;

    }
    public void requestRecordingState() {

        sendServiceAction(
                RecordActions.ACTION_REQUEST_STATE
        );

    }

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

    public void pauseRecording() {

        if (recordingState.getValue()
                != RecordingState.RECORDING) {
            return;
        }

        sendServiceAction(
                RecordActions.ACTION_PAUSE
        );

    }

    public void resumeRecording() {

        if (recordingState.getValue()
                != RecordingState.PAUSED) {
            return;
        }

        sendServiceAction(
                RecordActions.ACTION_RESUME
        );

    }

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

    public void rename(
            long id,
            String title
    ) {

        String newTitle = title.trim();

        if (newTitle.isEmpty()) {
            return;
        }

        repository.rename(
                id,
                newTitle
        );

    }

    public void delete(long id) {

        if (id <= 0) {
            return;
        }

        repository.delete(id);

    }

    private void sendServiceAction(String action) {

        Intent intent =
                new Intent(
                        getApplication(),
                        RecordService.class
                );

        intent.setAction(action);

        if (RecordActions.ACTION_START.equals(action)
                && Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.O) {

            getApplication()
                    .startForegroundService(intent);

        } else {

            getApplication()
                    .startService(intent);

        }

    }

    @Override
    protected void onCleared() {

        repository.shutdown();

        super.onCleared();

    }

}