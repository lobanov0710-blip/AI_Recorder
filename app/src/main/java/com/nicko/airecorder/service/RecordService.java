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

    private long recordStartTime;

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

                        this,

                        new RecordTimer.Callback() {

                            @Override
                            public int getAmplitude() {

                                return controller.getMaxAmplitude();

                            }

                            @Override
                            public void onTimeChanged(long duration) {

                                broadcastManager.sendRecordTime(duration);

                            }

                            @Override
                            public void onAmplitudeChanged(int amplitude) {

                                broadcastManager.sendAmplitude(amplitude);

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

        String action = intent.getAction();

        if (RecordActions.ACTION_START.equals(action)) {

            startRecording();

        } else if (RecordActions.ACTION_PAUSE.equals(action)) {

            pauseRecording();

        } else if (RecordActions.ACTION_RESUME.equals(action)) {

            resumeRecording();

        } else if (RecordActions.ACTION_STOP.equals(action)) {

            stopRecording();

        }

        return START_NOT_STICKY;

    }

    private boolean canHandleRecording() {

        return isRecording;

    }

    private void startRecording() {

        if (isRecording) {
            return;
        }

        try {

            startForeground(

                    NotificationController.NOTIFICATION_ID,

                    notificationController.buildNotification(
                            getString(R.string.record_preparing)
                    )

            );

            controller.startRecording();

            isRecording = true;

            recordStartTime =
                    System.currentTimeMillis();

            recordTimer.start(recordStartTime);

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

        if (!isRecording || controller.isPaused()) {
            return;
        }

        if (controller.pauseRecording()) {

            notifyRecordingPaused();

        }

    }

    private void resumeRecording() {

        if (!isRecording || !controller.isPaused()) {
            return;
        }

        if (controller.resumeRecording()) {

            notifyRecordingResumed();

        }

    }

    private void stopRecording() {

        if (!isRecording) {
            return;
        }

        try {

            boolean success =
                    controller.stopRecording();

            if (success) {

                controller.saveRecord(recordStartTime);

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

    private void notifyRecordingStarted() {

        updateRecordingNotification(
                getString(R.string.record_in_progress)
        );

        broadcastManager.sendRecordStarted();

    }

    private void notifyRecordingPaused() {

        updateRecordingNotification(
                getString(R.string.record_paused)
        );

        broadcastManager.sendRecordPaused();

    }

    private void notifyRecordingResumed() {

        updateRecordingNotification(
                getString(R.string.record_in_progress)
        );

        broadcastManager.sendRecordResumed();

    }

    private void notifyRecordingStopped() {

        broadcastManager.sendRecordStopped();

    }

    private void updateRecordingNotification(String text) {

        notificationController.updateNotification(text);

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
    public IBinder onBind(Intent intent) {

        return null;

    }

}