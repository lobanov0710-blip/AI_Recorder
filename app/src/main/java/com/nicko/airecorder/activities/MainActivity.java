package com.nicko.airecorder.activities;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.nicko.airecorder.R;
import com.nicko.airecorder.adapter.RecordAdapter;
import com.nicko.airecorder.common.RecordActions;
import com.nicko.airecorder.controller.RecordListController;
import com.nicko.airecorder.controller.RecordingController;
import com.nicko.airecorder.databinding.ActivityMainBinding;
import com.nicko.airecorder.manager.DialogManager;
import com.nicko.airecorder.manager.ShareManager;
import com.nicko.airecorder.model.RecordItem;
import com.nicko.airecorder.model.RecordingState;
import com.nicko.airecorder.receiver.RecordReceiver;
import com.nicko.airecorder.utils.MotionUtils;
import com.nicko.airecorder.utils.PermissionManager;
import com.nicko.airecorder.utils.SystemBarsManager;
import com.nicko.airecorder.viewmodel.RecordViewModel;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    /*
     * =========================================================
     * VIEW
     * =========================================================
     */

    private ActivityMainBinding binding;

    /*
     * =========================================================
     * VIEW MODEL
     * =========================================================
     */

    private RecordViewModel viewModel;

    /*
     * =========================================================
     * UI / CONTROLLERS
     * =========================================================
     */

    private RecordAdapter adapter;

    private RecordListController recordListController;

    private RecordingController recordingController;

    /*
     * =========================================================
     * MANAGERS
     * =========================================================
     */

    private PermissionManager permissionManager;

    private ShareManager shareManager;

    private DialogManager dialogManager;

    /*
     * =========================================================
     * RECEIVER
     * =========================================================
     */

    private RecordReceiver recordReceiver;

    /*
     * =========================================================
     * ACTIVITY CREATE
     * =========================================================
     */

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {

        /*
         * SplashScreen должен устанавливаться
         * до super.onCreate().
         */
        SplashScreen splashScreen =
                SplashScreen.installSplashScreen(
                        this
                );

        super.onCreate(
                savedInstanceState
        );

        /*
         * =====================================================
         * VIEW BINDING
         * =====================================================
         */

        binding =
                ActivityMainBinding.inflate(
                        getLayoutInflater()
                );

        setContentView(
                binding.getRoot()
        );

        /*
         * =====================================================
         * EDGE-TO-EDGE
         * =====================================================
         */

        SystemBarsManager.apply(
                this,
                binding.getRoot()
        );

        /*
         * =====================================================
         * SPLASH
         * =====================================================
         */

        configureSplashExitAnimation(
                splashScreen
        );

        /*
         * =====================================================
         * VIEW MODEL
         * =====================================================
         */

        viewModel =
                new ViewModelProvider(this)
                        .get(
                                RecordViewModel.class
                        );

        /*
         * =====================================================
         * STORAGE ↔ ROOM RECOVERY
         * =====================================================
         *
         * Проверяет consistency после предыдущего
         * аварийного завершения процесса.
         *
         * Выполняется на Repository executor.
         */
        viewModel.reconcileStorage();

        /*
         * =====================================================
         * MANAGERS
         * =====================================================
         */

        permissionManager =
                new PermissionManager(
                        this
                );

        shareManager =
                new ShareManager(
                        this
                );

        dialogManager =
                new DialogManager(
                        this
                );

        /*
         * =====================================================
         * RECORD LIST
         * =====================================================
         */

        adapter =
                new RecordAdapter(
                        createAdapterListener()
                );

        binding.recyclerRecords
                .setLayoutManager(
                        new LinearLayoutManager(
                                this
                        )
                );

        binding.recyclerRecords
                .setAdapter(
                        adapter
                );

        recordListController =
                new RecordListController(
                        viewModel,
                        adapter,
                        binding.emptyState
                );

        /*
         * =====================================================
         * RECORDING CONTROLLER
         * =====================================================
         */

        recordingController =
                new RecordingController(
                        binding,
                        viewModel
                );

        /*
         * =====================================================
         * OBSERVERS
         * =====================================================
         */

        recordListController.observe(
                this
        );

        recordingController.observe(
                this
        );

        /*
         * =====================================================
         * BUTTONS
         * =====================================================
         */

        binding.btnRecord
                .setOnClickListener(
                        view ->
                                handleRecordButtonClick()
                );

        binding.btnStop
                .setOnClickListener(
                        view ->
                                stopRecording()
                );

        /*
         * =====================================================
         * BRAND ENTRANCE
         * =====================================================
         */

        animateHeaderEntrance();
    }

    /*
     * =========================================================
     * SPLASH EXIT ANIMATION
     * =========================================================
     */

    private void configureSplashExitAnimation(
            SplashScreen splashScreen
    ) {

        splashScreen.setOnExitAnimationListener(
                splashScreenView -> {

                    /*
                     * Если пользователь отключил системные
                     * анимации — splash удаляем сразу.
                     */
                    if (!MotionUtils.areAnimationsEnabled(
                            this
                    )) {

                        splashScreenView.remove();

                        return;
                    }

                    /*
                     * Сначала отменяем возможную
                     * предыдущую animation.
                     */
                    splashScreenView
                            .getIconView()
                            .animate()
                            .cancel();

                    /*
                     * Небольшое увеличение +
                     * плавное исчезновение.
                     */
                    splashScreenView
                            .getIconView()
                            .animate()
                            .scaleX(1.10f)
                            .scaleY(1.10f)
                            .alpha(0f)
                            .setDuration(240L)
                            .withEndAction(
                                    splashScreenView::remove
                            )
                            .start();
                }
        );
    }

    /*
     * =========================================================
     * HEADER ENTRANCE
     * =========================================================
     */

    private void animateHeaderEntrance() {

        if (binding == null) {
            return;
        }

        /*
         * Останавливаем предыдущие animator,
         * если Activity была пересоздана.
         */
        binding.txtAppTitle
                .animate()
                .cancel();

        binding.txtAppSubtitle
                .animate()
                .cancel();

        /*
         * Если системные анимации выключены,
         * сразу показываем финальное состояние.
         */
        if (!MotionUtils.areAnimationsEnabled(
                this
        )) {

            resetHeaderTransform();

            return;
        }

        /*
         * =====================================================
         * INITIAL STATE
         * =====================================================
         */

        binding.txtAppTitle.setAlpha(
                0f
        );

        binding.txtAppTitle.setTranslationY(
                -18f
        );

        binding.txtAppSubtitle.setAlpha(
                0f
        );

        binding.txtAppSubtitle.setTranslationY(
                -10f
        );

        /*
         * =====================================================
         * TITLE
         * =====================================================
         */

        binding.txtAppTitle
                .animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(70L)
                .setDuration(320L)
                .start();

        /*
         * =====================================================
         * SUBTITLE
         * =====================================================
         */

        binding.txtAppSubtitle
                .animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(150L)
                .setDuration(320L)
                .start();
    }

    /*
     * =========================================================
     * RESET HEADER
     * =========================================================
     */

    private void resetHeaderTransform() {

        if (binding == null) {
            return;
        }

        binding.txtAppTitle.setAlpha(
                1f
        );

        binding.txtAppTitle.setTranslationY(
                0f
        );

        binding.txtAppSubtitle.setAlpha(
                1f
        );

        binding.txtAppSubtitle.setTranslationY(
                0f
        );
    }

    /*
     * =========================================================
     * RECORD BUTTON
     * =========================================================
     */

    private void handleRecordButtonClick() {

        RecordingState state =
                viewModel
                        .getRecordingState()
                        .getValue();

        if (state == null) {

            state =
                    RecordingState.IDLE;
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

    /*
     * =========================================================
     * PERMISSIONS
     * =========================================================
     */

    private void startRecordingWithPermissions() {

        if (!permissionManager
                .hasAudioPermission()) {

            permissionManager
                    .requestAudioPermission();

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

    /*
     * =========================================================
     * START RECORDING
     * =========================================================
     */

    private void startRecordingNow() {

        RecordingState state =
                viewModel
                        .getRecordingState()
                        .getValue();

        if (state != RecordingState.IDLE) {
            return;
        }

        binding.waveformView
                .clearWaveform();

        viewModel.startRecording();
    }

    /*
     * =========================================================
     * STOP RECORDING
     * =========================================================
     */

    private void stopRecording() {

        RecordingState state =
                viewModel
                        .getRecordingState()
                        .getValue();

        if (state != RecordingState.RECORDING
                && state != RecordingState.PAUSED) {

            return;
        }

        viewModel.stopRecording();
    }

    /*
     * =========================================================
     * PLAYER
     * =========================================================
     */

    private void openPlayer(
            RecordItem item
    ) {

        if (item == null) {
            return;
        }

        Intent intent =
                new Intent(
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

        startActivity(
                intent
        );
    }

    /*
     * =========================================================
     * ACTIVITY START
     * =========================================================
     */

    @Override
    protected void onStart() {

        super.onStart();

        registerRecordReceiver();

        viewModel.requestRecordingState();
    }

    /*
     * =========================================================
     * RECORD RECEIVER
     * =========================================================
     */

    private void registerRecordReceiver() {

        if (recordReceiver != null) {
            return;
        }

        recordReceiver =
                new RecordReceiver(

                        new RecordReceiver.Callback() {

                            @Override
                            public void onRecordStarted() {

                                if (viewModel == null) {
                                    return;
                                }

                                viewModel.setRecording();
                            }

                            @Override
                            public void onRecordPaused() {

                                if (viewModel == null) {
                                    return;
                                }

                                viewModel.setPaused();
                            }

                            @Override
                            public void onRecordResumed() {

                                if (viewModel == null) {
                                    return;
                                }

                                viewModel.setResumed();
                            }

                            @Override
                            public void onRecordStopped() {

                                if (viewModel == null) {
                                    return;
                                }

                                viewModel.setStopped();
                            }

                            @Override
                            public void onRecordTime(
                                    long duration
                            ) {

                                if (binding == null) {
                                    return;
                                }

                                long seconds =
                                        duration / 1000L;

                                String time =
                                        String.format(
                                                Locale.getDefault(),
                                                "%02d:%02d",
                                                seconds / 60L,
                                                seconds % 60L
                                        );

                                binding.txtRecordTime
                                        .setText(
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

                                if (binding == null) {
                                    return;
                                }

                                binding.waveformView
                                        .addAmplitude(
                                                amplitude
                                        );
                            }
                        }
                );

        IntentFilter filter =
                createRecordIntentFilter();

        /*
         * =====================================================
         * INTERNAL-ONLY RECEIVER
         * =====================================================
         *
         * Receiver принимает события только от
         * нашего приложения.
         *
         * ContextCompat обеспечивает одинаковую
         * security semantics на поддерживаемых
         * Android API.
         */
        ContextCompat.registerReceiver(
                this,
                recordReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }

    /*
     * =========================================================
     * ACTIVITY STOP
     * =========================================================
     */

    @Override
    protected void onStop() {

        unregisterRecordReceiver();

        super.onStop();
    }

    /*
     * =========================================================
     * UNREGISTER RECEIVER
     * =========================================================
     */

    private void unregisterRecordReceiver() {

        if (recordReceiver == null) {
            return;
        }

        try {

            unregisterReceiver(
                    recordReceiver
            );

        } catch (IllegalArgumentException ignored) {

        }

        recordReceiver = null;
    }

    /*
     * =========================================================
     * INTENT FILTER
     * =========================================================
     */

    private IntentFilter createRecordIntentFilter() {

        IntentFilter filter =
                new IntentFilter();

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

    /*
     * =========================================================
     * ADAPTER CALLBACKS
     * =========================================================
     */

    private RecordAdapter.OnItemClickListener
    createAdapterListener() {

        return new RecordAdapter
                .OnItemClickListener() {

            @Override
            public void onItemClick(
                    RecordItem item
            ) {

                openPlayer(
                        item
                );
            }

            @Override
            public void onItemLongClick(
                    RecordItem item
            ) {

                showRenameDialog(
                        item
                );
            }

            @Override
            public void onMoreClick(
                    RecordItem item
            ) {

                showRecordActions(
                        item
                );
            }
        };
    }

    /*
     * =========================================================
     * RECORD ACTIONS
     * =========================================================
     */

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

    /*
     * =========================================================
     * RENAME
     * =========================================================
     */

    private void showRenameDialog(
            RecordItem item
    ) {

        if (item == null) {
            return;
        }

        dialogManager.showRenameDialog(

                item.getTitle(),

                title -> {

                    if (title == null) {

                        showToast(
                                getString(
                                        R.string.enter_title
                                )
                        );

                        return;
                    }

                    String newTitle =
                            title.trim();

                    if (newTitle.isEmpty()) {

                        showToast(
                                getString(
                                        R.string.enter_title
                                )
                        );

                        return;
                    }

                    String oldTitle =
                            item.getTitle();

                    if (oldTitle != null
                            && newTitle.equals(
                            oldTitle.trim()
                    )) {

                        return;
                    }

                    viewModel.rename(
                            item.getId(),
                            newTitle
                    );
                }
        );
    }

    /*
     * =========================================================
     * DELETE
     * =========================================================
     */

    private void showDeleteDialog(
            RecordItem item
    ) {

        if (item == null) {

            return;
        }

        dialogManager.showDeleteDialog(
                () -> {

                    /*
                     * Activity больше не управляет
                     * физическим файлом.
                     *
                     * Полная consistency операция:
                     *
                     * filesystem
                     * +
                     * Room
                     *
                     * выполняется Repository.
                     */
                    viewModel.deleteRecord(

                            item.getId(),

                            item.getFilePath(),

                            success -> {

                                if (success) {

                                    return;
                                }

                                showToast(
                                        getString(
                                                R.string.delete_error
                                        )
                                );
                            }
                    );
                }
        );
    }

    /*
     * =========================================================
     * SHARE
     * =========================================================
     */

    private void shareRecord(
            RecordItem item
    ) {

        if (item == null) {
            return;
        }

        String filePath =
                item.getFilePath();

        if (filePath == null
                || filePath.trim().isEmpty()) {

            showToast(
                    getString(
                            R.string.file_not_found
                    )
            );

            return;
        }

        shareManager.share(
                filePath
        );
    }

    /*
     * =========================================================
     * PERMISSION RESULT
     * =========================================================
     */

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

    /*
     * =========================================================
     * MICROPHONE PERMISSION
     * =========================================================
     */

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

    /*
     * =========================================================
     * NOTIFICATION PERMISSION
     * =========================================================
     */

    private void handleNotificationPermissionResult(
            int requestCode
    ) {

        if (requestCode
                != PermissionManager.REQUEST_POST_NOTIFICATIONS) {

            return;
        }

        startRecordingNow();
    }

    /*
     * =========================================================
     * TOAST
     * =========================================================
     */

    private void showToast(
            String message
    ) {

        if (!canShowUi()) {
            return;
        }

        Toast.makeText(
                this,
                message,
                Toast.LENGTH_SHORT
        ).show();
    }

    /*
     * =========================================================
     * UI CHECK
     * =========================================================
     */

    private boolean canShowUi() {

        return !isFinishing()
                && !isDestroyed();
    }

    /*
     * =========================================================
     * DESTROY
     * =========================================================
     */

    @Override
    protected void onDestroy() {

        unregisterRecordReceiver();

        if (binding != null) {

            binding.txtAppTitle
                    .animate()
                    .cancel();

            binding.txtAppSubtitle
                    .animate()
                    .cancel();

            binding.btnRecord
                    .animate()
                    .cancel();
        }

        binding = null;

        super.onDestroy();
    }
}