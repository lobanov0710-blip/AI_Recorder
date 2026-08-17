package com.nicko.airecorder.controller;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.nicko.airecorder.common.RecordActions;

public class RecordBroadcastManager {

    private final Context context;

    public RecordBroadcastManager(@NonNull Context context) {

        this.context = context.getApplicationContext();

    }

    public void sendRecordStarted() {

        sendBroadcast(
                RecordActions.ACTION_RECORD_STARTED
        );

    }

    public void sendRecordPaused() {

        sendBroadcast(
                RecordActions.ACTION_RECORD_PAUSED
        );

    }

    public void sendRecordResumed() {

        sendBroadcast(
                RecordActions.ACTION_RECORD_RESUMED
        );

    }

    public void sendRecordStopped() {

        sendBroadcast(
                RecordActions.ACTION_RECORD_STOPPED
        );

    }

    public void sendRecordTime(long duration) {

        Intent intent = createIntent(
                RecordActions.ACTION_RECORD_TIME
        );

        intent.putExtra(
                RecordActions.EXTRA_RECORD_DURATION,
                duration
        );

        context.sendBroadcast(intent);

    }

    public void sendAmplitude(int amplitude) {

        Intent intent = createIntent(
                RecordActions.ACTION_RECORD_AMPLITUDE
        );

        intent.putExtra(
                RecordActions.EXTRA_RECORD_AMPLITUDE,
                amplitude
        );

        context.sendBroadcast(intent);

    }

    private void sendBroadcast(String action) {

        context.sendBroadcast(
                createIntent(action)
        );

    }

    private Intent createIntent(String action) {

        Intent intent = new Intent(action);

        intent.setPackage(
                context.getPackageName()
        );

        return intent;

    }

}