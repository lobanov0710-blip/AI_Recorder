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

    public void observe(LifecycleOwner owner) {

        viewModel.getRecordingState().observe(owner, state -> {

            switch (state) {

                case IDLE:

                    updateUi(
                            R.string.record_start,
                            false,
                            false,
                            true
                    );

                    break;

                case RECORDING:

                    updateUi(
                            R.string.record_pause,
                            true,
                            true,
                            false
                    );

                    break;

                case PAUSED:

                    updateUi(
                            R.string.record_resume,
                            true,
                            true,
                            false
                    );

                    break;
            }

        });

    }

    private void updateUi(
            int buttonTextResId,
            boolean showStop,
            boolean showTimer,
            boolean clearWaveform
    ) {

        binding.btnRecord.setText(buttonTextResId);

        binding.btnStop.setVisibility(
                showStop ? View.VISIBLE : View.GONE
        );

        binding.txtRecordTime.setVisibility(
                showTimer ? View.VISIBLE : View.GONE
        );

        if (clearWaveform) {

            binding.waveformView.clearWaveform();

        }

    }

}