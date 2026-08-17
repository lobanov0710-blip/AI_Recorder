package com.nicko.airecorder.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class WaveformView extends View {

    private static final int MAX_BARS = 250;

    private static final float BAR_WIDTH = 6f;
    private static final float BAR_SPACE = 4f;

    private static final float MIN_BAR_HEIGHT = 3f;

    // скорость движения волны
    private static final float SCROLL_SPEED = 1.2f;

    private final Paint playedPaint =
            new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint unplayedPaint =
            new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Integer> waveform =
            new ArrayList<>();

    private float progress = 0f;

    // смещение для плавной анимации
    private float animationOffset = 0f;

    private final Choreographer.FrameCallback frameCallback =
            new Choreographer.FrameCallback() {

                @Override
                public void doFrame(long frameTimeNanos) {

                    animationOffset += SCROLL_SPEED;

                    if (animationOffset >= BAR_WIDTH + BAR_SPACE) {

                        animationOffset = 0f;

                    }

                    invalidate();

                    Choreographer.getInstance()
                            .postFrameCallback(this);
                }

            };

    public WaveformView(Context context) {
        super(context);
        init();
    }

    public WaveformView(Context context,
                        AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WaveformView(Context context,
                        AttributeSet attrs,
                        int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    private void init() {

        playedPaint.setStyle(Paint.Style.STROKE);
        playedPaint.setStrokeCap(Paint.Cap.ROUND);
        playedPaint.setStrokeWidth(BAR_WIDTH);
        playedPaint.setColor(Color.parseColor("#4CAF50"));

        unplayedPaint.setStyle(Paint.Style.STROKE);
        unplayedPaint.setStrokeCap(Paint.Cap.ROUND);
        unplayedPaint.setStrokeWidth(BAR_WIDTH);
        unplayedPaint.setColor(Color.parseColor("#D8D8D8"));

        Choreographer.getInstance()
                .postFrameCallback(frameCallback);
    }
    /**
     * Загрузка готовой волны.
     */
    public void setWaveform(int[] data) {

        waveform.clear();

        if (data != null) {

            for (int value : data) {

                waveform.add(
                        Math.max(0,
                                Math.min(100, value))
                );
            }
        }
        invalidate();
    }
    /**
     * Добавление амплитуды во время записи.
     */
    public void addAmplitude(int amplitude) {

        amplitude = Math.max(
                0,
                Math.min(100, amplitude)
        );

        if (!waveform.isEmpty()) {

            int last =
                    waveform.get(waveform.size() - 1);

            amplitude =
                    (last + amplitude) / 2;
        }

        waveform.add(amplitude);

        while (waveform.size() > MAX_BARS) {

            waveform.remove(0);
        }
        animationOffset = 0f;

        invalidate();
    }
    /**
     * Прогресс воспроизведения.
     */
    public void setProgress(float progress) {

        this.progress =
                Math.max(
                        0f,
                        Math.min(1f, progress)
                );

        invalidate();
    }

    /**
     * Очистка волны.
     */
    public void clearWaveform() {

        waveform.clear();

        progress = 0f;

        animationOffset = 0f;

        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {

        super.onDraw(canvas);

        if (waveform.isEmpty()) {
            return;
        }

        float height = getHeight();

        float centerY = height / 2f;

        float totalWidth =
                waveform.size() * (BAR_WIDTH + BAR_SPACE);

        float startX =
                Math.max(
                        0,
                        getWidth() - totalWidth
                );

        startX -= animationOffset;

        int playedBars =
                (int) (progress * waveform.size());

        for (int i = 0; i < waveform.size(); i++) {

            float x =
                    startX +
                            i * (BAR_WIDTH + BAR_SPACE);

            if (x < -BAR_WIDTH) {
                continue;
            }

            if (x > getWidth() + BAR_WIDTH) {
                break;
            }

            float lineHeight =
                    waveform.get(i) / 100f * centerY;

            lineHeight =
                    Math.max(
                            MIN_BAR_HEIGHT,
                            lineHeight
                    );

            lineHeight =
                    Math.min(
                            centerY,
                            lineHeight
                    );

            Paint paint =
                    i < playedBars
                            ? playedPaint
                            : unplayedPaint;

            canvas.drawLine(
                    x,
                    centerY - lineHeight,
                    x,
                    centerY + lineHeight,
                    paint
            );
        }
    }

    @Override
    protected void onDetachedFromWindow() {

        super.onDetachedFromWindow();

        Choreographer.getInstance()
                .removeFrameCallback(frameCallback);
    }
}