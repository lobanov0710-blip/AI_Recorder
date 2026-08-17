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

    /*
     * Амплитуда во время записи приходит примерно
     * каждые 80 мс из RecordTimer.
     *
     * За этот промежуток плавно сдвигаем waveform
     * на ширину одного бара вместе с промежутком.
     */
    private static final long LIVE_ANIMATION_DURATION_MS = 80L;

    private final Paint playedPaint =
            new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint unplayedPaint =
            new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Integer> waveform =
            new ArrayList<>();

    private float progress = 0f;

    private float animationOffset = 0f;

    private long animationStartTimeNanos = 0L;

    private boolean frameCallbackPosted = false;

    private final Choreographer.FrameCallback frameCallback =
            new Choreographer.FrameCallback() {

                @Override
                public void doFrame(long frameTimeNanos) {

                    frameCallbackPosted = false;

                    if (!isAttachedToWindow()) {
                        return;
                    }

                    if (animationStartTimeNanos == 0L) {

                        animationStartTimeNanos =
                                frameTimeNanos;

                    }

                    long elapsedNanos =
                            frameTimeNanos
                                    - animationStartTimeNanos;

                    float elapsedMs =
                            elapsedNanos / 1_000_000f;

                    float animationProgress =
                            Math.min(
                                    1f,
                                    elapsedMs
                                            / LIVE_ANIMATION_DURATION_MS
                            );

                    animationOffset =
                            (BAR_WIDTH + BAR_SPACE)
                                    * animationProgress;

                    invalidate();

                    if (animationProgress < 1f) {

                        postNextFrame();

                    }

                }

            };

    public WaveformView(Context context) {

        super(context);

        init();

    }

    public WaveformView(
            Context context,
            AttributeSet attrs
    ) {

        super(context, attrs);

        init();

    }

    public WaveformView(
            Context context,
            AttributeSet attrs,
            int defStyleAttr
    ) {

        super(
                context,
                attrs,
                defStyleAttr
        );

        init();

    }

    private void init() {

        playedPaint.setStyle(
                Paint.Style.STROKE
        );

        playedPaint.setStrokeCap(
                Paint.Cap.ROUND
        );

        playedPaint.setStrokeWidth(
                BAR_WIDTH
        );

        playedPaint.setColor(
                Color.parseColor("#4CAF50")
        );

        unplayedPaint.setStyle(
                Paint.Style.STROKE
        );

        unplayedPaint.setStrokeCap(
                Paint.Cap.ROUND
        );

        unplayedPaint.setStrokeWidth(
                BAR_WIDTH
        );

        unplayedPaint.setColor(
                Color.parseColor("#D8D8D8")
        );

    }

    /**
     * Загрузка готовой waveform.
     * Используется для уже сохранённой записи.
     */
    public void setWaveform(int[] data) {

        stopAnimation();

        waveform.clear();

        if (data != null) {

            for (int value : data) {

                waveform.add(

                        Math.max(
                                0,
                                Math.min(
                                        100,
                                        value
                                )
                        )

                );

            }

        }

        invalidate();

    }

    /**
     * Добавление новой амплитуды
     * во время активной записи.
     */
    public void addAmplitude(int amplitude) {

        amplitude =
                Math.max(
                        0,
                        Math.min(
                                100,
                                amplitude
                        )
                );

        if (!waveform.isEmpty()) {

            int last =
                    waveform.get(
                            waveform.size() - 1
                    );

            amplitude =
                    (last + amplitude) / 2;

        }

        waveform.add(amplitude);

        while (waveform.size() > MAX_BARS) {

            waveform.remove(0);

        }

        startAnimation();

    }

    /**
     * Прогресс воспроизведения.
     */
    public void setProgress(float progress) {

        this.progress =
                Math.max(
                        0f,
                        Math.min(
                                1f,
                                progress
                        )
                );

        invalidate();

    }

    /**
     * Очистка waveform.
     */
    public void clearWaveform() {

        stopAnimation();

        waveform.clear();

        progress = 0f;

        invalidate();

    }

    private void startAnimation() {

        animationStartTimeNanos = 0L;

        animationOffset = 0f;

        postNextFrame();

    }

    private void postNextFrame() {

        if (frameCallbackPosted) {
            return;
        }

        if (!isAttachedToWindow()) {
            return;
        }

        frameCallbackPosted = true;

        Choreographer.getInstance()
                .postFrameCallback(
                        frameCallback
                );

    }

    private void stopAnimation() {

        if (frameCallbackPosted) {

            Choreographer.getInstance()
                    .removeFrameCallback(
                            frameCallback
                    );

            frameCallbackPosted = false;

        }

        animationStartTimeNanos = 0L;

        animationOffset = 0f;

    }

    @Override
    protected void onDraw(Canvas canvas) {

        super.onDraw(canvas);

        if (waveform.isEmpty()) {
            return;
        }

        float height =
                getHeight();

        float centerY =
                height / 2f;

        float totalWidth =
                waveform.size()
                        * (BAR_WIDTH + BAR_SPACE);

        float startX =
                Math.max(
                        0,
                        getWidth() - totalWidth
                );

        startX -= animationOffset;

        int playedBars =
                (int) (
                        progress
                                * waveform.size()
                );

        for (
                int i = 0;
                i < waveform.size();
                i++
        ) {

            float x =
                    startX
                            + i
                            * (BAR_WIDTH + BAR_SPACE);

            if (x < -BAR_WIDTH) {
                continue;
            }

            if (x > getWidth() + BAR_WIDTH) {
                break;
            }

            float lineHeight =
                    waveform.get(i)
                            / 100f
                            * centerY;

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

        stopAnimation();

        super.onDetachedFromWindow();

    }

}