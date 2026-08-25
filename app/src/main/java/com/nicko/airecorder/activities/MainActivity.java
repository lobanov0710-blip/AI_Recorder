package com.nicko.airecorder.activities;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.nicko.airecorder.adapter.RecordAdapter;
import com.nicko.airecorder.model.RecordItem;
import com.nicko.airecorder.model.RecordingState;
import com.nicko.airecorder.common.RecordActions;
import com.nicko.airecorder.viewmodel.RecordViewModel;
import com.nicko.airecorder.databinding.ActivityMainBinding;
import com.nicko.airecorder.utils.PermissionManager;
import com.nicko.airecorder.receiver.RecordReceiver;
import com.nicko.airecorder.controller.RecordListController;
import com.nicko.airecorder.controller.RecordingController;
import com.nicko.airecorder.manager.ShareManager;
import com.nicko.airecorder.manager.DialogManager;
import com.nicko.airecorder.R;


public class MainActivity extends AppCompatActivity {

    private PermissionManager permissionManager;
    private ActivityMainBinding binding;

    private RecordAdapter adapter;

    private RecordReceiver recordReceiver;
    private RecordViewModel viewModel;
    private RecordListController recordListController;
    private RecordingController recordingController;
    private ShareManager shareManager;
    private DialogManager dialogManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        viewModel =
                new ViewModelProvider(this)
                        .get(RecordViewModel.class);

        permissionManager =
                new PermissionManager(this);

        shareManager =
                new ShareManager(this);

        dialogManager =
                new DialogManager(this);

        adapter =
                new RecordAdapter(createAdapterListener());

        binding.recyclerRecords.setLayoutManager(
                new LinearLayoutManager(this)
        );

        binding.recyclerRecords.setAdapter(adapter);

        recordListController =
                new RecordListController(
                        viewModel,
                        adapter
                );

        recordingController =
                new RecordingController(
                        binding,
                        viewModel
                );

        recordListController.observe(this);

        recordingController.observe(this);

        binding.btnRecord.setOnClickListener(v ->

                handleRecordButtonClick()

        );

        binding.btnStop.setOnClickListener(v ->

                stopRecording()

        );

    }
    private void handleRecordButtonClick() {

        RecordingState state =
                viewModel.getRecordingState().getValue();

        if (state == null) {

            state = RecordingState.IDLE;

        }

        switch (state) {

            case IDLE:

                startRecordingWithPermissions();

                break;

            case RECORDING:

                viewModel.pauseRecording();

                break;

            case PAUSED:

                viewModel.resumeRecording();

                break;

        }

    }
    private void startRecordingWithPermissions() {

        if (!permissionManager.hasAudioPermission()) {

            permissionManager.requestAudioPermission();

            return;

        }

        if (permissionManager
                .shouldRequestNotificationPermission()) {

            permissionManager
                    .requestNotificationPermission();

            return;

        }

        startRecordingNow();

    }
    private void startRecordingNow() {

        RecordingState state =
                viewModel.getRecordingState().getValue();

        if (state != RecordingState.IDLE) {
            return;
        }

        binding.waveformView.clearWaveform();

        viewModel.startRecording();

    }
    private void stopRecording() {

        RecordingState state = viewModel.getRecordingState().getValue();

        if (state != RecordingState.RECORDING
                && state != RecordingState.PAUSED) {
            return;
        }

        viewModel.stopRecording();

    }
    private void openPlayer(RecordItem item) {

        Intent intent = new Intent(
                MainActivity.this,
                PlayerActivity.class
        );

        intent.putExtra(
                "id",
                item.getId()
        );

        intent.putExtra(
                "filePath",
                item.getFilePath()
        );

        intent.putExtra(
                "title",
                item.getTitle()
        );

        startActivity(intent);

    }

    @Override
    protected void onStart() {

        super.onStart();

        if (recordReceiver != null) {
            return;
        }

        recordReceiver =
                new RecordReceiver(

                        new RecordReceiver.Callback() {

                            @Override
                            public void onRecordStarted() {

                                viewModel.setRecording();

                            }

                            @Override
                            public void onRecordPaused() {

                                viewModel.setPaused();

                            }

                            @Override
                            public void onRecordResumed() {

                                viewModel.setResumed();

                            }

                            @Override
                            public void onRecordStopped() {

                                viewModel.setStopped();

                            }

                            @Override
                            public void onRecordTime(
                                    long duration
                            ) {

                                long seconds =
                                        duration / 1000;

                                String time =
                                        String.format(

                                                java.util.Locale
                                                        .getDefault(),

                                                "%02d:%02d",

                                                seconds / 60,

                                                seconds % 60

                                        );

                                binding.txtRecordTime.setText(

                                        getString(
                                                R.string.recording_indicator,
                                                time
                                        )

                                );

                            }

                            @Override
                            public void onAmplitude(
                                    int amplitude
                            ) {

                                binding.waveformView
                                        .addAmplitude(
                                                amplitude
                                        );

                            }

                        }

                );

        IntentFilter filter =
                createRecordIntentFilter();

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.TIRAMISU) {

            registerReceiver(
                    recordReceiver,
                    filter,
                    RECEIVER_NOT_EXPORTED
            );

        } else {

            registerReceiver(
                    recordReceiver,
                    filter
            );

        }

        viewModel.requestRecordingState();

    }

    @Override
    protected void onStop() {

        super.onStop();

        unregisterRecordReceiver();

    }
    private void unregisterRecordReceiver() {

        if (recordReceiver == null) {
            return;
        }

        try {

            unregisterReceiver(recordReceiver);

        } catch (IllegalArgumentException ignored) {

        }

        recordReceiver = null;

    }
    private IntentFilter createRecordIntentFilter() {

        IntentFilter filter = new IntentFilter();

        filter.addAction(
                RecordActions.ACTION_RECORD_STARTED
        );

        filter.addAction(
                RecordActions.ACTION_RECORD_PAUSED
        );

        filter.addAction(
                RecordActions.ACTION_RECORD_RESUMED
        );

        filter.addAction(
                RecordActions.ACTION_RECORD_STOPPED
        );

        filter.addAction(
                RecordActions.ACTION_RECORD_TIME
        );

        filter.addAction(
                RecordActions.ACTION_RECORD_AMPLITUDE
        );

        return filter;

    }
    private RecordAdapter.OnItemClickListener createAdapterListener() {

        return new RecordAdapter.OnItemClickListener() {

            @Override
            public void onItemClick(
                    RecordItem item
            ) {

                openPlayer(item);
            }

            @Override
            public void onItemLongClick(
                    RecordItem item
            ) {

                showRenameDialog(item);
            }

            @Override
            public void onMoreClick(
                    RecordItem item
            ) {

                showRecordActions(item);
            }
        };
    }

    private void showRecordActions(
            RecordItem item
    ) {

        if (item == null) {
            return;
        }

        dialogManager.showRecordActions(

                new DialogManager.RecordActionsListener() {

                    @Override
                    public void onRename() {

                        showRenameDialog(
                                item
                        );
                    }

                    @Override
                    public void onShare() {

                        shareRecord(
                                item
                        );
                    }

                    @Override
                    public void onDelete() {

                        showDeleteDialog(
                                item
                        );
                    }
                }
        );
    }

    private void showRenameDialog(RecordItem item) {

        dialogManager.showRenameDialog(

                item.getTitle(),

                title -> {

                    String newTitle = title.trim();

                    if (newTitle.isEmpty()) {

                        showToast(
                                getString(R.string.enter_title)
                        );

                        return;

                    }

                    if (newTitle.equals(item.getTitle())) {
                        return;
                    }

                    viewModel.rename(
                            item.getId(),
                            newTitle
                    );

                }

        );

    }
    private void showDeleteDialog(
            RecordItem item
    ) {

        dialogManager.showDeleteDialog(() -> {

            String filePath =
                    item.getFilePath();

            /*
             * Если запись в БД повреждена
             * и физический путь отсутствует,
             * удалить хотя бы некорректную строку БД.
             */
            if (filePath == null
                    || filePath.trim().isEmpty()) {

                viewModel.delete(
                        item.getId()
                );

                return;

            }

            java.io.File file =
                    new java.io.File(
                            filePath
                    );

            if (file.exists()
                    && !file.delete()) {

                showToast(
                        getString(
                                R.string.delete_error
                        )
                );

                return;

            }

            viewModel.delete(
                    item.getId()
            );

        });

    }
    private void shareRecord(RecordItem item) {

        if (item == null) {
            return;
        }

        shareManager.share(item.getFilePath());

    }

    @Override
    public void onRequestPermissionsResult(

            int requestCode,

            @NonNull String[] permissions,

            @NonNull int[] grantResults

    ) {

        super.onRequestPermissionsResult(

                requestCode,

                permissions,

                grantResults

        );

        handleAudioPermissionResult(
                requestCode,
                grantResults
        );

        handleNotificationPermissionResult(
                requestCode
        );

    }
    private void handleAudioPermissionResult(

            int requestCode,

            int[] grantResults

    ) {

        if (requestCode
                != PermissionManager.REQUEST_RECORD_AUDIO) {

            return;

        }

        if (grantResults.length > 0
                && grantResults[0]
                == PackageManager.PERMISSION_GRANTED) {

            startRecordingWithPermissions();

            return;

        }

        showToast(
                getString(
                        R.string.microphone_permission_required
                )
        );

    }
    private void handleNotificationPermissionResult(
            int requestCode
    ) {

        if (requestCode
                != PermissionManager.REQUEST_POST_NOTIFICATIONS) {

            return;

        }

        startRecordingNow();

    }
    private void showToast(String message) {

        if (!canShowUi()) {
            return;
        }

        Toast.makeText(

                this,

                message,

                Toast.LENGTH_SHORT

        ).show();

    }
    private boolean canShowUi() {

        return !isFinishing() && !isDestroyed();

    }

    @Override
    protected void onDestroy() {

        unregisterRecordReceiver();

        binding = null;

        super.onDestroy();

    }
}