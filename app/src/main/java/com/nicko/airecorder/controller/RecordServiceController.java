package com.nicko.airecorder.controller;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import com.nicko.airecorder.database.RecordEntity;
import com.nicko.airecorder.repository.RecordRepository;
import com.nicko.airecorder.utils.AudioRecorder;

import java.io.File;

public class RecordServiceController {

    private static final String TAG =
            "RecordServiceController";

    private final AudioRecorder audioRecorder;

    private final RecordRepository repository;

    private final Context context;

    public RecordServiceController(
            Context context
    ) {

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

        return audioRecorder
                .pauseRecording();

    }

    public boolean resumeRecording() {

        return audioRecorder
                .resumeRecording();

    }

    public boolean isPaused() {

        return audioRecorder
                .isPaused();

    }

    public boolean isRecording() {

        return audioRecorder
                .isRecording();

    }

    public int getMaxAmplitude() {

        return audioRecorder
                .getMaxAmplitude();

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

        return audioRecorder
                .stopRecording();

    }

    public boolean saveRecord(
            long duration
    ) {

        File file =
                audioRecorder
                        .getOutputFile();

        if (!isValidRecording(file)) {

            deleteInvalidRecording(
                    file
            );

            Log.e(
                    TAG,
                    "Итоговый файл записи невалиден"
            );

            return false;
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

        Log.d(
                TAG,
                "Запись передана в Room: "
                        + file.getAbsolutePath()
        );

        return true;
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

        if (!file.isFile()) {
            return false;
        }

        if (file.length() <= 0) {
            return false;
        }

        MediaMetadataRetriever retriever =
                new MediaMetadataRetriever();

        try {

            retriever.setDataSource(
                    file.getAbsolutePath()
            );

            String mediaDuration =
                    retriever.extractMetadata(
                            MediaMetadataRetriever
                                    .METADATA_KEY_DURATION
                    );

            if (mediaDuration == null) {
                return false;
            }

            long duration =
                    Long.parseLong(
                            mediaDuration
                    );

            return duration > 0;

        } catch (RuntimeException e) {

            Log.e(
                    TAG,
                    "Не удалось проверить файл записи",
                    e
            );

            return false;

        } finally {

            try {

                retriever.release();

            } catch (Exception e) {

                Log.w(
                        TAG,
                        "Ошибка release MediaMetadataRetriever",
                        e
                );

            }

        }

    }

    private void deleteInvalidRecording(
            File file
    ) {

        if (file == null
                || !file.exists()) {

            return;
        }

        if (!file.delete()) {

            Log.w(
                    TAG,
                    "Не удалось удалить повреждённый файл: "
                            + file.getAbsolutePath()
            );

        }

    }

    public void shutdown() {

        repository.shutdown();

    }

}