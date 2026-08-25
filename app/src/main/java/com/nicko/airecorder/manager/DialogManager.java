package com.nicko.airecorder.manager;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
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

    /*
     * =========================================================
     * RENAME
     * =========================================================
     */

    public void showRenameDialog(
            String currentTitle,
            RenameListener listener
    ) {

        View view =
                LayoutInflater
                        .from(context)
                        .inflate(
                                R.layout.dialog_rename_record,
                                null,
                                false
                        );

        TextInputEditText editText =
                view.findViewById(
                        R.id.editRecordTitle
                );

        if (currentTitle != null) {

            editText.setText(
                    currentTitle
            );

            editText.setSelection(
                    editText.length()
            );
        }

        AlertDialog dialog =
                new MaterialAlertDialogBuilder(
                        context
                )

                        .setTitle(
                                R.string.rename_title
                        )

                        .setView(
                                view
                        )

                        .setPositiveButton(
                                R.string.rename_save,
                                null
                        )

                        .setNegativeButton(
                                R.string.rename_cancel,
                                null
                        )

                        .create();

        dialog.setOnShowListener(
                ignored -> {

                    dialog
                            .getButton(
                                    AlertDialog.BUTTON_POSITIVE
                            )
                            .setTextColor(

                                    ContextCompat.getColor(
                                            context,
                                            R.color.ai_primary_light
                                    )
                            );

                    dialog
                            .getButton(
                                    AlertDialog.BUTTON_POSITIVE
                            )
                            .setOnClickListener(
                                    v -> {

                                        String title =
                                                editText
                                                        .getText()
                                                        == null
                                                        ? ""
                                                        : editText
                                                        .getText()
                                                        .toString()
                                                        .trim();

                                        /*
                                         * Пустое название не закрывает
                                         * диалог.
                                         *
                                         * MainActivity также оставляет
                                         * собственную валидацию.
                                         */
                                        if (title.isEmpty()) {

                                            return;
                                        }

                                        listener.onRename(
                                                title
                                        );

                                        dialog.dismiss();
                                    }
                            );
                }
        );

        dialog.show();

        editText.requestFocus();
    }

    /*
     * =========================================================
     * DELETE
     * =========================================================
     */

    public void showDeleteDialog(
            DeleteListener listener
    ) {

        AlertDialog dialog =
                new MaterialAlertDialogBuilder(
                        context
                )

                        .setIcon(
                                R.drawable.ic_delete_24
                        )

                        .setTitle(
                                R.string.delete_title
                        )

                        .setMessage(
                                R.string.delete_message
                        )

                        .setNegativeButton(
                                R.string.rename_cancel,
                                null
                        )

                        .setPositiveButton(
                                R.string.delete_confirm,
                                (ignored, which) ->
                                        listener.onDelete()
                        )

                        .create();

        dialog.setOnShowListener(
                ignored -> {

                    dialog
                            .getButton(
                                    AlertDialog.BUTTON_POSITIVE
                            )
                            .setTextColor(

                                    ContextCompat.getColor(
                                            context,
                                            R.color.ai_danger
                                    )
                            );
                }
        );

        dialog.show();
    }

    /*
     * =========================================================
     * RECORD ACTIONS BOTTOM SHEET
     * =========================================================
     */

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

        actionRename.setOnClickListener(
                v -> {

                    dialog.dismiss();

                    listener.onRename();
                }
        );

        actionShare.setOnClickListener(
                v -> {

                    dialog.dismiss();

                    listener.onShare();
                }
        );

        actionDelete.setOnClickListener(
                v -> {

                    dialog.dismiss();

                    listener.onDelete();
                }
        );

        dialog.show();
    }
}