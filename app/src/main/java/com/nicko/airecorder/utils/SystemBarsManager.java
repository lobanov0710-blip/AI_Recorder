package com.nicko.airecorder.utils;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

public final class SystemBarsManager {

    private SystemBarsManager() {
    }

    public static void apply(
            Activity activity,
            View root
    ) {

        if (activity == null || root == null) {
            return;
        }

        /*
         * Разрешаем приложению рисоваться
         * под системными панелями.
         *
         * Этот API доступен в AndroidX Core
         * значительно дольше, чем новый
         * WindowCompat.enableEdgeToEdge().
         */
        WindowCompat.setDecorFitsSystemWindows(
                activity.getWindow(),
                false
        );

        activity.getWindow().setStatusBarColor(
                Color.TRANSPARENT
        );

        activity.getWindow().setNavigationBarColor(
                Color.TRANSPARENT
        );

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(
                        activity.getWindow(),
                        root
                );

        /*
         * UI сейчас тёмный, поэтому
         * системные значки должны быть светлыми.
         */
        controller.setAppearanceLightStatusBars(
                false
        );

        controller.setAppearanceLightNavigationBars(
                false
        );

        /*
         * Сохраняем исходные XML padding.
         * Иначе повторные dispatchInsets
         * постепенно увеличивали бы отступы.
         */
        final int initialLeft =
                root.getPaddingLeft();

        final int initialTop =
                root.getPaddingTop();

        final int initialRight =
                root.getPaddingRight();

        final int initialBottom =
                root.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(
                root,
                (view, windowInsets) -> {

                    Insets systemBars =
                            windowInsets.getInsets(
                                    WindowInsetsCompat.Type.systemBars()
                                            | WindowInsetsCompat.Type.displayCutout()
                            );

                    view.setPadding(
                            initialLeft
                                    + systemBars.left,

                            initialTop
                                    + systemBars.top,

                            initialRight
                                    + systemBars.right,

                            initialBottom
                                    + systemBars.bottom
                    );

                    return windowInsets;
                }
        );

        ViewCompat.requestApplyInsets(
                root
        );
    }
}