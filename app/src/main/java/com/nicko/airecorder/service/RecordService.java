package com.nicko.airecorder.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

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

    private boolean isRecording = false;

    private RecordServiceController controller;

    private RecordTimer recordTimer;

    private RecordBroadcastManager broadcastManager;

    private NotificationController notificationController;

    @Override
    public void onCreate() {

        super.onCreate();

        controller =
                new RecordServiceController(this);

        broadcastManager =
                new RecordBroadcastManager(this);

        notificationController =
                new NotificationController(this);

        recordTimer =
                new RecordTimer(

                        new RecordTimer.Callback() {

                            @Override
                            public int getAmplitude() {

                                return controller
                                        .getMaxAmplitude();

                            }

                            @Override
                            public void onTimeChanged(
                                    long duration
                            ) {

                                broadcastManager
                                        .sendRecordTime(
                                                duration
                                        );

                            }

                            @Override
                            public void onAmplitudeChanged(
                                    int amplitude
                            ) {

                                broadcastManager
                                        .sendAmplitude(
                                                amplitude
                                        );

                            }

                        }

                );

    }

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

            sendCurrentState(startId);

        }

        return START_NOT_STICKY;

    }

    private void startRecording() {

        if (isRecording) {
            return;
        }

        try {

            startForeground(

                    NotificationController.NOTIFICATION_ID,

                    notificationController
                            .buildNotification(
                                    getString(
                                            R.string.record_preparing
                                    )
                            )

            );

            controller.startRecording();

            isRecording = true;

            recordTimer.start();

            notifyRecordingStarted();

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Ошибка старта записи",
                    e
            );

            isRecording = false;

            recordTimer.stop();

            stopForeground(true);

            stopSelf();

        }

    }

    private void pauseRecording() {

        if (!isRecording
                || controller.isPaused()) {

            return;

        }

        if (controller.pauseRecording()) {

            recordTimer.pause();

            notifyRecordingPaused();

        }

    }

    private void resumeRecording() {

        if (!isRecording
                || !controller.isPaused()) {

            return;

        }

        if (controller.resumeRecording()) {

            recordTimer.resume();

            notifyRecordingResumed();

        }

    }

    private void stopRecording() {

        if (!isRecording) {
            return;
        }

        try {

            recordTimer.stop();

            long duration =
                    recordTimer.getDuration();

            boolean success =
                    controller.stopRecording();

            if (success) {

                controller.saveRecord(
                        duration
                );

            }

            notifyRecordingStopped();

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Ошибка остановки записи",
                    e
            );

        } finally {

            isRecording = false;

            recordTimer.stop();

            stopForeground(true);

            stopSelf();

        }

    }

    private void sendCurrentState(
            int startId
    ) {

        if (!isRecording) {

            broadcastManager
                    .sendRecordStopped();

            broadcastManager
                    .sendRecordTime(0L);

            /*
             * ACTION_REQUEST_STATE может создать сервис,
             * если записи сейчас нет.
             *
             * После ответа пустой сервис нам не нужен.
             */
            stopSelf(startId);

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

    private void notifyRecordingStarted() {

        updateRecordingNotification(
                getString(
                        R.string.record_in_progress
                )
        );

        broadcastManager
                .sendRecordStarted();

    }

    private void notifyRecordingPaused() {

        updateRecordingNotification(
                getString(
                        R.string.record_paused
                )
        );

        broadcastManager
                .sendRecordPaused();

    }

    private void notifyRecordingResumed() {

        updateRecordingNotification(
                getString(
                        R.string.record_in_progress
                )
        );

        broadcastManager
                .sendRecordResumed();

    }

    private void notifyRecordingStopped() {

        broadcastManager
                .sendRecordStopped();

    }

    private void updateRecordingNotification(
            String text
    ) {

        notificationController
                .updateNotification(text);

    }

    @Override
    public void onDestroy() {

        if (recordTimer != null) {

            recordTimer.stop();

        }

        if (controller != null) {

            controller.shutdown();

        }

        isRecording = false;

        super.onDestroy();

    }

    @Nullable
    @Override
    public IBinder onBind(
            Intent intent
    ) {

        return null;

    }

}