package com.nicko.airecorder.service;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;

import com.nicko.airecorder.R;
import com.nicko.airecorder.common.RecordActions;
import com.nicko.airecorder.controller.NotificationController;
import com.nicko.airecorder.controller.RecordBroadcastManager;
import com.nicko.airecorder.controller.RecordServiceController;
import com.nicko.airecorder.controller.RecordTimer;

public class RecordService extends Service {

    private static final String TAG =
            "RecordService";

    public static final String CHANNEL_ID =
            "record_channel";

    /*
     * =========================================================
     * STATE
     * =========================================================
     */

    private boolean isRecording =
            false;

    /*
     * =========================================================
     * COMPONENTS
     * =========================================================
     */

    private RecordServiceController controller;

    private RecordTimer recordTimer;

    private RecordBroadcastManager broadcastManager;

    private NotificationController notificationController;

    /*
     * =========================================================
     * SERVICE CREATE
     * =========================================================
     */

    @Override
    public void onCreate() {

        super.onCreate();

        controller =
                new RecordServiceController(
                        this
                );

        broadcastManager =
                new RecordBroadcastManager(
                        this
                );

        notificationController =
                new NotificationController(
                        this
                );

        recordTimer =
                new RecordTimer(

                        new RecordTimer.Callback() {

                            @Override
                            public int getAmplitude() {

                                if (controller == null) {
                                    return 0;
                                }

                                return controller
                                        .getMaxAmplitude();
                            }

                            @Override
                            public void onTimeChanged(
                                    long duration
                            ) {

                                if (broadcastManager == null) {
                                    return;
                                }

                                broadcastManager
                                        .sendRecordTime(
                                                duration
                                        );
                            }

                            @Override
                            public void onAmplitudeChanged(
                                    int amplitude
                            ) {

                                if (broadcastManager == null) {
                                    return;
                                }

                                broadcastManager
                                        .sendAmplitude(
                                                amplitude
                                        );
                            }
                        }
                );
    }

    /*
     * =========================================================
     * COMMANDS
     * =========================================================
     */

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {

        if (intent == null) {

            return START_NOT_STICKY;
        }

        String action =
                intent.getAction();

        if (RecordActions.ACTION_START
                .equals(action)) {

            startRecording();

        } else if (RecordActions.ACTION_PAUSE
                .equals(action)) {

            pauseRecording();

        } else if (RecordActions.ACTION_RESUME
                .equals(action)) {

            resumeRecording();

        } else if (RecordActions.ACTION_STOP
                .equals(action)) {

            stopRecording();

        } else if (RecordActions.ACTION_REQUEST_STATE
                .equals(action)) {

            sendCurrentState(
                    startId
            );
        }

        return START_NOT_STICKY;
    }

    /*
     * =========================================================
     * START RECORDING
     * =========================================================
     */

    private void startRecording() {

        if (isRecording) {

            return;
        }

        if (controller == null
                || recordTimer == null
                || notificationController == null) {

            Log.e(
                    TAG,
                    "Компоненты RecordService не инициализированы"
            );

            stopSelf();

            return;
        }

        try {

            /*
             * Сначала переводим Service в foreground.
             *
             * Это должно произойти до длительной
             * подготовки recording pipeline.
             */
            promoteToForeground();

            controller.startRecording();

            isRecording =
                    true;

            recordTimer.start();

            notifyRecordingStarted();

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Ошибка старта записи",
                    e
            );

            isRecording =
                    false;

            recordTimer.stop();

            removeFromForeground();

            stopSelf();
        }
    }

    /*
     * =========================================================
     * FOREGROUND PROMOTION
     * =========================================================
     *
     * Recording использует microphone foreground-service type.
     *
     * ServiceCompat:
     *
     * - на Android < 10 использует старый startForeground();
     * - на Android 10+ передаёт foregroundServiceType;
     * - позволяет сохранить единый код для API 24–36.
     */
    private void promoteToForeground() {

        if (notificationController == null) {

            throw new IllegalStateException(
                    "NotificationController не инициализирован"
            );
        }

        ServiceCompat.startForeground(

                this,

                NotificationController.NOTIFICATION_ID,

                notificationController
                        .buildNotification(
                                getString(
                                        R.string.record_preparing
                                )
                        ),

                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        );
    }

    /*
     * =========================================================
     * PAUSE RECORDING
     * =========================================================
     */

    /*
     * =========================================================
     * FOREGROUND REMOVAL
     * =========================================================
     */

    private void removeFromForeground() {

        ServiceCompat.stopForeground(
                this,
                ServiceCompat.STOP_FOREGROUND_REMOVE
        );
    }

    private void pauseRecording() {

        if (!isRecording
                || controller == null
                || recordTimer == null) {

            return;
        }

        if (controller.isPaused()) {

            return;
        }

        boolean pauseSuccess =
                controller.pauseRecording();

        if (!pauseSuccess) {

            Log.w(
                    TAG,
                    "AudioRecorder не смог перейти в Pause"
            );

            return;
        }

        recordTimer.pause();

        notifyRecordingPaused();
    }

    /*
     * =========================================================
     * RESUME RECORDING
     * =========================================================
     */

    private void resumeRecording() {

        if (!isRecording
                || controller == null
                || recordTimer == null) {

            return;
        }

        if (!controller.isPaused()) {

            return;
        }

        boolean resumeSuccess =
                controller.resumeRecording();

        if (!resumeSuccess) {

            Log.w(
                    TAG,
                    "AudioRecorder не смог продолжить запись"
            );

            return;
        }

        recordTimer.resume();

        notifyRecordingResumed();
    }

    /*
     * =========================================================
     * STOP RECORDING
     * =========================================================
     */

    private void stopRecording() {

        if (!isRecording
                || controller == null
                || recordTimer == null) {

            return;
        }

        /*
         * Stop больше не принимаем повторно.
         *
         * Это также предотвращает двойную
         * финализацию одной RecorderSession.
         */
        isRecording =
                false;

        /*
         * Сначала останавливаем UI timer.
         *
         * Он больше не должен отправлять
         * amplitude/time broadcasts.
         */
        recordTimer.stop();

        boolean stopSuccess =
                false;

        boolean saveSuccess =
                false;

        try {

            /*
             * =================================================
             * AUDIO FINALIZATION
             * =================================================
             *
             * stopRecording() возвращает true ТОЛЬКО когда:
             *
             * - capture worker завершился;
             * - encoder worker завершился;
             * - MediaCodec корректно завершён;
             * - MediaMuxer корректно завершён;
             * - итоговый файл существует.
             */
            stopSuccess =
                    controller.stopRecording();

            if (!stopSuccess) {

                Log.e(
                        TAG,
                        "RecorderSession не была корректно финализирована"
                );

            } else {

                /*
                 * =============================================
                 * DATABASE SAVE
                 * =============================================
                 *
                 * КРИТИЧЕСКОЕ ПРАВИЛО P0.1:
                 *
                 * Room insert выполняется только после
                 * успешной полной финализации M4A.
                 */
                saveSuccess =
                        controller.saveRecord();

                if (!saveSuccess) {

                    Log.e(
                            TAG,
                            "Финализированный файл не удалось сохранить в Room"
                    );
                }
            }

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Ошибка остановки записи",
                    e
            );

            stopSuccess =
                    false;

            saveSuccess =
                    false;

        } finally {

            /*
             * =================================================
             * UI STATE
             * =================================================
             *
             * Для UI recording lifecycle завершён независимо
             * от того, был ли файл успешно сохранён.
             */
            notifyRecordingStopped();

            /*
             * На всякий случай timer остаётся остановленным.
             */
            recordTimer.stop();

            /*
             * Foreground notification больше не нужна.
             */
            removeFromForeground();

            /*
             * Завершаем Service.
             *
             * Если worker внутри AudioRecorder пережил timeout,
             * он работает только со своей RecorderSession и
             * больше не может затронуть ресурсы следующей session.
             */
            stopSelf();
        }

        /*
         * Отдельный итоговый log удобен для regression test.
         */
        Log.d(
                TAG,
                "Stop result: stopSuccess="
                        + stopSuccess
                        + ", saveSuccess="
                        + saveSuccess
        );
    }

    /*
     * =========================================================
     * REQUEST CURRENT STATE
     * =========================================================
     */

    private void sendCurrentState(
            int startId
    ) {

        if (broadcastManager == null
                || recordTimer == null
                || controller == null) {

            stopSelf(
                    startId
            );

            return;
        }

        if (!isRecording) {

            broadcastManager
                    .sendRecordStopped();

            broadcastManager
                    .sendRecordTime(
                            0L
                    );

            /*
             * ACTION_REQUEST_STATE может создать Service,
             * когда активной записи нет.
             *
             * Такой пустой Service сразу завершаем.
             */
            stopSelf(
                    startId
            );

            return;
        }

        if (controller.isPaused()) {

            broadcastManager
                    .sendRecordPaused();

        } else {

            broadcastManager
                    .sendRecordStarted();
        }

        broadcastManager
                .sendRecordTime(
                        recordTimer.getDuration()
                );
    }

    /*
     * =========================================================
     * RECORDING STARTED
     * =========================================================
     */

    private void notifyRecordingStarted() {

        updateRecordingNotification(
                getString(
                        R.string.record_in_progress
                )
        );

        if (broadcastManager != null) {

            broadcastManager
                    .sendRecordStarted();
        }
    }

    /*
     * =========================================================
     * RECORDING PAUSED
     * =========================================================
     */

    private void notifyRecordingPaused() {

        updateRecordingNotification(
                getString(
                        R.string.record_paused
                )
        );

        if (broadcastManager != null) {

            broadcastManager
                    .sendRecordPaused();
        }
    }

    /*
     * =========================================================
     * RECORDING RESUMED
     * =========================================================
     */

    private void notifyRecordingResumed() {

        updateRecordingNotification(
                getString(
                        R.string.record_in_progress
                )
        );

        if (broadcastManager != null) {

            broadcastManager
                    .sendRecordResumed();
        }
    }

    /*
     * =========================================================
     * RECORDING STOPPED
     * =========================================================
     */

    private void notifyRecordingStopped() {

        if (broadcastManager == null) {

            return;
        }

        broadcastManager
                .sendRecordStopped();
    }

    /*
     * =========================================================
     * NOTIFICATION UPDATE
     * =========================================================
     */

    private void updateRecordingNotification(
            String text
    ) {

        if (notificationController == null) {

            return;
        }

        notificationController
                .updateNotification(
                        text
                );
    }

    /*
     * =========================================================
     * SERVICE DESTROY
     * =========================================================
     */

    @Override
    public void onDestroy() {

        /*
         * Timer никогда не должен оставлять
         * callbacks после уничтожения Service.
         */
        if (recordTimer != null) {

            recordTimer.stop();
        }

        /*
         * Repository executor закрывается.
         *
         * AudioRecorder workers при этом владеют
         * своими RecorderSession независимо.
         */
        if (controller != null) {

            controller.shutdown();
        }

        isRecording =
                false;

        super.onDestroy();
    }

    /*
     * =========================================================
     * BIND
     * =========================================================
     */

    @Nullable
    @Override
    public IBinder onBind(
            Intent intent
    ) {

        return null;
    }
}