package com.nicko.airecorder.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.nicko.airecorder.common.RecordActions;

public class RecordReceiver extends BroadcastReceiver {

    public interface Callback {

        void onRecordStarted();

        void onRecordPaused();

        void onRecordResumed();

        void onRecordStopped();

        void onRecordTime(long duration);

        void onAmplitude(int amplitude);

    }

    private final Callback callback;

    public RecordReceiver(@NonNull Callback callback) {

        this.callback = callback;

    }

    @Override
    public void onReceive(Context context, Intent intent) {

        if (intent == null) {
            return;
        }

        String action = intent.getAction();

        if (action == null) {
            return;
        }

        switch (action) {

            case RecordActions.ACTION_RECORD_STARTED:

                callback.onRecordStarted();
                break;

            case RecordActions.ACTION_RECORD_PAUSED:

                callback.onRecordPaused();
                break;

            case RecordActions.ACTION_RECORD_RESUMED:

                callback.onRecordResumed();
                break;

            case RecordActions.ACTION_RECORD_STOPPED:

                callback.onRecordStopped();
                break;

            case RecordActions.ACTION_RECORD_TIME:

                callback.onRecordTime(

                        intent.getLongExtra(
                                RecordActions.EXTRA_RECORD_DURATION,
                                0L
                        )

                );

                break;

            case RecordActions.ACTION_RECORD_AMPLITUDE:

                callback.onAmplitude(

                        intent.getIntExtra(
                                RecordActions.EXTRA_RECORD_AMPLITUDE,
                                0
                        )

                );

                break;

        }

    }

}