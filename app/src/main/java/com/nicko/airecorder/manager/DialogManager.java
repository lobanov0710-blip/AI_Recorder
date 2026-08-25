package com.nicko.airecorder.manager;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.nicko.airecorder.R;

public class DialogManager {

    public interface RenameListener {

        void onRename(String title);
    }

    public interface DeleteListener {

        void onDelete();
    }

    public interface RecordActionsListener {

        void onRename();

        void onShare();

        void onDelete();
    }

    private final Context context;

    public DialogManager(
            Context context
    ) {

        this.context = context;
    }

    public void showRenameDialog(
            String currentTitle,
            RenameListener listener
    ) {

        EditText editText =
                new EditText(context);

        editText.setText(
                currentTitle
        );

        new AlertDialog.Builder(context)

                .setTitle(
                        R.string.rename_title
                )

                .setView(
                        editText
                )

                .setPositiveButton(
                        R.string.rename_save,
                        (dialog, which) -> {

                            String title =
                                    editText
                                            .getText()
                                            .toString()
                                            .trim();

                            listener.onRename(
                                    title
                            );
                        }
                )

                .setNegativeButton(
                        R.string.rename_cancel,
                        null
                )

                .show();
    }

    public void showDeleteDialog(
            DeleteListener listener
    ) {

        new AlertDialog.Builder(context)

                .setTitle(
                        R.string.delete_title
                )

                .setMessage(
                        R.string.delete_message
                )

                .setPositiveButton(
                        R.string.delete_confirm,
                        (dialog, which) ->
                                listener.onDelete()
                )

                .setNegativeButton(
                        R.string.rename_cancel,
                        null
                )

                .show();
    }

    public void showRecordActions(
            RecordActionsListener listener
    ) {

        BottomSheetDialog dialog =
                new BottomSheetDialog(
                        context
                );

        View view =
                LayoutInflater
                        .from(context)
                        .inflate(
                                R.layout.bottom_sheet_record_actions,
                                null,
                                false
                        );

        dialog.setContentView(
                view
        );

        View actionRename =
                view.findViewById(
                        R.id.actionRename
                );

        View actionShare =
                view.findViewById(
                        R.id.actionShare
                );

        View actionDelete =
                view.findViewById(
                        R.id.actionDelete
                );

        actionRename.setOnClickListener(v -> {

            dialog.dismiss();

            listener.onRename();
        });

        actionShare.setOnClickListener(v -> {

            dialog.dismiss();

            listener.onShare();
        });

        actionDelete.setOnClickListener(v -> {

            dialog.dismiss();

            listener.onDelete();
        });

        dialog.show();
    }
}