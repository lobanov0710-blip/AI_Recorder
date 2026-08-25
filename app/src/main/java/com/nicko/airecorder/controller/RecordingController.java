package com.nicko.airecorder.controller;

import android.view.View;

import androidx.lifecycle.LifecycleOwner;

import com.nicko.airecorder.R;
import com.nicko.airecorder.databinding.ActivityMainBinding;
import com.nicko.airecorder.viewmodel.RecordViewModel;

public class RecordingController {

    private final ActivityMainBinding binding;

    private final RecordViewModel viewModel;

    public RecordingController(
            ActivityMainBinding binding,
            RecordViewModel viewModel
    ) {

        this.binding = binding;

        this.viewModel = viewModel;
    }

    public void observe(
            LifecycleOwner owner
    ) {

        viewModel
                .getRecordingState()
                .observe(
                        owner,
                        state -> {

                            switch (state) {

                                case IDLE:

                                    showIdleState();

                                    break;

                                case RECORDING:

                                    showRecordingState();

                                    break;

                                case PAUSED:

                                    showPausedState();

                                    break;
                            }

                        }
                );
    }

    private void showIdleState() {

        binding.btnRecord.setText(
                R.string.record_start
        );

        binding.btnStop.setVisibility(
                View.GONE
        );

        binding.txtRecordTime.setVisibility(
                View.GONE
        );

        binding.txtRecordingStatus.setVisibility(
                View.GONE
        );

        binding.waveformView.setVisibility(
                View.GONE
        );

        binding.waveformView.clearWaveform();
    }

    private void showRecordingState() {

        binding.btnRecord.setText(
                R.string.record_pause
        );

        binding.btnStop.setVisibility(
                View.VISIBLE
        );

        binding.txtRecordTime.setVisibility(
                View.VISIBLE
        );

        binding.txtRecordingStatus.setText(
                R.string.record_in_progress
        );

        binding.txtRecordingStatus.setVisibility(
                View.VISIBLE
        );

        binding.waveformView.setVisibility(
                View.VISIBLE
        );
    }

    private void showPausedState() {

        binding.btnRecord.setText(
                R.string.record_resume
        );

        binding.btnStop.setVisibility(
                View.VISIBLE
        );

        binding.txtRecordTime.setVisibility(
                View.VISIBLE
        );

        binding.txtRecordingStatus.setText(
                R.string.record_paused
        );

        binding.txtRecordingStatus.setVisibility(
                View.VISIBLE
        );

        binding.waveformView.setVisibility(
                View.VISIBLE
        );
    }
}