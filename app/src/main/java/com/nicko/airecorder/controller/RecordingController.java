package com.nicko.airecorder.controller;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.nicko.airecorder.R;
import com.nicko.airecorder.databinding.ActivityMainBinding;
import com.nicko.airecorder.utils.MotionUtils;
import com.nicko.airecorder.viewmodel.RecordViewModel;

public class RecordingController {

    /*
     * =========================================================
     * CONSTANTS
     * =========================================================
     */

    private static final long STATE_ANIMATION_DURATION_MS =
            170L;

    private static final long IDLE_PULSE_DURATION_MS =
            720L;

    private static final long IDLE_PULSE_DELAY_MS =
            260L;

    private static final float STATE_START_SCALE =
            0.94f;

    private static final float IDLE_PULSE_SCALE =
            1.035f;

    /*
     * =========================================================
     * DEPENDENCIES
     * =========================================================
     */

    private final ActivityMainBinding binding;

    private final RecordViewModel viewModel;

    /*
     * =========================================================
     * ANIMATION
     * =========================================================
     */

    private Animator primaryButtonAnimator;

    /*
     * =========================================================
     * CONSTRUCTOR
     * =========================================================
     */

    public RecordingController(
            ActivityMainBinding binding,
            RecordViewModel viewModel
    ) {

        this.binding =
                binding;

        this.viewModel =
                viewModel;
    }

    /*
     * =========================================================
     * OBSERVE STATE
     * =========================================================
     */

    public void observe(
            LifecycleOwner owner
    ) {

        viewModel
                .getRecordingState()
                .observe(
                        owner,
                        state -> {

                            if (state == null) {
                                return;
                            }

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

    /*
     * =========================================================
     * IDLE
     * =========================================================
     */

    private void showIdleState() {

        cancelPrimaryButtonAnimation();

        resetPrimaryButtonTransform();

        /*
         * Icon.
         */
        binding.btnRecord.setImageResource(
                R.drawable.ic_mic_24
        );

        /*
         * Background.
         */
        binding.btnRecord.setBackgroundResource(
                R.drawable.bg_record_idle
        );

        /*
         * Accessibility.
         */
        binding.btnRecord.setContentDescription(
                getContext()
                        .getString(
                                R.string.record_start
                        )
        );

        /*
         * Action label.
         */
        binding.txtRecordAction.setText(
                R.string.record_start
        );

        /*
         * Stop button hidden.
         */
        binding.stopContainer.setVisibility(
                View.GONE
        );

        /*
         * Timer hidden.
         */
        binding.txtRecordTime.setVisibility(
                View.GONE
        );

        /*
         * Status hidden.
         */
        binding.txtRecordingStatus.setVisibility(
                View.GONE
        );

        /*
         * Live waveform hidden.
         */
        binding.waveformView.setVisibility(
                View.GONE
        );

        binding.waveformView.clearWaveform();

        /*
         * Один мягкий pulse.
         *
         * Не бесконечный:
         * не отвлекает пользователя
         * и не расходует ресурсы постоянно.
         */
        animateIdlePulse();
    }

    /*
     * =========================================================
     * RECORDING
     * =========================================================
     */

    private void showRecordingState() {

        cancelPrimaryButtonAnimation();

        resetPrimaryButtonTransform();

        /*
         * Pause icon.
         */
        binding.btnRecord.setImageResource(
                R.drawable.ic_pause_24
        );

        /*
         * Active red state.
         */
        binding.btnRecord.setBackgroundResource(
                R.drawable.bg_record_active
        );

        /*
         * Accessibility.
         */
        binding.btnRecord.setContentDescription(
                getContext()
                        .getString(
                                R.string.record_pause
                        )
        );

        /*
         * Action label.
         */
        binding.txtRecordAction.setText(
                R.string.record_pause
        );

        /*
         * Stop becomes available.
         */
        binding.stopContainer.setVisibility(
                View.VISIBLE
        );

        /*
         * Timer.
         */
        binding.txtRecordTime.setVisibility(
                View.VISIBLE
        );

        /*
         * Status.
         */
        binding.txtRecordingStatus.setText(
                R.string.record_in_progress
        );

        binding.txtRecordingStatus.setTextColor(
                ContextCompat.getColor(
                        getContext(),
                        R.color.ai_record
                )
        );

        binding.txtRecordingStatus.setVisibility(
                View.VISIBLE
        );

        /*
         * Live waveform.
         */
        binding.waveformView.setVisibility(
                View.VISIBLE
        );

        /*
         * Короткий tactile transition.
         */
        animateStateTransition();
    }

    /*
     * =========================================================
     * PAUSED
     * =========================================================
     */

    private void showPausedState() {

        cancelPrimaryButtonAnimation();

        resetPrimaryButtonTransform();

        /*
         * Resume icon.
         */
        binding.btnRecord.setImageResource(
                R.drawable.ic_play_24
        );

        /*
         * Paused appearance.
         */
        binding.btnRecord.setBackgroundResource(
                R.drawable.bg_record_paused
        );

        /*
         * Accessibility.
         */
        binding.btnRecord.setContentDescription(
                getContext()
                        .getString(
                                R.string.record_resume
                        )
        );

        /*
         * Action label.
         */
        binding.txtRecordAction.setText(
                R.string.record_resume
        );

        binding.stopContainer.setVisibility(
                View.VISIBLE
        );

        binding.txtRecordTime.setVisibility(
                View.VISIBLE
        );

        /*
         * Status.
         */
        binding.txtRecordingStatus.setText(
                R.string.record_paused
        );

        binding.txtRecordingStatus.setTextColor(
                ContextCompat.getColor(
                        getContext(),
                        R.color.ai_text_secondary
                )
        );

        binding.txtRecordingStatus.setVisibility(
                View.VISIBLE
        );

        binding.waveformView.setVisibility(
                View.VISIBLE
        );

        /*
         * Короткий tactile transition.
         */
        animateStateTransition();
    }

    /*
     * =========================================================
     * IDLE MICRO-PULSE
     * =========================================================
     */

    private void animateIdlePulse() {

        /*
         * Уважаем системную настройку
         * Reduce / Remove animations.
         */
        if (!MotionUtils.areAnimationsEnabled(
                getContext()
        )) {

            resetPrimaryButtonTransform();

            return;
        }

        PropertyValuesHolder scaleX =
                PropertyValuesHolder.ofFloat(
                        View.SCALE_X,
                        1f,
                        IDLE_PULSE_SCALE,
                        1f
                );

        PropertyValuesHolder scaleY =
                PropertyValuesHolder.ofFloat(
                        View.SCALE_Y,
                        1f,
                        IDLE_PULSE_SCALE,
                        1f
                );

        ObjectAnimator animator =
                ObjectAnimator.ofPropertyValuesHolder(
                        binding.btnRecord,
                        scaleX,
                        scaleY
                );

        animator.setStartDelay(
                IDLE_PULSE_DELAY_MS
        );

        animator.setDuration(
                IDLE_PULSE_DURATION_MS
        );

        animator.setInterpolator(
                new AccelerateDecelerateInterpolator()
        );

        primaryButtonAnimator =
                animator;

        animator.start();
    }

    /*
     * =========================================================
     * STATE TRANSITION
     * =========================================================
     */

    private void animateStateTransition() {

        if (!MotionUtils.areAnimationsEnabled(
                getContext()
        )) {

            resetPrimaryButtonTransform();

            return;
        }

        PropertyValuesHolder scaleX =
                PropertyValuesHolder.ofFloat(
                        View.SCALE_X,
                        STATE_START_SCALE,
                        1f
                );

        PropertyValuesHolder scaleY =
                PropertyValuesHolder.ofFloat(
                        View.SCALE_Y,
                        STATE_START_SCALE,
                        1f
                );

        ObjectAnimator animator =
                ObjectAnimator.ofPropertyValuesHolder(
                        binding.btnRecord,
                        scaleX,
                        scaleY
                );

        animator.setDuration(
                STATE_ANIMATION_DURATION_MS
        );

        animator.setInterpolator(
                new DecelerateInterpolator()
        );

        primaryButtonAnimator =
                animator;

        animator.start();
    }

    /*
     * =========================================================
     * CANCEL ANIMATION
     * =========================================================
     */

    private void cancelPrimaryButtonAnimation() {

        if (primaryButtonAnimator != null) {

            primaryButtonAnimator.cancel();

            primaryButtonAnimator = null;
        }

        /*
         * На всякий случай также останавливаем
         * ViewPropertyAnimator из старого кода
         * или Activity lifecycle.
         */
        binding.btnRecord
                .animate()
                .cancel();
    }

    /*
     * =========================================================
     * RESET TRANSFORM
     * =========================================================
     */

    private void resetPrimaryButtonTransform() {

        binding.btnRecord.setScaleX(
                1f
        );

        binding.btnRecord.setScaleY(
                1f
        );

        binding.btnRecord.setAlpha(
                1f
        );
    }

    /*
     * =========================================================
     * CONTEXT
     * =========================================================
     */

    private Context getContext() {

        return binding
                .getRoot()
                .getContext();
    }
}