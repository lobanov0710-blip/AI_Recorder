package com.nicko.airecorder.controller;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

public class RecordTimer {

    public interface Callback {

        int getAmplitude();

        void onTimeChanged(
                long duration
        );

        void onAmplitudeChanged(
                int amplitude
        );
    }

    /*
     * =========================================================
     * UPDATE FREQUENCIES
     * =========================================================
     *
     * Waveform:
     *
     * 100 ms = до 10 updates/sec.
     *
     * Этого достаточно для визуально плавного
     * индикатора микрофона и существенно дешевле
     * прежних 80 ms.
     *
     * Timer:
     *
     * UI отображает только MM:SS.
     * Поэтому broadcast duration отправляется
     * только при фактической смене секунды.
     */
    private static final long AMPLITUDE_INTERVAL_MS =
            100L;

    /*
     * Последняя секунда, уже переданная UI.
     *
     * -1 гарантирует initial callback для 00:00.
     */
    private long lastDispatchedSecond =
            -1L;

    /*
     * =========================================================
     * COMPONENTS
     * =========================================================
     */

    private final Callback callback;

    private final Handler handler =
            new Handler(
                    Looper.getMainLooper()
            );

    private Runnable runnable;

    /*
     * =========================================================
     * TIMER STATE
     * =========================================================
     */

    private long activeDurationMs =
            0L;

    private long segmentStartTime =
            0L;

    private boolean running =
            false;

    private boolean paused =
            false;

    /*
     * =========================================================
     * CONSTRUCTOR
     * =========================================================
     */

    public RecordTimer(
            Callback callback
    ) {

        this.callback =
                callback;
    }

    /*
     * =========================================================
     * START
     * =========================================================
     */

    public void start() {

        /*
         * Полностью очищаем предыдущую session.
         */
        stop();

        activeDurationMs =
                0L;

        segmentStartTime =
                SystemClock.elapsedRealtime();

        lastDispatchedSecond =
                -1L;

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

                        /*
                         * =====================================
                         * DURATION
                         * =====================================
                         *
                         * Duration считается через
                         * elapsedRealtime(), а не через
                         * количество Handler ticks.
                         *
                         * Поэтому задержка main thread
                         * не создаёт накопительного drift.
                         */
                        long duration =
                                getDuration();

                        dispatchTimeIfNeeded(
                                duration
                        );

                        /*
                         * =====================================
                         * AMPLITUDE
                         * =====================================
                         */

                        int amplitude =
                                callback.getAmplitude();

                        /*
                         * AudioRecorder возвращает
                         * нормализованный уровень,
                         * но boundary здесь оставляем
                         * defensively.
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

                        /*
                         * Следующий visual amplitude update.
                         */
                        handler.postDelayed(
                                this,
                                AMPLITUDE_INTERVAL_MS
                        );
                    }
                };

        /*
         * Первый update отправляем сразу.
         *
         * UI получает:
         *
         * time = 00:00
         * amplitude = current value
         */
        handler.post(
                runnable
        );
    }

    /*
     * =========================================================
     * PAUSE
     * =========================================================
     */

    public void pause() {

        if (!running
                || paused) {

            return;
        }

        /*
         * Фиксируем завершённый active segment.
         */
        activeDurationMs +=
                SystemClock.elapsedRealtime()
                        - segmentStartTime;

        paused =
                true;

        if (runnable != null) {

            handler.removeCallbacks(
                    runnable
            );
        }

        /*
         * При PAUSE отправляем точное итоговое
         * duration независимо от second throttling.
         *
         * Это гарантирует синхронное финальное
         * состояние UI.
         */
        callback.onTimeChanged(
                activeDurationMs
        );

        lastDispatchedSecond =
                activeDurationMs / 1000L;
    }

    /*
     * =========================================================
     * RESUME
     * =========================================================
     */

    public void resume() {

        if (!running
                || !paused) {

            return;
        }

        segmentStartTime =
                SystemClock.elapsedRealtime();

        paused =
                false;

        /*
         * Runnable запускаем немедленно.
         *
         * Amplitude обновится сразу.
         * Time broadcast произойдёт только если
         * началась новая отображаемая секунда.
         */
        if (runnable != null) {

            handler.post(
                    runnable
            );
        }
    }

    /*
     * =========================================================
     * GET DURATION
     * =========================================================
     */

    public long getDuration() {

        if (!running
                || paused) {

            return activeDurationMs;
        }

        return activeDurationMs
                + SystemClock.elapsedRealtime()
                - segmentStartTime;
    }

    /*
     * =========================================================
     * STOP
     * =========================================================
     */

    public void stop() {

        /*
         * Если session действительно была активна,
         * фиксируем последний segment.
         */
        if (running
                && !paused) {

            activeDurationMs +=
                    SystemClock.elapsedRealtime()
                            - segmentStartTime;
        }

        running =
                false;

        paused =
                false;

        if (runnable != null) {

            handler.removeCallbacks(
                    runnable
            );

            runnable =
                    null;
        }

        lastDispatchedSecond =
                -1L;
    }

    /*
     * =========================================================
     * THROTTLED TIME DISPATCH
     * =========================================================
     */

    private void dispatchTimeIfNeeded(
            long duration
    ) {

        long currentSecond =
                duration / 1000L;

        /*
         * MainActivity форматирует duration
         * как MM:SS.
         *
         * Пока currentSecond не изменилась,
         * новый broadcast не несёт для UI
         * никакой новой информации.
         */
        if (currentSecond
                == lastDispatchedSecond) {

            return;
        }

        lastDispatchedSecond =
                currentSecond;

        callback.onTimeChanged(
                duration
        );
    }
}