package com.nicko.airecorder.controller;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;

public class FileController {

    private final Context context;

    public FileController(Context context) {

        this.context = context.getApplicationContext();

    }

    public boolean delete(String filePath) {

        File file = new File(filePath);

        return !file.exists() || file.delete();

    }

    public void share(String filePath) {

        File file = new File(filePath);

        Uri uri = FileProvider.getUriForFile(

                context,

                context.getPackageName() + ".provider",

                file

        );

        Intent intent = new Intent(Intent.ACTION_SEND);

        intent.setType("audio/mp4");

        intent.putExtra(Intent.EXTRA_STREAM, uri);

        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        context.startActivity(

                Intent.createChooser(

                                intent,

                                "Поделиться записью"

                        )

                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        );

    }

}