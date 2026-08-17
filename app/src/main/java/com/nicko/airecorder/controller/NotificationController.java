package com.nicko.airecorder.controller;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.nicko.airecorder.R;
import com.nicko.airecorder.service.RecordService;

public class NotificationController {

    public static final int NOTIFICATION_ID = 1;

    private final Context context;

    public NotificationController(Context context) {

        this.context = context.getApplicationContext();

        createNotificationChannel();

    }

    public void createNotificationChannel() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager manager =
                context.getSystemService(NotificationManager.class);

        if (manager == null) {
            return;
        }

        NotificationChannel channel =
                new NotificationChannel(

                        RecordService.CHANNEL_ID,

                        context.getString(
                                R.string.notification_channel_name
                        ),

                        NotificationManager.IMPORTANCE_LOW

                );

        manager.createNotificationChannel(channel);

    }

    public Notification buildNotification(String text) {

        return new NotificationCompat.Builder(

                context,

                RecordService.CHANNEL_ID

        )

                .setContentTitle(
                        context.getString(
                                R.string.notification_title
                        )
                )

                .setContentText(text)

                .setSmallIcon(R.drawable.ic_launcher_foreground)

                .setOngoing(true)

                .build();

    }

    public void updateNotification(String text) {

        NotificationManager manager =

                (NotificationManager)

                        context.getSystemService(

                                Context.NOTIFICATION_SERVICE

                        );

        if (manager == null) {
            return;
        }

        manager.notify(

                NOTIFICATION_ID,

                buildNotification(text)

        );

    }

}