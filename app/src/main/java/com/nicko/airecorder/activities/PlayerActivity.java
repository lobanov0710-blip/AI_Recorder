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

import java.io.File;

public class PlayerActivity extends AppCompatActivity {

    private ActivityPlayerBinding binding;

    private RecordViewModel viewModel;

    private PlayerController playerController;

    private ShareManager shareManager;

    private DialogManager dialogManager;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private final Runnable updateRunnable =
            this::updateSeek;

    private String filePath;

    private long recordId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        binding =
                ActivityPlayerBinding.inflate(
                        getLayoutInflater()
                );

        setContentView(binding.getRoot());

        viewModel =
                new ViewModelProvider(this)
                        .get(RecordViewModel.class);

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

        if (filePath == null || filePath.isEmpty()) {

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

        binding.txtTitle.setText(title);

        preparePlayer();

        loadWaveform();

        binding.btnPlay.setOnClickListener(v -> {

            if (playerController.getPlayer() == null) {
                return;
            }

            try {

                if (playerController.isPlaying()) {

                    playerController.pause();

                    binding.btnPlay.setText(
                            R.string.player_play
                    );

                    handler.removeCallbacks(
                            updateRunnable
                    );

                } else {

                    playerController.play();

                    binding.btnPlay.setText(
                            R.string.player_pause
                    );

                    updateSeek();

                }

            } catch (Exception e) {

                showToast(
                        getString(
                                R.string.playback_error
                        )
                );

            }

        });

        binding.btnShare.setOnClickListener(v ->
                shareRecord()
        );

        binding.btnDelete.setOnClickListener(v ->
                deleteRecord()
        );

        binding.seekBar.setOnSeekBarChangeListener(

                new SeekBar.OnSeekBarChangeListener() {

                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser
                    ) {

                        if (fromUser) {

                            playerController.seekTo(
                                    progress
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

                        binding.seekBar.setProgress(0);

                        binding.waveformView.setProgress(0);

                        binding.btnPlay.setText(
                                R.string.player_play
                        );

                        updateTime();

                    },

                    mp -> {

                        if (binding == null) {
                            return;
                        }

                        binding.btnPlay.setText(
                                R.string.player_play
                        );

                        binding.seekBar.setProgress(0);

                        binding.waveformView.setProgress(0);

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

                            binding.btnPlay.setText(
                                    R.string.player_play
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

    private void loadWaveform() {

        int[] waveform =
                WaveformCache
                        .getInstance()
                        .get(filePath);

        if (waveform != null) {

            binding.waveformView.setWaveform(
                    waveform
            );

            return;

        }

        WaveformExtractor extractor =
                new WaveformExtractor();

        extractor.extract(

                new File(filePath),

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

                    binding.waveformView.setWaveform(
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
                    new File(filePath);

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
                    .remove(filePath);

            finish();

        });

    }

    private void updateSeek() {

        if (!playerController.isPlaying()) {
            return;
        }

        int currentPosition =
                playerController.getCurrentPosition();

        binding.seekBar.setProgress(
                currentPosition
        );

        int duration =
                playerController.getDuration();

        if (duration > 0) {

            binding.waveformView.setProgress(

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

        if (playerController.getPlayer() == null) {
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

    private void showToast(String message) {

        if (isFinishing() || isDestroyed()) {
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

            binding.btnPlay.setText(
                    R.string.player_play
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

            binding.btnPlay.setText(
                    R.string.player_pause
            );

            updateSeek();

        } else {

            binding.btnPlay.setText(
                    R.string.player_play
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