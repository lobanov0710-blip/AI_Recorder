package com.nicko.airecorder.utils;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class PermissionManager {

    public static final int REQUEST_RECORD_AUDIO = 100;

    public static final int REQUEST_POST_NOTIFICATIONS = 101;

    private final Activity activity;

    private boolean notificationPermissionRequested = false;

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

    public boolean hasNotificationPermission() {

        if (Build.VERSION.SDK_INT
                < Build.VERSION_CODES.TIRAMISU) {

            return true;

        }

        return ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED;

    }

    public boolean shouldRequestNotificationPermission() {

        if (Build.VERSION.SDK_INT
                < Build.VERSION_CODES.TIRAMISU) {

            return false;

        }

        if (hasNotificationPermission()) {
            return false;
        }

        if (notificationPermissionRequested) {
            return false;
        }

        return !ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
        );

    }

    public void requestNotificationPermission() {

        if (Build.VERSION.SDK_INT
                < Build.VERSION_CODES.TIRAMISU) {

            return;

        }

        notificationPermissionRequested = true;

        ActivityCompat.requestPermissions(

                activity,

                new String[]{
                        Manifest.permission.POST_NOTIFICATIONS
                },

                REQUEST_POST_NOTIFICATIONS

        );

    }

}