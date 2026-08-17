package com.nicko.airecorder.manager;

import android.content.Context;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;

import com.nicko.airecorder.R;

public class DialogManager {

    public interface RenameListener {
        void onRename(String title);
    }

    public interface DeleteListener {
        void onDelete();
    }

    private final Context context;

    public DialogManager(Context context) {

        this.context = context;

    }

    public void showRenameDialog(
            String currentTitle,
            RenameListener listener
    ) {

        EditText editText = new EditText(context);

        editText.setText(currentTitle);

        new AlertDialog.Builder(context)

                .setTitle(R.string.rename_title)

                .setView(editText)

                .setPositiveButton(
                        R.string.rename_save,
                        (dialog, which) -> {

                            String title =
                                    editText
                                            .getText()
                                            .toString()
                                            .trim();

                            listener.onRename(title);

                        }
                )

                .setNegativeButton(
                        R.string.rename_cancel,
                        null
                )

                .show();

    }

    public void showDeleteDialog(DeleteListener listener) {

        new AlertDialog.Builder(context)

                .setTitle(R.string.delete_title)

                .setMessage(R.string.delete_message)

                .setPositiveButton(
                        R.string.delete_confirm,
                        (dialog, which) -> listener.onDelete()
                )

                .setNegativeButton(
                        R.string.rename_cancel,
                        null
                )

                .show();

    }

}