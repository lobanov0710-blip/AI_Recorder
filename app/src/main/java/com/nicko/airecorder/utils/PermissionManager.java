package com.nicko.airecorder.utils;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class PermissionManager {

    public static final int REQUEST_RECORD_AUDIO = 100;

    private final Activity activity;

    public PermissionManager(Activity activity) {
        this.activity = activity;
    }

    public boolean hasAudioPermission() {

        return ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED;

    }

    public void requestAudioPermission() {

        ActivityCompat.requestPermissions(

                activity,

                new String[]{
                        Manifest.permission.RECORD_AUDIO
                },

                REQUEST_RECORD_AUDIO

        );

    }

}