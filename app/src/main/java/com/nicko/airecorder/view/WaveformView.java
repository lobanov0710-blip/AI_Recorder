package com.nicko.airecorder.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class WaveformView extends View {

    /*
     * =========================================================
     * DATA
     * =========================================================
     */

    private static final int MAX_LIVE_POINTS =
            900;

    /*
     * RecordTimer отправляет amplitude
     * примерно каждые 80 мс.
     */
    private static final float EXPECTED_SAMPLE_INTERVAL_MS =
            80f;

    /*
     * Держим небольшой визуальный буфер.
     *
     * Благодаря этому waveform не успевает
     * останавливаться между amplitude updates.
     */
    private static final float LIVE_BUFFER_POINTS =
            0.85f;

    /*
     * Если новых amplitude нет дольше этого
     * времени, считаем что запись поставлена
     * на Pause / остановлена.
     */
    private static final long LIVE_IDLE_THRESHOLD_MS =
            180L;

    /*
     * Положение NOW/playhead.
     *
     * 70% ширины:
     * больше истории видно слева.
     */
    private static final float PLAYHEAD_POSITION =
            0.70f;

    /*
     * Playback smoothing.
     */
    private static final float PLAYBACK_FOLLOW_TIME_MS =
            70f;

    /*
     * Амплитуда:
     * быстрый attack, медленный release.
     */
    private static final float LEVEL_ATTACK_MS =
            40f;

    private static final float LEVEL_RELEASE_MS =
            150f;

    /*
     * =========================================================
     * GEOMETRY
     * =========================================================
     */

    private float pointStep;

    private float minHalfHeight;

    private float verticalPadding;

    private float waveformStrokeWidth;

    private float waveformGlowWidth;

    private float centerLineWidth;

    private float playheadWidth;

    private float playheadGlowWidth;

    private float playheadDotMinRadius;

    private float playheadDotMaxRadius;

    /*
     * =========================================================
     * COLORS
     * =========================================================
     */

    private int primaryColor;

    private int secondaryColor;

    /*
     * =========================================================
     * PAINTS
     * =========================================================
     */

    private final Paint activeFillPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint activeStrokePaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint activeGlowPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint inactiveFillPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint inactiveStrokePaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint centerLinePaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint playheadGlowPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint playheadPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint playheadDotPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    /*
     * =========================================================
     * WAVEFORM DATA
     * =========================================================
     */

    private final List<Integer> waveform =
            new ArrayList<>();

    private final Path waveformPath =
            new Path();

    /*
     * Переиспользуемые массивы.
     *
     * Не создаём WavePoint объекты
     * на каждом кадре.
     */
    private float[] pointX =
            new float[128];

    private float[] pointHeight =
            new float[128];

    private int visiblePointCount =
            0;

    /*
     * =========================================================
     * MODE
     * =========================================================
     */

    private boolean playbackMode =
            false;

    /*
     * =========================================================
     * LIVE STATE
     * =========================================================
     */

    /*
     * Это не integer index.
     *
     * Например:
     *
     * 25.37
     *
     * означает, что waveform находится
     * между sample 25 и 26.
     */
    private float displayedLiveIndex =
            0f;

    private float targetLiveIndex =
            0f;

    /*
     * Текущий визуальный уровень.
     *
     * Используем также для playhead dot.
     */
    private float displayedLiveLevel =
            0f;

    private float targetLiveLevel =
            0f;

    private long lastAmplitudeTimeMs =
            0L;

    /*
     * =========================================================
     * PLAYBACK STATE
     * =========================================================
     */

    private float displayedProgress =
            0f;

    private float targetProgress =
            0f;

    /*
     * =========================================================
     * FRAME STATE
     * =========================================================
     */

    private long lastFrameTimeNanos =
            0L;

    private boolean frameCallbackPosted =
            false;

    /*
     * =========================================================
     * CHOREOGRAPHER
     * =========================================================
     */

    private final Choreographer.FrameCallback frameCallback =
            new Choreographer.FrameCallback() {

                @Override
                public void doFrame(
                        long frameTimeNanos
                ) {

                    frameCallbackPosted =
                            false;

                    if (!isAttachedToWindow()) {
                        return;
                    }

                    float deltaMs;

                    if (lastFrameTimeNanos == 0L) {

                        deltaMs =
                                16f;

                    } else {

                        deltaMs =
                                (
                                        frameTimeNanos
                                                - lastFrameTimeNanos
                                )
                                        / 1_000_000f;

                        /*
                         * Защита от огромного скачка
                         * после background / freeze.
                         */
                        deltaMs =
                                Math.min(
                                        deltaMs,
                                        50f
                                );
                    }

                    lastFrameTimeNanos =
                            frameTimeNanos;

                    boolean continueAnimation;

                    if (playbackMode) {

                        continueAnimation =
                                updatePlayback(
                                        deltaMs
                                );

                    } else {

                        continueAnimation =
                                updateLive(
                                        deltaMs
                                );
                    }

                    invalidate();

                    if (continueAnimation) {

                        postNextFrame();

                    } else {

                        lastFrameTimeNanos =
                                0L;
                    }
                }
            };

    /*
     * =========================================================
     * CONSTRUCTORS
     * =========================================================
     */

    public WaveformView(
            Context context
    ) {

        super(
                context
        );

        init();
    }

    public WaveformView(
            Context context,
            @Nullable AttributeSet attrs
    ) {

        super(
                context,
                attrs
        );

        init();
    }

    public WaveformView(
            Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr
    ) {

        super(
                context,
                attrs,
                defStyleAttr
        );

        init();
    }

    /*
     * =========================================================
     * INIT
     * =========================================================
     */

    private void init() {

        /*
         * Более плотная и спокойная геометрия.
         */
        pointStep =
                dp(
                        6.5f
                );

        minHalfHeight =
                dp(
                        1.3f
                );

        verticalPadding =
                dp(
                        12f
                );

        waveformStrokeWidth =
                dp(
                        1.35f
                );

        waveformGlowWidth =
                dp(
                        6f
                );

        centerLineWidth =
                dp(
                        0.7f
                );

        playheadWidth =
                dp(
                        1.3f
                );

        playheadGlowWidth =
                dp(
                        5f
                );

        playheadDotMinRadius =
                dp(
                        2.6f
                );

        playheadDotMaxRadius =
                dp(
                        5.2f
                );

        primaryColor =
                resolveThemeColor(
                        androidx.appcompat.R.attr.colorPrimary,
                        Color.parseColor(
                                "#6750A4"
                        )
                );

        secondaryColor =
                resolveThemeColor(
                        android.R.attr.textColorSecondary,
                        Color.GRAY
                );

        configurePaints();

        setImportantForAccessibility(
                IMPORTANT_FOR_ACCESSIBILITY_NO
        );
    }

    private void configurePaints() {

        /*
         * ACTIVE FILL
         */
        activeFillPaint.setStyle(
                Paint.Style.FILL
        );

        /*
         * ACTIVE OUTLINE
         */
        activeStrokePaint.setStyle(
                Paint.Style.STROKE
        );

        activeStrokePaint.setStrokeWidth(
                waveformStrokeWidth
        );

        activeStrokePaint.setStrokeJoin(
                Paint.Join.ROUND
        );

        activeStrokePaint.setStrokeCap(
                Paint.Cap.ROUND
        );

        /*
         * SOFT GLOW
         */
        activeGlowPaint.setStyle(
                Paint.Style.STROKE
        );

        activeGlowPaint.setStrokeWidth(
                waveformGlowWidth
        );

        activeGlowPaint.setStrokeJoin(
                Paint.Join.ROUND
        );

        activeGlowPaint.setStrokeCap(
                Paint.Cap.ROUND
        );

        /*
         * FUTURE / INACTIVE
         */
        inactiveFillPaint.setStyle(
                Paint.Style.FILL
        );

        inactiveStrokePaint.setStyle(
                Paint.Style.STROKE
        );

        inactiveStrokePaint.setStrokeWidth(
                waveformStrokeWidth
        );

        inactiveStrokePaint.setStrokeJoin(
                Paint.Join.ROUND
        );

        inactiveStrokePaint.setStrokeCap(
                Paint.Cap.ROUND
        );

        /*
         * CENTER LINE
         */
        centerLinePaint.setStyle(
                Paint.Style.STROKE
        );

        centerLinePaint.setStrokeWidth(
                centerLineWidth
        );

        centerLinePaint.setColor(
                secondaryColor
        );

        centerLinePaint.setAlpha(
                25
        );

        /*
         * PLAYHEAD GLOW
         */
        playheadGlowPaint.setStyle(
                Paint.Style.STROKE
        );

        playheadGlowPaint.setStrokeWidth(
                playheadGlowWidth
        );

        playheadGlowPaint.setStrokeCap(
                Paint.Cap.ROUND
        );

        playheadGlowPaint.setColor(
                primaryColor
        );

        playheadGlowPaint.setAlpha(
                22
        );

        /*
         * PLAYHEAD CORE
         */
        playheadPaint.setStyle(
                Paint.Style.STROKE
        );

        playheadPaint.setStrokeWidth(
                playheadWidth
        );

        playheadPaint.setStrokeCap(
                Paint.Cap.ROUND
        );

        playheadPaint.setColor(
                primaryColor
        );

        playheadPaint.setAlpha(
                225
        );

        /*
         * PLAYHEAD DOT
         */
        playheadDotPaint.setStyle(
                Paint.Style.FILL
        );

        playheadDotPaint.setColor(
                primaryColor
        );
    }

    /*
     * =========================================================
     * SHADERS
     * =========================================================
     */

    @Override
    protected void onSizeChanged(
            int width,
            int height,
            int oldWidth,
            int oldHeight
    ) {

        super.onSizeChanged(
                width,
                height,
                oldWidth,
                oldHeight
        );

        if (width <= 0) {
            return;
        }

        /*
         * Все shaders создаём только при
         * изменении размеров View.
         *
         * Не на каждом frame.
         */

        activeFillPaint.setShader(

                createHorizontalGradient(
                        width,
                        0,
                        64,
                        82,
                        0
                )

        );

        activeStrokePaint.setShader(

                createHorizontalGradient(
                        width,
                        0,
                        160,
                        240,
                        0
                )

        );

        activeGlowPaint.setShader(

                createHorizontalGradient(
                        width,
                        0,
                        25,
                        55,
                        0
                )

        );

        inactiveFillPaint.setShader(

                createInactiveGradient(
                        width,
                        0,
                        22,
                        22,
                        0
                )

        );

        inactiveStrokePaint.setShader(

                createInactiveGradient(
                        width,
                        0,
                        48,
                        48,
                        0
                )

        );
    }

    private LinearGradient createHorizontalGradient(
            int width,
            int edgeAlpha,
            int middleAlpha,
            int nearPlayheadAlpha,
            int endAlpha
    ) {

        return new LinearGradient(
                0f,
                0f,
                width,
                0f,

                new int[]{

                        withAlpha(
                                primaryColor,
                                edgeAlpha
                        ),

                        withAlpha(
                                primaryColor,
                                middleAlpha
                        ),

                        withAlpha(
                                primaryColor,
                                nearPlayheadAlpha
                        ),

                        withAlpha(
                                primaryColor,
                                endAlpha
                        )
                },

                new float[]{

                        0f,

                        0.10f,

                        Math.min(
                                0.90f,
                                PLAYHEAD_POSITION
                                        + 0.08f
                        ),

                        1f
                },

                Shader.TileMode.CLAMP
        );
    }

    private LinearGradient createInactiveGradient(
            int width,
            int edgeAlpha,
            int middleAlpha,
            int nearPlayheadAlpha,
            int endAlpha
    ) {

        return new LinearGradient(
                0f,
                0f,
                width,
                0f,

                new int[]{

                        withAlpha(
                                secondaryColor,
                                edgeAlpha
                        ),

                        withAlpha(
                                secondaryColor,
                                middleAlpha
                        ),

                        withAlpha(
                                secondaryColor,
                                nearPlayheadAlpha
                        ),

                        withAlpha(
                                secondaryColor,
                                endAlpha
                        )
                },

                new float[]{

                        0f,
                        0.10f,
                        0.90f,
                        1f
                },

                Shader.TileMode.CLAMP
        );
    }

    /*
     * =========================================================
     * PUBLIC API
     * =========================================================
     */

    public void setWaveform(
            int[] data
    ) {

        stopAnimation();

        waveform.clear();

        playbackMode =
                true;

        displayedProgress =
                0f;

        targetProgress =
                0f;

        displayedLiveLevel =
                0f;

        targetLiveLevel =
                0f;

        if (data != null) {

            for (int value : data) {

                waveform.add(
                        clampAmplitude(
                                value
                        )
                );
            }
        }

        invalidate();
    }

    public void addAmplitude(
            int amplitude
    ) {

        /*
         * Player → Recording.
         */
        if (playbackMode) {

            stopAnimation();

            waveform.clear();

            displayedLiveIndex =
                    0f;

            targetLiveIndex =
                    0f;

            displayedProgress =
                    0f;

            targetProgress =
                    0f;
        }

        playbackMode =
                false;

        int value =
                clampAmplitude(
                        amplitude
                );

        /*
         * Очень лёгкое сглаживание входных данных.
         *
         * Основной envelope уже рассчитывается
         * AudioRecorder.
         */
        if (!waveform.isEmpty()) {

            int previous =
                    waveform.get(
                            waveform.size()
                                    - 1
                    );

            value =
                    Math.round(
                            previous
                                    * 0.12f
                                    + value
                                    * 0.88f
                    );
        }

        waveform.add(
                value
        );

        while (waveform.size()
                > MAX_LIVE_POINTS) {

            waveform.remove(
                    0
            );

            /*
             * Индексы сдвинулись вместе
             * с удалением первого элемента.
             */
            displayedLiveIndex =
                    Math.max(
                            0f,
                            displayedLiveIndex
                                    - 1f
                    );
        }

        if (waveform.size() == 1) {

            displayedLiveIndex =
                    0f;
        }

        /*
         * Во время активной записи
         * держим небольшой sample buffer.
         */
        targetLiveIndex =
                Math.max(
                        0f,
                        waveform.size()
                                - 1f
                                - LIVE_BUFFER_POINTS
                );

        targetLiveLevel =
                value;

        lastAmplitudeTimeMs =
                SystemClock.uptimeMillis();

        postNextFrame();
    }

    public void setProgress(
            float progress
    ) {

        float value =
                clamp(
                        progress,
                        0f,
                        1f
                );

        if (!playbackMode) {

            return;
        }

        /*
         * Если пользователь сделал seek
         * или playback сбросился назад,
         * не тянем waveform через весь экран.
         */
        if (Math.abs(
                value
                        - displayedProgress
        ) > 0.12f
                || value
                < displayedProgress) {

            displayedProgress =
                    value;

            targetProgress =
                    value;

            invalidate();

            return;
        }

        targetProgress =
                value;

        postNextFrame();
    }

    public void clearWaveform() {

        stopAnimation();

        waveform.clear();

        playbackMode =
                false;

        displayedLiveIndex =
                0f;

        targetLiveIndex =
                0f;

        displayedLiveLevel =
                0f;

        targetLiveLevel =
                0f;

        displayedProgress =
                0f;

        targetProgress =
                0f;

        visiblePointCount =
                0;

        invalidate();
    }

    /*
     * =========================================================
     * LIVE ENGINE
     * =========================================================
     */

    private boolean updateLive(
            float deltaMs
    ) {

        if (waveform.isEmpty()) {

            return false;
        }

        long idleTimeMs =
                SystemClock.uptimeMillis()
                        - lastAmplitudeTimeMs;

        float effectiveTarget;

        /*
         * Пока amplitude поступает регулярно,
         * сохраняем небольшой buffer.
         *
         * Это ключ к непрерывному движению.
         */
        if (idleTimeMs
                <= LIVE_IDLE_THRESHOLD_MS) {

            effectiveTarget =
                    Math.max(
                            0f,
                            waveform.size()
                                    - 1f
                                    - LIVE_BUFFER_POINTS
                    );

        } else {

            /*
             * Pause / Stop:
             *
             * мягко доезжаем последним sample
             * непосредственно до playhead.
             */
            effectiveTarget =
                    Math.max(
                            0f,
                            waveform.size()
                                    - 1f
                    );
        }

        targetLiveIndex =
                effectiveTarget;

        float distance =
                targetLiveIndex
                        - displayedLiveIndex;

        if (distance > 0.0001f) {

            /*
             * Базовая скорость:
             *
             * 1 sample / 80 ms.
             */
            float frameStep =
                    deltaMs
                            / EXPECTED_SAMPLE_INTERVAL_MS;

            /*
             * Если очередь чуть выросла —
             * мягко ускоряемся.
             *
             * Никаких рывков.
             */
            float speedMultiplier =
                    clamp(
                            0.92f
                                    + distance
                                    * 0.16f,
                            0.92f,
                            1.35f
                    );

            float movement =
                    frameStep
                            * speedMultiplier;

            displayedLiveIndex =
                    Math.min(
                            targetLiveIndex,
                            displayedLiveIndex
                                    + movement
                    );
        }

        /*
         * Живая реакция playhead
         * на громкость.
         */
        float levelDifference =
                targetLiveLevel
                        - displayedLiveLevel;

        float levelTimeConstant =
                levelDifference > 0f
                        ? LEVEL_ATTACK_MS
                        : LEVEL_RELEASE_MS;

        displayedLiveLevel =
                smoothTowards(
                        displayedLiveLevel,
                        targetLiveLevel,
                        deltaMs,
                        levelTimeConstant
                );

        boolean signalIsRecent =
                idleTimeMs
                        <= LIVE_IDLE_THRESHOLD_MS;

        boolean positionMoving =
                Math.abs(
                        targetLiveIndex
                                - displayedLiveIndex
                ) > 0.002f;

        boolean levelMoving =
                Math.abs(
                        targetLiveLevel
                                - displayedLiveLevel
                ) > 0.25f;

        /*
         * Пока идёт запись —
         * Choreographer работает постоянно.
         *
         * Поэтому View действительно живёт
         * на refresh rate дисплея.
         */
        return signalIsRecent
                || positionMoving
                || levelMoving;
    }

    /*
     * =========================================================
     * PLAYBACK ENGINE
     * =========================================================
     */

    private boolean updatePlayback(
            float deltaMs
    ) {

        float difference =
                targetProgress
                        - displayedProgress;

        if (Math.abs(
                difference
        ) < 0.00005f) {

            displayedProgress =
                    targetProgress;

            return false;
        }

        displayedProgress =
                smoothTowards(
                        displayedProgress,
                        targetProgress,
                        deltaMs,
                        PLAYBACK_FOLLOW_TIME_MS
                );

        return true;
    }

    /*
     * =========================================================
     * FRAME MANAGEMENT
     * =========================================================
     */

    private void postNextFrame() {

        if (frameCallbackPosted) {
            return;
        }

        if (!isAttachedToWindow()) {
            return;
        }

        frameCallbackPosted =
                true;

        Choreographer
                .getInstance()
                .postFrameCallback(
                        frameCallback
                );
    }

    private void stopAnimation() {

        if (frameCallbackPosted) {

            Choreographer
                    .getInstance()
                    .removeFrameCallback(
                            frameCallback
                    );

            frameCallbackPosted =
                    false;
        }

        lastFrameTimeNanos =
                0L;
    }

    /*
     * =========================================================
     * DRAW
     * =========================================================
     */

    @Override
    protected void onDraw(
            Canvas canvas
    ) {

        super.onDraw(
                canvas
        );

        float width =
                getWidth();

        float height =
                getHeight();

        if (width <= 0f
                || height <= 0f) {

            return;
        }

        float centerY =
                height / 2f;

        float maxHalfHeight =
                Math.max(
                        minHalfHeight,
                        centerY
                                - verticalPadding
                );

        float playheadX =
                width
                        * PLAYHEAD_POSITION;

        /*
         * Почти невидимая baseline.
         */
        canvas.drawLine(
                0f,
                centerY,
                width,
                centerY,
                centerLinePaint
        );

        if (!waveform.isEmpty()) {

            if (playbackMode) {

                drawPlayback(
                        canvas,
                        width,
                        centerY,
                        maxHalfHeight,
                        playheadX
                );

            } else {

                drawLive(
                        canvas,
                        width,
                        centerY,
                        maxHalfHeight,
                        playheadX
                );
            }
        }

        drawPlayhead(
                canvas,
                centerY,
                maxHalfHeight,
                playheadX
        );
    }

    /*
     * =========================================================
     * LIVE DRAW
     * =========================================================
     */

    private void drawLive(
            Canvas canvas,
            float width,
            float centerY,
            float maxHalfHeight,
            float playheadX
    ) {

        buildVisiblePoints(
                displayedLiveIndex,
                width,
                maxHalfHeight,
                playheadX
        );

        if (visiblePointCount <= 0) {
            return;
        }

        buildFluidPath(
                centerY
        );

        /*
         * Очень мягкий внешний halo.
         */
        canvas.drawPath(
                waveformPath,
                activeGlowPaint
        );

        /*
         * Полупрозрачный объём.
         */
        canvas.drawPath(
                waveformPath,
                activeFillPaint
        );

        /*
         * Чёткий тонкий контур.
         */
        canvas.drawPath(
                waveformPath,
                activeStrokePaint
        );
    }

    /*
     * =========================================================
     * PLAYBACK DRAW
     * =========================================================
     */

    private void drawPlayback(
            Canvas canvas,
            float width,
            float centerY,
            float maxHalfHeight,
            float playheadX
    ) {

        int size =
                waveform.size();

        if (size <= 0) {
            return;
        }

        float currentIndex =
                displayedProgress
                        * Math.max(
                        0,
                        size - 1
                );

        buildVisiblePoints(
                currentIndex,
                width,
                maxHalfHeight,
                playheadX
        );

        if (visiblePointCount <= 0) {
            return;
        }

        buildFluidPath(
                centerY
        );

        /*
         * Будущая часть.
         */
        canvas.drawPath(
                waveformPath,
                inactiveFillPaint
        );

        canvas.drawPath(
                waveformPath,
                inactiveStrokePaint
        );

        /*
         * Проигранная часть.
         */
        int save =
                canvas.save();

        canvas.clipRect(
                0f,
                0f,
                playheadX,
                getHeight()
        );

        canvas.drawPath(
                waveformPath,
                activeGlowPaint
        );

        canvas.drawPath(
                waveformPath,
                activeFillPaint
        );

        canvas.drawPath(
                waveformPath,
                activeStrokePaint
        );

        canvas.restoreToCount(
                save
        );
    }

    /*
     * =========================================================
     * VISIBLE POINTS
     * =========================================================
     */

    private void buildVisiblePoints(
            float currentIndex,
            float width,
            float maxHalfHeight,
            float playheadX
    ) {

        visiblePointCount =
                0;

        int size =
                waveform.size();

        if (size <= 0) {
            return;
        }

        int barsToLeft =
                (int) (
                        playheadX
                                / pointStep
                )
                        + 5;

        int barsToRight =
                (int) (
                        (
                                width
                                        - playheadX
                        )
                                / pointStep
                )
                        + 5;

        int centerIndex =
                (int) Math.floor(
                        currentIndex
                );

        int firstIndex =
                Math.max(
                        0,
                        centerIndex
                                - barsToLeft
                );

        int lastIndex =
                Math.min(
                        size - 1,
                        centerIndex
                                + barsToRight
                                + 2
                );

        int required =
                lastIndex
                        - firstIndex
                        + 1;

        ensurePointCapacity(
                required
        );

        for (int i = firstIndex;
             i <= lastIndex;
             i++) {

            float x =
                    playheadX
                            + (
                            i
                                    - currentIndex
                    )
                            * pointStep;

            if (x < -pointStep
                    || x > width
                    + pointStep) {

                continue;
            }

            float halfHeight =
                    amplitudeToHeight(
                            waveform.get(
                                    i
                            ),
                            maxHalfHeight
                    );

            pointX[
                    visiblePointCount
                    ] =
                    x;

            pointHeight[
                    visiblePointCount
                    ] =
                    halfHeight;

            visiblePointCount++;
        }
    }

    private void ensurePointCapacity(
            int required
    ) {

        if (required
                <= pointX.length) {

            return;
        }

        int newSize =
                Math.max(
                        required,
                        pointX.length
                                * 2
                );

        pointX =
                new float[
                        newSize
                        ];

        pointHeight =
                new float[
                        newSize
                        ];
    }

    /*
     * =========================================================
     * FLUID PATH
     * =========================================================
     */

    private void buildFluidPath(
            float centerY
    ) {

        waveformPath.reset();

        if (visiblePointCount <= 0) {
            return;
        }

        if (visiblePointCount == 1) {

            waveformPath.addCircle(
                    pointX[0],
                    centerY,
                    pointHeight[0],
                    Path.Direction.CW
            );

            return;
        }

        /*
         * =========================
         * TOP
         * =========================
         */

        float firstX =
                pointX[0];

        float firstY =
                centerY
                        - pointHeight[0];

        waveformPath.moveTo(
                firstX,
                firstY
        );

        for (int i = 1;
             i < visiblePointCount;
             i++) {

            float previousX =
                    pointX[
                            i - 1
                            ];

            float previousY =
                    centerY
                            - pointHeight[
                            i - 1
                            ];

            float currentX =
                    pointX[i];

            float currentY =
                    centerY
                            - pointHeight[i];

            float controlX =
                    (
                            previousX
                                    + currentX
                    )
                            / 2f;

            /*
             * Безопасный Bezier.
             *
             * Не overshoot-ит амплитуду,
             * как агрессивный spline.
             */
            waveformPath.cubicTo(
                    controlX,
                    previousY,

                    controlX,
                    currentY,

                    currentX,
                    currentY
            );
        }

        /*
         * Последняя точка снизу.
         */
        int last =
                visiblePointCount
                        - 1;

        waveformPath.lineTo(
                pointX[last],
                centerY
                        + pointHeight[last]
        );

        /*
         * =========================
         * BOTTOM
         * =========================
         */

        for (int i = last - 1;
             i >= 0;
             i--) {

            float previousX =
                    pointX[
                            i + 1
                            ];

            float previousY =
                    centerY
                            + pointHeight[
                            i + 1
                            ];

            float currentX =
                    pointX[i];

            float currentY =
                    centerY
                            + pointHeight[i];

            float controlX =
                    (
                            previousX
                                    + currentX
                    )
                            / 2f;

            waveformPath.cubicTo(
                    controlX,
                    previousY,

                    controlX,
                    currentY,

                    currentX,
                    currentY
            );
        }

        waveformPath.close();
    }

    /*
     * =========================================================
     * PLAYHEAD
     * =========================================================
     */

    private void drawPlayhead(
            Canvas canvas,
            float centerY,
            float maxHalfHeight,
            float playheadX
    ) {

        /*
         * Playhead не занимает всю высоту,
         * поэтому выглядит легче.
         */
        float lineHalfHeight =
                maxHalfHeight
                        * 0.78f;

        float top =
                centerY
                        - lineHalfHeight;

        float bottom =
                centerY
                        + lineHalfHeight;

        canvas.drawLine(
                playheadX,
                top,
                playheadX,
                bottom,
                playheadGlowPaint
        );

        canvas.drawLine(
                playheadX,
                top,
                playheadX,
                bottom,
                playheadPaint
        );

        float level;

        if (playbackMode) {

            /*
             * Во время playback точка
             * спокойная.
             */
            level =
                    0.35f;

        } else {

            level =
                    clamp(
                            displayedLiveLevel
                                    / 100f,
                            0f,
                            1f
                    );
        }

        float radius =
                playheadDotMinRadius
                        + (
                        playheadDotMaxRadius
                                - playheadDotMinRadius
                )
                        * level;

        /*
         * Маленький внешний halo.
         */
        playheadDotPaint.setAlpha(
                35
        );

        canvas.drawCircle(
                playheadX,
                centerY,
                radius
                        * 1.8f,
                playheadDotPaint
        );

        /*
         * Центральное ядро.
         */
        playheadDotPaint.setAlpha(
                255
        );

        canvas.drawCircle(
                playheadX,
                centerY,
                radius,
                playheadDotPaint
        );
    }

    /*
     * =========================================================
     * AMPLITUDE
     * =========================================================
     */

    private float amplitudeToHeight(
            float amplitude,
            float maxHalfHeight
    ) {

        float normalized =
                clamp(
                        amplitude
                                / 100f,
                        0f,
                        1f
                );

        /*
         * Тихие участки остаются читаемыми.
         *
         * Громкие не упираются сразу
         * в потолок.
         */
        float shaped =
                (float) Math.pow(
                        normalized,
                        0.76
                );

        return minHalfHeight
                + shaped
                * (
                maxHalfHeight
                        - minHalfHeight
        );
    }

    /*
     * =========================================================
     * SMOOTHING
     * =========================================================
     */

    private float smoothTowards(
            float current,
            float target,
            float deltaMs,
            float timeConstantMs
    ) {

        if (timeConstantMs <= 0f) {
            return target;
        }

        float alpha =
                1f
                        - (float) Math.exp(
                        -deltaMs
                                / timeConstantMs
                );

        return current
                + (
                target
                        - current
        )
                * alpha;
    }

    /*
     * =========================================================
     * HELPERS
     * =========================================================
     */

    private int clampAmplitude(
            int amplitude
    ) {

        return Math.max(
                0,
                Math.min(
                        100,
                        amplitude
                )
        );
    }

    private float clamp(
            float value,
            float min,
            float max
    ) {

        return Math.max(
                min,
                Math.min(
                        max,
                        value
                )
        );
    }

    private int withAlpha(
            int color,
            int alpha
    ) {

        return Color.argb(
                Math.max(
                        0,
                        Math.min(
                                255,
                                alpha
                        )
                ),
                Color.red(
                        color
                ),
                Color.green(
                        color
                ),
                Color.blue(
                        color
                )
        );
    }

    private float dp(
            float value
    ) {

        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources()
                        .getDisplayMetrics()
        );
    }

    private int resolveThemeColor(
            int attribute,
            int fallback
    ) {

        TypedValue value =
                new TypedValue();

        boolean resolved =
                getContext()
                        .getTheme()
                        .resolveAttribute(
                                attribute,
                                value,
                                true
                        );

        if (!resolved) {
            return fallback;
        }

        if (value.resourceId != 0) {

            try {

                return ContextCompat.getColor(
                        getContext(),
                        value.resourceId
                );

            } catch (Exception ignored) {
            }
        }

        if (value.type
                >= TypedValue.TYPE_FIRST_COLOR_INT
                && value.type
                <= TypedValue.TYPE_LAST_COLOR_INT) {

            return value.data;
        }

        return fallback;
    }

    /*
     * =========================================================
     * LIFECYCLE
     * =========================================================
     */

    @Override
    protected void onDetachedFromWindow() {

        stopAnimation();

        super.onDetachedFromWindow();
    }
}