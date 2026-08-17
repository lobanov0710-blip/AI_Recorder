package com.nicko.airecorder.utils;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;

public class AudioRecorder {

    private static final String TAG = "AudioRecorder";

    private final Context context;

    private MediaRecorder recorder;

    private String filePath;

    private File outputFile;

    private boolean recording = false;

    private boolean paused = false;

    public AudioRecorder(Context context) {

        this.context = context.getApplicationContext();

    }

    public void startRecording(@NonNull File outputFile) throws IOException {

        if (recording) {
            return;
        }

        this.outputFile = outputFile;

        filePath = null;

        File parent = outputFile.getParentFile();

        if (parent != null && !parent.exists()) {

            if (!parent.mkdirs()) {

                Log.w(TAG, "Не удалось создать папку");

            }
        }

        if (outputFile.exists() && !outputFile.delete()) {

            Log.w(TAG, "Не удалось удалить старый файл");

        }

        try {

            recorder = new MediaRecorder();

            recorder.setAudioSource(
                    MediaRecorder.AudioSource.MIC
            );

            recorder.setOutputFormat(
                    MediaRecorder.OutputFormat.MPEG_4
            );

            recorder.setAudioEncoder(
                    MediaRecorder.AudioEncoder.AAC
            );

            recorder.setAudioEncodingBitRate(
                    128000
            );

            recorder.setAudioSamplingRate(
                    44100
            );

            recorder.setOutputFile(
                    outputFile.getAbsolutePath()
            );

            recorder.prepare();

            recorder.start();

            recording = true;

            paused = false;

            filePath = outputFile.getAbsolutePath();

        } catch (IOException | RuntimeException e) {

            Log.e(
                    TAG,
                    "Ошибка запуска записи",
                    e
            );

            releaseRecorder();

            throw e;
        }
    }

    public boolean pauseRecording() {

        if (!recording || paused || recorder == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }

        try {

            recorder.pause();

            paused = true;

            return true;

        } catch (Exception e) {

            Log.e(TAG, "Ошибка паузы записи", e);

            return false;

        }

    }

    public boolean resumeRecording() {

        if (!recording || !paused || recorder == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }

        try {

            recorder.resume();

            paused = false;

            return true;

        } catch (Exception e) {

            Log.e(TAG, "Ошибка продолжения записи", e);

            return false;

        }

    }

    public boolean stopRecording() {

        if (!recording) {

            releaseRecorder();

            return false;
        }

        boolean success = false;

        try {

            recorder.stop();

            success = true;

        } catch (RuntimeException e) {

            Log.e(
                    TAG,
                    "Ошибка остановки записи",
                    e
            );

            if (outputFile != null && outputFile.exists()) {

                //noinspection ResultOfMethodCallIgnored
                outputFile.delete();

            }

        } finally {

            releaseRecorder();

        }
        return success;
    }
    private void releaseRecorder() {

        if (recorder != null) {

            try {

                recorder.reset();

            } catch (Exception e) {

                Log.w(
                        TAG,
                        "Ошибка reset()",
                        e
                );

            }

            try {

                recorder.release();

            } catch (Exception e) {

                Log.w(
                        TAG,
                        "Ошибка release()",
                        e
                );

            }

            recorder = null;

        }

        recording = false;

        paused = false;

        filePath = null;

        // outputFile специально НЕ очищаем.
    }

    public int getMaxAmplitude() {

        if (!recording || paused || recorder == null) {
            return 0;
        }

        try {

            return recorder.getMaxAmplitude();

        } catch (Exception e) {return 0;}

    }
    public File getOutputFile() {return outputFile;}
    public boolean hasValidRecording() {

        return outputFile != null
                && outputFile.exists()
                && outputFile.length() > 0;

    }
    public boolean isRecording() {return recording;}
    public boolean isPaused() {return paused;}

    public String getFilePath() {return filePath;}
}