package com.nicko.airecorder.activities;

import java.io.File;

import android.os.Bundle;
import android.os.Handler;
import android.widget.SeekBar;
import android.content.Intent;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.nicko.airecorder.viewmodel.RecordViewModel;
import com.nicko.airecorder.databinding.ActivityPlayerBinding;
import com.nicko.airecorder.controller.PlayerController;
import com.nicko.airecorder.controller.FileController;
import com.nicko.airecorder.utils.WaveformCache;
import com.nicko.airecorder.utils.WaveformExtractor;


public class PlayerActivity extends AppCompatActivity {
    private RecordViewModel viewModel;
    private ActivityPlayerBinding binding;
    private PlayerController playerController;
    private final Handler handler =
            new Handler(android.os.Looper.getMainLooper());
    private final Runnable updateRunnable =
            this::updateSeek;
    private String filePath;
    private long recordId;
    private FileController fileController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        binding = ActivityPlayerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        viewModel =

                new ViewModelProvider(this)

                        .get(RecordViewModel.class);

        playerController = new PlayerController();

        fileController = new FileController(this);

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

                if (playerController.getPlayer().isPlaying()) {

                    playerController.pause();

                    binding.btnPlay.setText("▶");

                    handler.removeCallbacks(updateRunnable);

                } else {

                    playerController.play();

                    binding.btnPlay.setText("⏸");

                    updateSeek();

                }

            } catch (Exception e) {

                showToast("Ошибка воспроизведения");

            }

        });

        binding.btnShare.setOnClickListener(v -> {

            shareRecord();

        });

        binding.btnDelete.setOnClickListener(v -> {

            deleteRecord();

        });

        binding.seekBar.setOnSeekBarChangeListener(

                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(

                            SeekBar seekBar,

                            int progress,

                            boolean fromUser

                    ) {

                        if (fromUser) {

                            playerController.seekTo(progress);

                        }

                    }
                    @Override
                    public void onStartTrackingTouch(
                            SeekBar seekBar
                    ) {}
                    @Override
                    public void onStopTrackingTouch(
                            SeekBar seekBar
                    ) {}

                }

        );

    }

    @Override
    protected void onPause() {

        super.onPause();

        if (playerController.isPlaying()) {

            playerController.pause();

            updateTime();

            binding.btnPlay.setText("▶");

        }

        handler.removeCallbacks(updateRunnable);
    }

    @Override
    protected void onResume() {

        super.onResume();

        if (playerController.isPlaying()) {
            binding.btnPlay.setText("⏸");
            updateSeek();
        } else {
            binding.btnPlay.setText("▶");
        }
    }
    private void showToast(String message) {

        if (isFinishing()) {
            return;
        }

        android.widget.Toast.makeText(
                this,
                message,
                android.widget.Toast.LENGTH_SHORT
        ).show();
    }
    private void preparePlayer() {

        try {

            WaveformExtractor extractor = new WaveformExtractor();

            extractor.extract(

                    new java.io.File(filePath),

                    waveform -> binding.waveformView.setWaveform(waveform)

            );

            playerController.prepare(

                    filePath,

                    mp -> {

                        binding.seekBar.setMax(mp.getDuration());

                        binding.seekBar.setProgress(0);

                        binding.waveformView.setProgress(0);

                        binding.btnPlay.setText("▶");

                        updateTime();

                    },

                    mp -> {

                        binding.btnPlay.setText("▶");

                        binding.seekBar.setProgress(0);

                        binding.waveformView.setProgress(0);

                        updateTime();

                        handler.removeCallbacks(updateRunnable);

                    },

                    (mp, what, extra) -> {

                        binding.btnPlay.setText("▶");

                        handler.removeCallbacks(updateRunnable);

                        showToast("Ошибка воспроизведения");

                        return true;

                    }

            );

        } catch (Exception e) {

            finish();

        }

    }
    private void updateSeek() {

        if (!playerController.isPlaying()) {
            return;
        }

        binding.seekBar.setProgress(
                playerController.getCurrentPosition()
        );

        int duration = playerController.getDuration();

        if (duration > 0) {

            binding.waveformView.setProgress(

                    (float) playerController.getCurrentPosition()
                            / duration

            );

        }

        updateTime();

        handler.postDelayed(updateRunnable, 300);

    }
    private void updateTime() {

        if (playerController.getPlayer() == null) {
            return;
        }

        int current = playerController.getCurrentPosition() / 1000;
        int total = playerController.getDuration() / 1000;

        binding.txtTime.setText(

                String.format(

                        java.util.Locale.getDefault(),

                        "%02d:%02d / %02d:%02d",

                        current / 60,
                        current % 60,

                        total / 60,
                        total % 60

                )

        );
    }

    private void loadWaveform() {

        int[] waveform = WaveformCache.getInstance().get(filePath);

        if (waveform != null) {

            binding.waveformView.setWaveform(waveform);

            return;
        }

        WaveformExtractor extractor = new WaveformExtractor();

        extractor.extract(

                new java.io.File(filePath),

                result -> runOnUiThread(() -> {

                    WaveformCache
                            .getInstance()
                            .put(filePath, result);

                    binding.waveformView.setWaveform(result);

                })

        );

    }
    private void shareRecord(){

        java.io.File file =

                new java.io.File(
                        filePath
                );

        if (!file.exists()) {

            showToast("Файл не найден");

            return;
        }

        android.net.Uri uri =

                androidx.core.content.FileProvider

                        .getUriForFile(

                                this,

                                getPackageName()
                                        +
                                        ".provider",

                                file

                        );

        android.content.Intent intent =

                new android.content.Intent(

                        android.content.Intent.ACTION_SEND

                );

        intent.setType(
                "audio/mp4"
        );

        intent.putExtra(

                android.content.Intent.EXTRA_STREAM,

                uri

        );

        intent.addFlags(

                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION

        );

        try {
            startActivity(
                    Intent.createChooser( intent, "Поделиться записью" )
            );
        }
        catch (android.content.ActivityNotFoundException e) {

            showToast("Не найдено приложение для отправки");

        }
    }
    private void deleteRecord() {

        new androidx.appcompat.app.AlertDialog.Builder(this)

                .setTitle("Удаление")

                .setMessage("Удалить запись?")

                .setPositiveButton("Удалить", (dialog, which) -> {

                    handler.removeCallbacks(updateRunnable);

                    playerController.release();

                    java.io.File file = new java.io.File(filePath);

                    if (file.exists() && !file.delete()) {

                        showToast("Не удалось удалить файл");

                        return;

                    }

                    if (recordId != -1) {

                        viewModel.delete(recordId);

                    }

                    WaveformCache.getInstance().remove(filePath);

                    finish();

                })

                .setNegativeButton("Отмена", null)

                .show();

    }
    @Override
    protected void onDestroy(){

        handler.removeCallbacks(updateRunnable);

        playerController.release();
        binding = null;
        super.onDestroy();
    }
}