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
import com.nicko.airecorder.utils.WaveformCache;
import com.nicko.airecorder.utils.WaveformExtractor;
import com.nicko.airecorder.viewmodel.RecordViewModel;
import com.nicko.airecorder.utils.SystemBarsManager;

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

        binding =
                ActivityPlayerBinding.inflate(
                        getLayoutInflater()
                );

        setContentView(
                binding.getRoot()
        );
        SystemBarsManager.apply(
                this,
                binding.getRoot()
        );

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

        filePath =
                getIntent()
                        .getStringExtra(
                                "filePath"
                        );

        if (filePath == null
                || filePath.isEmpty()) {

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

        preparePlayer();

        loadWaveform();

        binding.btnPlay.setOnClickListener(v ->
                handlePlayClick()
        );

        binding.btnBack.setOnClickListener(
                v -> finish()
        );

        binding.btnShare.setOnClickListener(v ->
                shareRecord()
        );

        binding.btnDelete.setOnClickListener(v ->
                deleteRecord()
        );

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
                            }

                            @Override
                            public void onStopTrackingTouch(
                                    SeekBar seekBar
                            ) {
                            }
                        }
                );
    }

    private void handlePlayClick() {

        if (playerController.getPlayer()
                == null) {

            return;
        }

        try {

            if (playerController.isPlaying()) {

                playerController.pause();

                updatePlayButton(
                        false
                );

                handler.removeCallbacks(
                        updateRunnable
                );

                return;
            }

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

    private void preparePlayer() {

        try {

            playerController.prepare(

                    filePath,

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

                        binding.waveformView
                                .setProgress(
                                        0
                                );

                        updatePlayButton(
                                false
                        );

                        updateTime();
                    },

                    mp -> {

                        if (binding == null) {
                            return;
                        }

                        updatePlayButton(
                                false
                        );

                        binding.seekBar.setProgress(
                                0
                        );

                        binding.waveformView
                                .setProgress(
                                        0
                                );

                        updateTime();

                        handler.removeCallbacks(
                                updateRunnable
                        );
                    },

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

            finish();
        }
    }

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

            return;
        }

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

    private void animatePlayButton() {

        if (binding == null) {
            return;
        }

        binding.btnPlay
                .animate()
                .cancel();

        binding.btnPlay.setScaleX(
                0.94f
        );

        binding.btnPlay.setScaleY(
                0.94f
        );

        binding.btnPlay
                .animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(150L)
                .start();
    }

    private void loadWaveform() {

        int[] waveform =
                WaveformCache
                        .getInstance()
                        .get(
                                filePath
                        );

        if (waveform != null) {

            binding.waveformView
                    .setWaveform(
                            waveform
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

    private void shareRecord() {

        shareManager.share(
                filePath
        );
    }

    private void deleteRecord() {

        dialogManager.showDeleteDialog(() -> {

            handler.removeCallbacks(
                    updateRunnable
            );

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
        });
    }

    private void updateSeek() {

        if (!playerController.isPlaying()) {
            return;
        }

        int currentPosition =
                playerController
                        .getCurrentPosition();

        binding.seekBar.setProgress(
                currentPosition
        );

        int duration =
                playerController
                        .getDuration();

        if (duration > 0) {

            binding.waveformView
                    .setProgress(

                            (float) currentPosition
                                    / duration
                    );
        }

        updateTime();

        handler.postDelayed(
                updateRunnable,
                300
        );
    }

    private void updateTime() {

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

    @Override
    protected void onPause() {

        super.onPause();

        if (playerController.isPlaying()) {

            playerController.pause();

            updateTime();

            updatePlayButton(
                    false
            );
        }

        handler.removeCallbacks(
                updateRunnable
        );
    }

    @Override
    protected void onResume() {

        super.onResume();

        if (playerController.isPlaying()) {

            updatePlayButton(
                    true
            );

            updateSeek();

        } else {

            updatePlayButton(
                    false
            );
        }
    }

    @Override
    protected void onDestroy() {

        handler.removeCallbacks(
                updateRunnable
        );

        playerController.release();

        binding = null;

        super.onDestroy();
    }
}