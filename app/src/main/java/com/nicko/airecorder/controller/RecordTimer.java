package com.nicko.airecorder.controller;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

public class RecordTimer {

    public interface Callback {

        int getAmplitude();

        void onTimeChanged(long duration);

        void onAmplitudeChanged(int amplitude);

    }

    private static final long UPDATE_INTERVAL_MS = 80L;

    private final Callback callback;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private Runnable runnable;

    private long activeDurationMs = 0L;

    private long segmentStartTime = 0L;

    private boolean running = false;

    private boolean paused = false;

    public RecordTimer(
            Callback callback
    ) {

        this.callback = callback;

    }

    public void start() {

        stop();

        activeDurationMs =
                0L;

        segmentStartTime =
                SystemClock.elapsedRealtime();

        running =
                true;

        paused =
                false;

        runnable =
                new Runnable() {

                    @Override
                    public void run() {

                        if (!running
                                || paused) {

                            return;
                        }

                        callback.onTimeChanged(
                                getDuration()
                        );

                        int amplitude =
                                callback.getAmplitude();

                        /*
                         * AudioRecorder уже возвращает
                         * нормализованный RMS/dB уровень
                         * в диапазоне 0...100.
                         */
                        amplitude =
                                Math.max(
                                        0,
                                        Math.min(
                                                100,
                                                amplitude
                                        )
                                );

                        callback.onAmplitudeChanged(
                                amplitude
                        );

                        handler.postDelayed(
                                this,
                                UPDATE_INTERVAL_MS
                        );
                    }
                };

        handler.post(
                runnable
        );
    }

    public void pause() {

        if (!running || paused) {
            return;
        }

        activeDurationMs +=
                SystemClock.elapsedRealtime()
                        - segmentStartTime;

        paused = true;

        if (runnable != null) {

            handler.removeCallbacks(
                    runnable
            );

        }

        callback.onTimeChanged(
                activeDurationMs
        );

    }

    public void resume() {

        if (!running || !paused) {
            return;
        }

        segmentStartTime =
                SystemClock.elapsedRealtime();

        paused = false;

        if (runnable != null) {

            handler.post(runnable);

        }

    }

    public long getDuration() {

        if (!running || paused) {

            return activeDurationMs;

        }

        return activeDurationMs
                + SystemClock.elapsedRealtime()
                - segmentStartTime;

    }

    public void stop() {

        if (running && !paused) {

            activeDurationMs +=
                    SystemClock.elapsedRealtime()
                            - segmentStartTime;

        }

        running = false;

        paused = false;

        if (runnable != null) {

            handler.removeCallbacks(
                    runnable
            );

            runnable = null;

        }

    }

}