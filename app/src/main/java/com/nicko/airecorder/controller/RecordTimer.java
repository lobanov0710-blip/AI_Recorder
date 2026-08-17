package com.nicko.airecorder.controller;

import android.os.Handler;
import android.os.Looper;

public class RecordTimer {

    public interface Callback {

        int getAmplitude();

        void onTimeChanged(long duration);

        void onAmplitudeChanged(int amplitude);

    }

    private final Callback callback;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private Runnable runnable;

    private long recordStartTime;
    private static final long UPDATE_INTERVAL_MS = 80L;

    public RecordTimer(
            Callback callback
    ) {

        this.callback = callback;

    }

    public void start(long startTime) {

        stop();

        recordStartTime = startTime;

        runnable = new Runnable() {

            @Override
            public void run() {

                long duration =
                        System.currentTimeMillis() - recordStartTime;

                callback.onTimeChanged(duration);

                int amplitude = callback.getAmplitude();

                amplitude = Math.min(
                        amplitude / 327,
                        100
                );

                callback.onAmplitudeChanged(amplitude);

                handler.postDelayed(
                        this,
                        UPDATE_INTERVAL_MS
                );

            }

        };

        handler.post(runnable);

    }

    public void stop() {

        if (runnable != null) {

            handler.removeCallbacks(runnable);

            runnable = null;

        }

    }

}