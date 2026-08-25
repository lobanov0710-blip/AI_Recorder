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

        binding.btnRecord.setImageResource(
                R.drawable.ic_mic_24
        );

        binding.btnRecord.setBackgroundResource(
                R.drawable.bg_record_idle
        );

        binding.btnRecord.setContentDescription(
                binding.getRoot()
                        .getContext()
                        .getString(
                                R.string.record_start
                        )
        );

        binding.txtRecordAction.setText(
                R.string.record_start
        );

        binding.stopContainer.setVisibility(
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

        binding.btnRecord.setImageResource(
                R.drawable.ic_pause_24
        );

        binding.btnRecord.setBackgroundResource(
                R.drawable.bg_record_active
        );

        binding.btnRecord.setContentDescription(
                binding.getRoot()
                        .getContext()
                        .getString(
                                R.string.record_pause
                        )
        );

        binding.txtRecordAction.setText(
                R.string.record_pause
        );

        binding.stopContainer.setVisibility(
                View.VISIBLE
        );

        binding.txtRecordTime.setVisibility(
                View.VISIBLE
        );

        binding.txtRecordingStatus.setText(
                R.string.record_in_progress
        );

        binding.txtRecordingStatus.setTextColor(
                androidx.core.content.ContextCompat
                        .getColor(
                                binding.getRoot()
                                        .getContext(),
                                R.color.ai_record
                        )
        );

        binding.txtRecordingStatus.setVisibility(
                View.VISIBLE
        );

        binding.waveformView.setVisibility(
                View.VISIBLE
        );

        animatePrimaryButton();
    }

    private void showPausedState() {

        binding.btnRecord.setImageResource(
                R.drawable.ic_play_24
        );

        binding.btnRecord.setBackgroundResource(
                R.drawable.bg_record_paused
        );

        binding.btnRecord.setContentDescription(
                binding.getRoot()
                        .getContext()
                        .getString(
                                R.string.record_resume
                        )
        );

        binding.txtRecordAction.setText(
                R.string.record_resume
        );

        binding.stopContainer.setVisibility(
                View.VISIBLE
        );

        binding.txtRecordTime.setVisibility(
                View.VISIBLE
        );

        binding.txtRecordingStatus.setText(
                R.string.record_paused
        );

        binding.txtRecordingStatus.setTextColor(
                androidx.core.content.ContextCompat
                        .getColor(
                                binding.getRoot()
                                        .getContext(),
                                R.color.ai_text_secondary
                        )
        );

        binding.txtRecordingStatus.setVisibility(
                View.VISIBLE
        );

        binding.waveformView.setVisibility(
                View.VISIBLE
        );

        animatePrimaryButton();
    }

    private void animatePrimaryButton() {

        binding.btnRecord
                .animate()
                .cancel();

        binding.btnRecord.setScaleX(
                0.94f
        );

        binding.btnRecord.setScaleY(
                0.94f
        );

        binding.btnRecord
                .animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(160L)
                .start();
    }
}