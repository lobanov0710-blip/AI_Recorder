package com.nicko.airecorder.manager;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.nicko.airecorder.R;

import java.io.File;

public class ShareManager {

    private final Activity activity;

    public ShareManager(Activity activity) {

        this.activity = activity;

    }

    public void share(String filePath) {

        File file = new File(filePath);

        if (!file.exists()) {

            Toast.makeText(
                    activity,
                    R.string.file_not_found,
                    Toast.LENGTH_SHORT
            ).show();

            return;

        }

        Uri uri = FileProvider.getUriForFile(

                activity,

                activity.getPackageName() + ".provider",

                file

        );

        Intent intent = new Intent(Intent.ACTION_SEND);

        intent.setType("audio/mp4");

        intent.putExtra(
                Intent.EXTRA_STREAM,
                uri
        );

        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
        );

        try {

            activity.startActivity(

                    Intent.createChooser(
                            intent,
                            activity.getString(R.string.share_record)
                    )

            );

        } catch (ActivityNotFoundException e) {

            Toast.makeText(
                    activity,
                    R.string.share_app_not_found,
                    Toast.LENGTH_SHORT
            ).show();

        }

    }

}