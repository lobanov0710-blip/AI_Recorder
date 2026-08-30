package com.nicko.airecorder.utils;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

public final class MotionUtils {

    private MotionUtils() {
        // Utility class.
    }

    /**
     * Возвращает true, если системные анимации разрешены.
     *
     * Android 8+:
     * используем официальный ValueAnimator.areAnimatorsEnabled().
     *
     * Android 7.x:
     * проверяем системный animator_duration_scale.
     */
    public static boolean areAnimationsEnabled(
            Context context
    ) {

        if (context == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.O) {

            return ValueAnimator
                    .areAnimatorsEnabled();
        }

        try {

            float scale =
                    Settings.Global.getFloat(
                            context.getContentResolver(),
                            Settings.Global.ANIMATOR_DURATION_SCALE,
                            1f
                    );

            return scale > 0f;

        } catch (Exception ignored) {

            /*
             * Если старое устройство или OEM
             * не позволяет корректно прочитать настройку,
             * оставляем анимации включёнными.
             */
            return true;
        }
    }
}