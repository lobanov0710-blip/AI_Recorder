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
import com.nicko.airecorder.utils.PermissionManager;
import com.nicko.airecorder.utils.SystemBarsManager;
import com.nicko.airecorder.viewmodel.RecordViewModel;

import java.io.File;
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
         * SplashScreen должен быть установлен
         * ДО super.onCreate().
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
         * SPLASH EXIT ANIMATION
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

        /*
         * Empty State подключён напрямую
         * к RecordListController.
         */
        recordListController =
                new RecordListController(
                        viewModel,
                        adapter,
                        binding.emptyState
                );

        /*
         * =====================================================
         * RECORDING UI CONTROLLER
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
                     * Анимируем именно Splash icon,
                     * а не весь MainActivity.
                     */
                    splashScreenView
                            .getIconView()
                            .animate()
                            .cancel();

                    splashScreenView
                            .getIconView()
                            .animate()
                            .scaleX(1.12f)
                            .scaleY(1.12f)
                            .alpha(0f)
                            .setDuration(260L)
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
         * Не допускаем накопления старых
         * ViewPropertyAnimator.
         */
        binding.txtAppTitle
                .animate()
                .cancel();

        binding.txtAppSubtitle
                .animate()
                .cancel();

        /*
         * Начальное состояние заголовка.
         */
        binding.txtAppTitle.setAlpha(
                0f
        );

        binding.txtAppTitle.setTranslationY(
                -18f
        );

        /*
         * Начальное состояние подзаголовка.
         */
        binding.txtAppSubtitle.setAlpha(
                0f
        );

        binding.txtAppSubtitle.setTranslationY(
                -10f
        );

        /*
         * AI Recorder.
         */
        binding.txtAppTitle
                .animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(80L)
                .setDuration(360L)
                .start();

        /*
         * Подзаголовок появляется
         * немного позже.
         */
        binding.txtAppSubtitle
                .animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(180L)
                .setDuration(360L)
                .start();
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
     * PERMISSION FLOW
     * =========================================================
     */

    private void startRecordingWithPermissions() {

        /*
         * Microphone permission.
         */
        if (!permissionManager
                .hasAudioPermission()) {

            permissionManager
                    .requestAudioPermission();

            return;
        }

        /*
         * Android 13+ notification permission.
         *
         * Если permission ещё не запрошен,
         * показываем системный запрос.
         */
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

        /*
         * Очистить старый live waveform
         * перед новой сессией.
         */
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

        /*
         * После возврата из background
         * синхронизируем UI с RecordService.
         */
        viewModel.requestRecordingState();
    }

    /*
     * =========================================================
     * RECEIVER REGISTRATION
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
     * RECEIVER UNREGISTER
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

            /*
             * Receiver уже мог быть снят системой/
             * Activity lifecycle.
             */
        }

        recordReceiver = null;
    }

    /*
     * =========================================================
     * RECORD INTENT FILTER
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
     * RECORD ADAPTER CALLBACKS
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
     * RECORD ACTIONS BOTTOM SHEET
     * =========================================================
     */

    private void showRecordActions(
            RecordItem item
    ) {

        if (item == null) {
            return;
        }

        dialogManager.showRecordActions(

                new DialogManager
                        .RecordActionsListener() {

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

                    String filePath =
                            item.getFilePath();

                    /*
                     * Если путь в БД повреждён,
                     * удаляем хотя бы запись Room.
                     */
                    if (filePath == null
                            || filePath
                            .trim()
                            .isEmpty()) {

                        viewModel.delete(
                                item.getId()
                        );

                        return;
                    }

                    File file =
                            new File(
                                    filePath
                            );

                    /*
                     * Если файл существует,
                     * сначала физически удаляем его.
                     */
                    if (file.exists()
                            && !file.delete()) {

                        showToast(
                                getString(
                                        R.string.delete_error
                                )
                        );

                        return;
                    }

                    /*
                     * После успешного удаления файла
                     * удаляем строку из Room.
                     */
                    viewModel.delete(
                            item.getId()
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
                != PermissionManager
                .REQUEST_RECORD_AUDIO) {

            return;
        }

        if (grantResults.length > 0
                && grantResults[0]
                == PackageManager
                .PERMISSION_GRANTED) {

            startRecordingWithPermissions();

            return;
        }

        showToast(
                getString(
                        R.string
                                .microphone_permission_required
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
                != PermissionManager
                .REQUEST_POST_NOTIFICATIONS) {

            return;
        }

        /*
         * POST_NOTIFICATIONS не является
         * разрешением на сам захват микрофона.
         *
         * После ответа пользователя продолжаем
         * старт записи.
         */
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
     * UI STATE CHECK
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

        /*
         * Останавливаем только декоративные
         * View animations.
         *
         * RecordService здесь НЕ останавливаем.
         */
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