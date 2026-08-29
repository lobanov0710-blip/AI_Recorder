package com.nicko.airecorder.activities;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.nicko.airecorder.R;
import com.nicko.airecorder.controller.PlayerController;
import com.nicko.airecorder.databinding.ActivityPlayerBinding;
import com.nicko.airecorder.manager.DialogManager;
import com.nicko.airecorder.manager.ShareManager;
import com.nicko.airecorder.utils.SystemBarsManager;
import com.nicko.airecorder.utils.WaveformCache;
import com.nicko.airecorder.utils.WaveformExtractor;
import com.nicko.airecorder.viewmodel.RecordViewModel;

import java.io.File;

public class PlayerActivity
        extends AppCompatActivity {

    private ActivityPlayerBinding binding;

    private RecordViewModel viewModel;

    private PlayerController playerController;

    private ShareManager shareManager;

    private DialogManager dialogManager;

    private final Handler handler =
            new Handler(
                    Looper.getMainLooper()
            );

    private final Runnable updateRunnable =
            this::updateSeek;

    private String filePath;

    private long recordId;

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {

        super.onCreate(savedInstanceState);

        /*
         * =====================================================
         * VIEW BINDING
         * =====================================================
         */

        binding =
                ActivityPlayerBinding.inflate(
                        getLayoutInflater()
                );

        setContentView(
                binding.getRoot()
        );

        /*
         * Edge-to-edge + system bars.
         */
        SystemBarsManager.apply(
                this,
                binding.getRoot()
        );

        /*
         * =====================================================
         * DEPENDENCIES
         * =====================================================
         */

        viewModel =
                new ViewModelProvider(this)
                        .get(
                                RecordViewModel.class
                        );

        playerController =
                new PlayerController();

        shareManager =
                new ShareManager(this);

        dialogManager =
                new DialogManager(this);

        /*
         * =====================================================
         * INTENT DATA
         * =====================================================
         */

        filePath =
                getIntent()
                        .getStringExtra(
                                "filePath"
                        );

        if (filePath == null
                || filePath.trim().isEmpty()) {

            finish();

            return;
        }

        recordId =
                getIntent()
                        .getLongExtra(
                                "id",
                                -1
                        );

        String title =
                getIntent()
                        .getStringExtra(
                                "title"
                        );

        if (title == null
                || title.trim().isEmpty()) {

            binding.txtTitle.setText(
                    R.string.player_default_title
            );

        } else {

            binding.txtTitle.setText(
                    title
            );
        }

        /*
         * =====================================================
         * PLAYER
         * =====================================================
         */

        preparePlayer();

        loadWaveform();

        /*
         * =====================================================
         * BACK
         * =====================================================
         */

        binding.btnBack.setOnClickListener(
                v -> finish()
        );

        /*
         * =====================================================
         * PLAY / PAUSE
         * =====================================================
         */

        binding.btnPlay.setOnClickListener(
                v -> handlePlayClick()
        );

        /*
         * =====================================================
         * SHARE
         * =====================================================
         */

        binding.btnShare.setOnClickListener(
                v -> shareRecord()
        );

        /*
         * =====================================================
         * DELETE
         * =====================================================
         */

        binding.btnDelete.setOnClickListener(
                v -> deleteRecord()
        );

        /*
         * =====================================================
         * SEEK BAR
         * =====================================================
         */

        binding.seekBar
                .setOnSeekBarChangeListener(

                        new SeekBar
                                .OnSeekBarChangeListener() {

                            @Override
                            public void onProgressChanged(
                                    SeekBar seekBar,
                                    int progress,
                                    boolean fromUser
                            ) {

                                if (!fromUser) {
                                    return;
                                }

                                playerController.seekTo(
                                        progress
                                );

                                updateTime();

                                int duration =
                                        playerController
                                                .getDuration();

                                if (duration > 0) {

                                    binding.waveformView
                                            .setProgress(

                                                    (float) progress
                                                            / duration
                                            );
                                }
                            }

                            @Override
                            public void onStartTrackingTouch(
                                    SeekBar seekBar
                            ) {

                                /*
                                 * Пока ничего дополнительно
                                 * делать не требуется.
                                 */
                            }

                            @Override
                            public void onStopTrackingTouch(
                                    SeekBar seekBar
                            ) {

                                /*
                                 * После ручного seek позиция
                                 * уже установлена через
                                 * onProgressChanged().
                                 */
                            }
                        }
                );
    }

    /*
     * =========================================================
     * PLAY / PAUSE CLICK
     * =========================================================
     */

    private void handlePlayClick() {

        if (playerController.getPlayer()
                == null) {

            return;
        }

        try {

            /*
             * PAUSE
             */
            if (playerController.isPlaying()) {

                playerController.pause();

                updatePlayButton(
                        false
                );

                updateTime();

                handler.removeCallbacks(
                        updateRunnable
                );

                animatePlayButton();

                return;
            }

            /*
             * PLAY / RESUME
             */
            playerController.play();

            updatePlayButton(
                    true
            );

            animatePlayButton();

            updateSeek();

        } catch (Exception e) {

            showToast(
                    getString(
                            R.string.playback_error
                    )
            );
        }
    }

    /*
     * =========================================================
     * PREPARE PLAYER
     * =========================================================
     */

    private void preparePlayer() {

        try {

            playerController.prepare(

                    filePath,

                    /*
                     * PREPARED
                     */
                    mp -> {

                        if (binding == null) {
                            return;
                        }

                        binding.seekBar.setMax(
                                mp.getDuration()
                        );

                        binding.seekBar.setProgress(
                                0
                        );

                        binding.waveformView.setProgress(
                                0f
                        );

                        updatePlayButton(
                                false
                        );

                        updateTime();
                    },

                    /*
                     * COMPLETED
                     */
                    mp -> {

                        if (binding == null) {
                            return;
                        }

                        handler.removeCallbacks(
                                updateRunnable
                        );

                        updatePlayButton(
                                false
                        );

                        binding.seekBar.setProgress(
                                0
                        );

                        binding.waveformView.setProgress(
                                0f
                        );

                        updateTime();
                    },

                    /*
                     * ERROR
                     */
                    (mp, what, extra) -> {

                        handler.removeCallbacks(
                                updateRunnable
                        );

                        if (binding != null) {

                            updatePlayButton(
                                    false
                            );

                            showToast(
                                    getString(
                                            R.string.playback_error
                                    )
                            );
                        }

                        return true;
                    }
            );

        } catch (Exception e) {

            showToast(
                    getString(
                            R.string.playback_error
                    )
            );

            finish();
        }
    }

    /*
     * =========================================================
     * PLAY BUTTON UI
     * =========================================================
     */

    private void updatePlayButton(
            boolean playing
    ) {

        if (binding == null) {
            return;
        }

        if (playing) {

            binding.btnPlay.setImageResource(
                    R.drawable.ic_pause_24
            );

            binding.btnPlay
                    .setContentDescription(
                            getString(
                                    R.string.player_pause_description
                            )
                    );

        } else {

            binding.btnPlay.setImageResource(
                    R.drawable.ic_play_24
            );

            binding.btnPlay
                    .setContentDescription(
                            getString(
                                    R.string.player_play_description
                            )
                    );
        }
    }

    /*
     * =========================================================
     * BUTTON MICRO ANIMATION
     * =========================================================
     */

    private void animatePlayButton() {

        if (binding == null) {
            return;
        }

        binding.btnPlay
                .animate()
                .cancel();

        binding.btnPlay.setScaleX(
                0.92f
        );

        binding.btnPlay.setScaleY(
                0.92f
        );

        binding.btnPlay
                .animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(150L)
                .start();
    }

    /*
     * =========================================================
     * WAVEFORM
     * =========================================================
     */

    private void loadWaveform() {

        int[] cachedWaveform =
                WaveformCache
                        .getInstance()
                        .get(
                                filePath
                        );

        /*
         * Уже есть в памяти.
         */
        if (cachedWaveform != null) {

            binding.waveformView
                    .setWaveform(
                            cachedWaveform
                    );

            return;
        }

        WaveformExtractor extractor =
                new WaveformExtractor();

        extractor.extract(

                new File(
                        filePath
                ),

                result -> {

                    if (binding == null
                            || isFinishing()
                            || isDestroyed()) {

                        return;
                    }

                    if (result == null) {
                        return;
                    }

                    WaveformCache
                            .getInstance()
                            .put(
                                    filePath,
                                    result
                            );

                    binding.waveformView
                            .setWaveform(
                                    result
                            );
                }
        );
    }

    /*
     * =========================================================
     * SHARE
     * =========================================================
     */

    private void shareRecord() {

        if (filePath == null
                || filePath.trim().isEmpty()) {

            return;
        }

        shareManager.share(
                filePath
        );
    }

    /*
     * =========================================================
     * DELETE
     * =========================================================
     */

    private void deleteRecord() {

        dialogManager.showDeleteDialog(
                () -> {

                    handler.removeCallbacks(
                            updateRunnable
                    );

                    /*
                     * Освобождаем MediaPlayer перед
                     * физическим удалением файла.
                     */
                    playerController.release();

                    File file =
                            new File(
                                    filePath
                            );

                    if (file.exists()
                            && !file.delete()) {

                        showToast(
                                getString(
                                        R.string.delete_error
                                )
                        );

                        /*
                         * Не удаляем запись из БД,
                         * если физический файл
                         * удалить не удалось.
                         */
                        return;
                    }

                    if (recordId != -1) {

                        viewModel.delete(
                                recordId
                        );
                    }

                    WaveformCache
                            .getInstance()
                            .remove(
                                    filePath
                            );

                    finish();
                }
        );
    }

    /*
     * =========================================================
     * UPDATE PLAYBACK POSITION
     * =========================================================
     */

    private void updateSeek() {

        if (binding == null) {
            return;
        }

        if (!playerController.isPlaying()) {
            return;
        }

        int currentPosition =
                playerController
                        .getCurrentPosition();

        int duration =
                playerController
                        .getDuration();

        binding.seekBar.setProgress(
                currentPosition
        );

        if (duration > 0) {

            float progress =
                    (float) currentPosition
                            / duration;

            /*
             * Дополнительная защита.
             */
            progress =
                    Math.max(
                            0f,
                            Math.min(
                                    1f,
                                    progress
                            )
                    );

            binding.waveformView
                    .setProgress(
                            progress
                    );
        }

        updateTime();

        handler.postDelayed(
                updateRunnable,
                300L
        );
    }

    /*
     * =========================================================
     * TIME
     * =========================================================
     */

    private void updateTime() {

        if (binding == null) {
            return;
        }

        if (playerController.getPlayer()
                == null) {

            return;
        }

        int current =
                playerController
                        .getCurrentPosition()
                        / 1000;

        int total =
                playerController
                        .getDuration()
                        / 1000;

        binding.txtTime.setText(

                getString(

                        R.string.player_time_format,

                        current / 60,

                        current % 60,

                        total / 60,

                        total % 60
                )
        );
    }

    /*
     * =========================================================
     * TOAST
     * =========================================================
     */

    private void showToast(
            String message
    ) {

        if (isFinishing()
                || isDestroyed()) {

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
     * ACTIVITY PAUSE
     * =========================================================
     */

    @Override
    protected void onPause() {

        super.onPause();

        handler.removeCallbacks(
                updateRunnable
        );

        if (playerController == null) {
            return;
        }

        if (playerController.isPlaying()) {

            playerController.pause();

            updateTime();
        }

        updatePlayButton(
                false
        );
    }

    /*
     * =========================================================
     * ACTIVITY RESUME
     * =========================================================
     */

    @Override
    protected void onResume() {

        super.onResume();

        /*
         * При возвращении в Player
         * воспроизведение автоматически
         * не запускаем.
         */
        updatePlayButton(
                false
        );
    }

    /*
     * =========================================================
     * DESTROY
     * =========================================================
     */

    @Override
    protected void onDestroy() {

        handler.removeCallbacks(
                updateRunnable
        );

        if (playerController != null) {

            playerController.release();
        }

        binding = null;

        super.onDestroy();
    }
}