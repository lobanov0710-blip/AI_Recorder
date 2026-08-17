package com.nicko.airecorder.controller;

import android.content.Context;

import com.nicko.airecorder.database.RecordEntity;
import com.nicko.airecorder.repository.RecordRepository;
import com.nicko.airecorder.utils.AudioRecorder;

import java.io.File;

public class RecordServiceController {

    private final AudioRecorder audioRecorder;

    private final RecordRepository repository;

    private final Context context;

    public RecordServiceController(Context context) {

        this.context =
                context.getApplicationContext();

        this.audioRecorder =
                new AudioRecorder();

        this.repository =
                new RecordRepository(
                        this.context
                );

    }

    public boolean pauseRecording() {

        return audioRecorder.pauseRecording();

    }

    public boolean resumeRecording() {

        return audioRecorder.resumeRecording();

    }

    public boolean isPaused() {

        return audioRecorder.isPaused();

    }

    public boolean isRecording() {

        return audioRecorder.isRecording();

    }

    public int getMaxAmplitude() {

        return audioRecorder.getMaxAmplitude();

    }

    public File startRecording()
            throws Exception {

        String fileName =
                "record_"
                        + System.currentTimeMillis()
                        + ".m4a";

        File outputFile =
                new File(
                        context.getFilesDir(),
                        fileName
                );

        audioRecorder.startRecording(
                outputFile
        );

        return outputFile;

    }

    public boolean stopRecording() {

        return audioRecorder.stopRecording();

    }

    public void saveRecord(long duration) {

        File file =
                audioRecorder.getOutputFile();

        if (!isValidRecording(file)) {
            return;
        }

        repository.insert(

                new RecordEntity(

                        file.getName(),

                        file.getAbsolutePath(),

                        System.currentTimeMillis(),

                        file.getName(),

                        duration

                )

        );

    }

    private boolean isValidRecording(
            File file
    ) {

        if (file == null) {
            return false;
        }

        if (!file.exists()) {
            return false;
        }

        if (file.length() <= 0) {

            //noinspection ResultOfMethodCallIgnored
            file.delete();

            return false;

        }

        return true;

    }

    public void shutdown() {

        repository.shutdown();

    }

}