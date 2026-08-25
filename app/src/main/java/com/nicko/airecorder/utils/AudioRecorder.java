package com.nicko.airecorder.utils;

import android.media.MediaRecorder;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class AudioRecorder {

    private static final String TAG =
            "AudioRecorder";

    private static final int COPY_BUFFER_SIZE =
            64 * 1024;

    private final List<File> segmentFiles =
            new ArrayList<>();

    private final AudioSegmentMerger segmentMerger =
            new AudioSegmentMerger();

    private MediaRecorder recorder;

    private File outputFile;

    private File currentSegmentFile;

    private boolean recording = false;

    private boolean paused = false;

    private int segmentIndex = 0;

    public AudioRecorder() {
    }

    public void startRecording(
            @NonNull File outputFile
    ) throws IOException {

        if (recording) {
            return;
        }

        releaseRecorderOnly();

        deleteTemporarySegments();

        this.outputFile =
                outputFile;

        segmentIndex = 0;

        recording = false;

        paused = false;

        File parent =
                outputFile.getParentFile();

        if (parent == null) {

            throw new IOException(
                    "Не удалось определить папку записи"
            );
        }

        if (!parent.exists()
                && !parent.mkdirs()) {

            throw new IOException(
                    "Не удалось создать папку записи"
            );
        }

        if (outputFile.exists()
                && !outputFile.delete()) {

            throw new IOException(
                    "Не удалось удалить старый итоговый файл"
            );
        }

        try {

            startNewSegment();

            recording = true;

        } catch (IOException | RuntimeException e) {

            recording = false;

            paused = false;

            releaseRecorderOnly();

            deleteTemporarySegments();

            throw e;
        }
    }

    public boolean pauseRecording() {

        if (!recording
                || paused
                || recorder == null) {

            return false;
        }

        if (!finishCurrentSegment()) {

            Log.e(
                    TAG,
                    "Не удалось завершить сегмент при Pause"
            );

            return false;
        }

        paused = true;

        return true;
    }

    public boolean resumeRecording() {

        if (!recording
                || !paused) {

            return false;
        }

        try {

            startNewSegment();

            paused = false;

            return true;

        } catch (IOException | RuntimeException e) {

            Log.e(
                    TAG,
                    "Не удалось начать сегмент после Resume",
                    e
            );

            paused = true;

            return false;
        }
    }

    public boolean stopRecording() {

        if (!recording) {

            releaseRecorderOnly();

            return false;
        }

        boolean segmentSuccess =
                true;

        /*
         * При Stop во время RECORDING
         * закрываем текущий сегмент.
         *
         * При Stop во время PAUSED
         * сегмент уже был закрыт в pauseRecording().
         */
        if (!paused) {

            segmentSuccess =
                    finishCurrentSegment();
        }

        if (!segmentSuccess) {

            Log.e(
                    TAG,
                    "Не удалось завершить последний сегмент"
            );

            recording = false;

            paused = false;

            releaseRecorderOnly();

            deleteTemporarySegments();

            return false;
        }

        boolean outputSuccess =
                buildFinalOutput();

        recording = false;

        paused = false;

        releaseRecorderOnly();

        if (outputSuccess) {

            deleteTemporarySegments();

        } else {

            Log.e(
                    TAG,
                    "Не удалось сформировать итоговый файл"
            );

            deleteTemporarySegments();
        }

        return outputSuccess;
    }

    private void startNewSegment()
            throws IOException {

        if (outputFile == null) {

            throw new IOException(
                    "Итоговый файл не задан"
            );
        }

        File parent =
                outputFile.getParentFile();

        if (parent == null) {

            throw new IOException(
                    "Папка записи отсутствует"
            );
        }

        String outputName =
                outputFile.getName();

        int extensionIndex =
                outputName.lastIndexOf('.');

        String baseName;

        if (extensionIndex > 0) {

            baseName =
                    outputName.substring(
                            0,
                            extensionIndex
                    );

        } else {

            baseName =
                    outputName;
        }

        File segmentFile =
                new File(
                        parent,
                        baseName
                                + "_segment_"
                                + segmentIndex
                                + ".m4a"
                );

        if (segmentFile.exists()
                && !segmentFile.delete()) {

            throw new IOException(
                    "Не удалось удалить старый сегмент"
            );
        }

        MediaRecorder newRecorder =
                new MediaRecorder();

        try {

            newRecorder.setAudioSource(
                    MediaRecorder.AudioSource.MIC
            );

            newRecorder.setOutputFormat(
                    MediaRecorder.OutputFormat.MPEG_4
            );

            newRecorder.setAudioEncoder(
                    MediaRecorder.AudioEncoder.AAC
            );

            newRecorder.setAudioEncodingBitRate(
                    128000
            );

            newRecorder.setAudioSamplingRate(
                    44100
            );

            newRecorder.setOutputFile(
                    segmentFile.getAbsolutePath()
            );

            newRecorder.prepare();

            newRecorder.start();

            recorder =
                    newRecorder;

            currentSegmentFile =
                    segmentFile;

            segmentIndex++;

            Log.d(
                    TAG,
                    "Начат сегмент: "
                            + segmentFile.getName()
            );

        } catch (IOException | RuntimeException e) {

            safeRelease(
                    newRecorder
            );

            deleteFileQuietly(
                    segmentFile
            );

            throw e;
        }
    }

    private boolean finishCurrentSegment() {

        if (recorder == null
                || currentSegmentFile == null) {

            return false;
        }

        File segment =
                currentSegmentFile;

        boolean success = false;

        try {

            recorder.stop();

            success = true;

        } catch (RuntimeException e) {

            Log.e(
                    TAG,
                    "Ошибка остановки сегмента",
                    e
            );

        } finally {

            releaseRecorderOnly();

            currentSegmentFile = null;
        }

        if (!success) {

            deleteFileQuietly(
                    segment
            );

            return false;
        }

        if (!isValidSegment(
                segment
        )) {

            Log.e(
                    TAG,
                    "Сегмент невалиден: "
                            + segment.getAbsolutePath()
            );

            deleteFileQuietly(
                    segment
            );

            return false;
        }

        segmentFiles.add(
                segment
        );

        Log.d(
                TAG,
                "Завершён сегмент: "
                        + segment.getName()
        );

        return true;
    }

    private boolean buildFinalOutput() {

        if (outputFile == null
                || segmentFiles.isEmpty()) {

            return false;
        }

        if (segmentFiles.size() == 1) {

            return moveSingleSegment(
                    segmentFiles.get(0),
                    outputFile
            );
        }

        Log.d(
                TAG,
                "Объединение сегментов: "
                        + segmentFiles.size()
        );

        return segmentMerger.merge(
                segmentFiles,
                outputFile
        );
    }

    private boolean moveSingleSegment(
            File source,
            File destination
    ) {

        if (!isValidSegment(
                source
        )) {

            return false;
        }

        if (destination.exists()
                && !destination.delete()) {

            return false;
        }

        if (source.renameTo(
                destination
        )) {

            return destination.exists()
                    && destination.isFile()
                    && destination.length() > 0L;
        }

        FileInputStream input =
                null;

        FileOutputStream output =
                null;

        try {

            input =
                    new FileInputStream(
                            source
                    );

            output =
                    new FileOutputStream(
                            destination
                    );

            byte[] buffer =
                    new byte[
                            COPY_BUFFER_SIZE
                            ];

            int read;

            while ((read =
                    input.read(buffer)) != -1) {

                output.write(
                        buffer,
                        0,
                        read
                );
            }

            output.flush();

        } catch (IOException e) {

            Log.e(
                    TAG,
                    "Ошибка копирования сегмента",
                    e
            );

            deleteFileQuietly(
                    destination
            );

            return false;

        } finally {

            if (input != null) {

                try {

                    input.close();

                } catch (IOException ignored) {
                }
            }

            if (output != null) {

                try {

                    output.close();

                } catch (IOException ignored) {
                }
            }
        }

        if (!destination.exists()
                || !destination.isFile()
                || destination.length() <= 0L) {

            deleteFileQuietly(
                    destination
            );

            return false;
        }

        deleteFileQuietly(
                source
        );

        return true;
    }

    private boolean isValidSegment(
            File file
    ) {

        return file != null
                && file.exists()
                && file.isFile()
                && file.length() > 0L;
    }

    private void releaseRecorderOnly() {

        if (recorder == null) {
            return;
        }

        safeRelease(
                recorder
        );

        recorder = null;
    }

    private void safeRelease(
            MediaRecorder mediaRecorder
    ) {

        if (mediaRecorder == null) {
            return;
        }

        try {

            mediaRecorder.reset();

        } catch (Exception e) {

            Log.w(
                    TAG,
                    "Ошибка MediaRecorder.reset()",
                    e
            );
        }

        try {

            mediaRecorder.release();

        } catch (Exception e) {

            Log.w(
                    TAG,
                    "Ошибка MediaRecorder.release()",
                    e
            );
        }
    }

    private void deleteTemporarySegments() {

        for (File segment
                : segmentFiles) {

            deleteFileQuietly(
                    segment
            );
        }

        segmentFiles.clear();

        if (currentSegmentFile != null) {

            deleteFileQuietly(
                    currentSegmentFile
            );

            currentSegmentFile = null;
        }
    }

    private void deleteFileQuietly(
            File file
    ) {

        if (file == null
                || !file.exists()) {

            return;
        }

        if (!file.delete()) {

            Log.w(
                    TAG,
                    "Не удалось удалить файл: "
                            + file.getAbsolutePath()
            );
        }
    }

    public int getMaxAmplitude() {

        if (!recording
                || paused
                || recorder == null) {

            return 0;
        }

        try {

            return recorder
                    .getMaxAmplitude();

        } catch (Exception e) {

            return 0;
        }
    }

    public File getOutputFile() {

        return outputFile;
    }

    public boolean hasValidRecording() {

        return outputFile != null
                && outputFile.exists()
                && outputFile.isFile()
                && outputFile.length() > 0L;
    }

    public boolean isRecording() {

        return recording;
    }

    public boolean isPaused() {

        return paused;
    }

    public String getFilePath() {

        if (outputFile == null) {
            return null;
        }

        return outputFile
                .getAbsolutePath();
    }
}