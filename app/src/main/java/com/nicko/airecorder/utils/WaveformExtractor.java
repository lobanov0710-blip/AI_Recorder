package com.nicko.airecorder.utils;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class WaveformExtractor {

    private static final String TAG =
            "WaveformExtractor";

    private static final int TARGET_POINTS =
            180;

    private static final long CODEC_TIMEOUT_US =
            10_000L;

    private static final double MIN_DB =
            -60.0;

    /*
     * Waveform extraction выполняется последовательно.
     *
     * Одновременная тяжелая media decode работа
     * для нескольких PlayerActivity не требуется.
     */
    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final Handler mainHandler =
            new Handler(
                    Looper.getMainLooper()
            );

    /*
     * =========================================================
     * TASK STATE
     * =========================================================
     */

    private final Object taskLock =
            new Object();

    private ExtractionTask currentTask;

    /*
     * =========================================================
     * CALLBACK
     * =========================================================
     */

    public interface Callback {

        void onWaveformReady(
                int[] waveform
        );
    }

    /*
     * =========================================================
     * EXTRACT
     * =========================================================
     */

    public void extract(
            File file,
            Callback callback
    ) {

        /*
         * Один WaveformExtractor обслуживает
         * только одну актуальную задачу.
         */
        cancel();

        if (file == null
                || !file.exists()
                || !file.isFile()) {

            deliverImmediateResult(
                    callback,
                    new int[0]
            );

            return;
        }

        String filePath =
                file.getAbsolutePath();

        /*
         * Уже рассчитанный waveform повторно
         * декодировать не нужно.
         */
        int[] cached =
                WaveformCache
                        .getInstance()
                        .get(
                                filePath
                        );

        if (cached != null) {

            deliverImmediateResult(
                    callback,
                    cached
            );

            return;
        }

        ExtractionTask task =
                new ExtractionTask(
                        file,
                        callback
                );

        synchronized (taskLock) {

            currentTask =
                    task;

            task.future =
                    EXECUTOR.submit(
                            () -> runExtraction(
                                    task
                            )
                    );
        }
    }

    /*
     * =========================================================
     * CANCEL
     * =========================================================
     */

    public void cancel() {

        ExtractionTask task;

        synchronized (taskLock) {

            task =
                    currentTask;

            currentTask =
                    null;
        }

        if (task == null) {
            return;
        }

        task.cancelled =
                true;

        Future<?> future =
                task.future;

        if (future != null) {

            future.cancel(
                    true
            );
        }
    }

    /*
     * =========================================================
     * WORKER
     * =========================================================
     */

    private void runExtraction(
            ExtractionTask task
    ) {

        if (task == null) {
            return;
        }

        try {

            if (task.isCancelled()) {

                clearTaskIfCurrent(
                        task
                );

                return;
            }

            String filePath =
                    task.file
                            .getAbsolutePath();

            /*
             * Задача могла ждать в single-thread executor.
             *
             * За это время waveform этого файла
             * мог уже появиться в cache.
             */
            int[] cached =
                    WaveformCache
                            .getInstance()
                            .get(
                                    filePath
                            );

            if (cached != null) {

                deliverTaskResult(
                        task,
                        cached
                );

                return;
            }

            int[] result =
                    buildWaveform(
                            task.file,
                            task
                    );

            if (task.isCancelled()) {

                clearTaskIfCurrent(
                        task
                );

                return;
            }

            WaveformCache
                    .getInstance()
                    .put(
                            filePath,
                            result
                    );

            deliverTaskResult(
                    task,
                    result
            );

        } catch (Exception e) {

            if (task.isCancelled()) {

                clearTaskIfCurrent(
                        task
                );

                return;
            }

            Log.e(
                    TAG,
                    "Ошибка waveform extraction worker",
                    e
            );

            deliverTaskResult(
                    task,
                    new int[0]
            );
        }
    }

    /*
     * =========================================================
     * RESULT DELIVERY
     * =========================================================
     */

    private void deliverImmediateResult(
            Callback callback,
            int[] result
    ) {

        if (callback == null) {
            return;
        }

        mainHandler.post(
                () -> callback.onWaveformReady(
                        result
                )
        );
    }

    private void deliverTaskResult(
            ExtractionTask task,
            int[] result
    ) {

        if (task == null) {
            return;
        }

        if (task.callback == null) {

            clearTaskIfCurrent(
                    task
            );

            return;
        }

        mainHandler.post(
                () -> {

                    try {

                        /*
                         * Activity могла быть уничтожена
                         * уже после завершения decoder.
                         */
                        if (task.isCancelled()) {
                            return;
                        }

                        task.callback
                                .onWaveformReady(
                                        result
                                );

                    } finally {

                        clearTaskIfCurrent(
                                task
                        );
                    }
                }
        );
    }

    /*
     * =========================================================
     * BUILD WAVEFORM
     * =========================================================
     */

    private int[] buildWaveform(
            File file,
            ExtractionTask task
    ) {

        if (task.isCancelled()) {
            return new int[0];
        }

        MediaExtractor extractor =
                new MediaExtractor();

        MediaCodec decoder =
                null;

        boolean decoderStarted =
                false;

        try {

            extractor.setDataSource(
                    file.getAbsolutePath()
            );

            if (task.isCancelled()) {
                return new int[0];
            }

            int audioTrack =
                    findAudioTrack(
                            extractor
                    );

            if (audioTrack < 0) {

                Log.e(
                        TAG,
                        "Аудиотрек не найден"
                );

                return new int[0];
            }

            MediaFormat inputFormat =
                    extractor.getTrackFormat(
                            audioTrack
                    );

            String mime =
                    inputFormat.getString(
                            MediaFormat.KEY_MIME
                    );

            if (mime == null
                    || !mime.startsWith(
                    "audio/"
            )) {

                return new int[0];
            }

            long durationUs =
                    readDurationUs(
                            inputFormat
                    );

            if (durationUs <= 0L) {

                Log.e(
                        TAG,
                        "В аудиотреке отсутствует корректная duration"
                );

                return new int[0];
            }

            /*
             * Bounded accumulator:
             *
             * независимо от длительности файла
             * используется фиксированный объём памяти.
             */
            WaveformAccumulator accumulator =
                    new WaveformAccumulator(
                            TARGET_POINTS,
                            durationUs
                    );

            extractor.selectTrack(
                    audioTrack
            );

            if (task.isCancelled()) {
                return new int[0];
            }

            decoder =
                    MediaCodec
                            .createDecoderByType(
                                    mime
                            );

            decoder.configure(
                    inputFormat,
                    null,
                    null,
                    0
            );

            decoder.start();

            decoderStarted =
                    true;

            decodeToAccumulator(
                    extractor,
                    decoder,
                    accumulator,
                    task
            );

            if (task.isCancelled()) {
                return new int[0];
            }

            return accumulator
                    .build();

        } catch (Exception e) {

            if (!task.isCancelled()) {

                Log.e(
                        TAG,
                        "Ошибка декодирования waveform",
                        e
                );
            }

            return new int[0];

        } finally {

            /*
             * MediaCodec / MediaExtractor всегда
             * освобождаются независимо от результата.
             */
            if (decoder != null) {

                if (decoderStarted) {

                    try {

                        decoder.stop();

                    } catch (Exception e) {

                        if (!task.isCancelled()) {

                            Log.w(
                                    TAG,
                                    "Ошибка MediaCodec.stop()",
                                    e
                            );
                        }
                    }
                }

                try {

                    decoder.release();

                } catch (Exception e) {

                    if (!task.isCancelled()) {

                        Log.w(
                                TAG,
                                "Ошибка MediaCodec.release()",
                                e
                        );
                    }
                }
            }

            try {

                extractor.release();

            } catch (Exception e) {

                if (!task.isCancelled()) {

                    Log.w(
                            TAG,
                            "Ошибка MediaExtractor.release()",
                            e
                    );
                }
            }
        }
    }

    /*
     * =========================================================
     * TRACK DURATION
     * =========================================================
     */

    private long readDurationUs(
            MediaFormat format
    ) {

        if (format == null
                || !format.containsKey(
                MediaFormat.KEY_DURATION
        )) {

            return 0L;
        }

        try {

            return Math.max(
                    0L,
                    format.getLong(
                            MediaFormat.KEY_DURATION
                    )
            );

        } catch (Exception e) {

            Log.w(
                    TAG,
                    "Не удалось прочитать duration аудиотрека",
                    e
            );

            return 0L;
        }
    }

    /*
     * =========================================================
     * DECODE → BOUNDED ACCUMULATOR
     * =========================================================
     */

    private void decodeToAccumulator(
            MediaExtractor extractor,
            MediaCodec decoder,
            WaveformAccumulator accumulator,
            ExtractionTask task
    ) throws IOException {

        boolean inputFinished =
                false;

        boolean outputFinished =
                false;

        int pcmEncoding =
                AudioFormat.ENCODING_PCM_16BIT;

        MediaCodec.BufferInfo bufferInfo =
                new MediaCodec.BufferInfo();

        while (!outputFinished) {

            if (task.isCancelled()) {
                return;
            }

            /*
             * =================================================
             * INPUT
             * =================================================
             */

            if (!inputFinished) {

                int inputIndex =
                        decoder.dequeueInputBuffer(
                                CODEC_TIMEOUT_US
                        );

                if (task.isCancelled()) {
                    return;
                }

                if (inputIndex >= 0) {

                    ByteBuffer inputBuffer =
                            decoder.getInputBuffer(
                                    inputIndex
                            );

                    if (inputBuffer == null) {

                        throw new IOException(
                                "Decoder input buffer = null"
                        );
                    }

                    inputBuffer.clear();

                    int sampleSize =
                            extractor.readSampleData(
                                    inputBuffer,
                                    0
                            );

                    if (sampleSize < 0) {

                        decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec
                                        .BUFFER_FLAG_END_OF_STREAM
                        );

                        inputFinished =
                                true;

                    } else {

                        long presentationTimeUs =
                                extractor.getSampleTime();

                        decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                Math.max(
                                        0L,
                                        presentationTimeUs
                                ),
                                0
                        );

                        extractor.advance();
                    }
                }
            }

            if (task.isCancelled()) {
                return;
            }

            /*
             * =================================================
             * OUTPUT PCM
             * =================================================
             */

            int outputIndex =
                    decoder.dequeueOutputBuffer(
                            bufferInfo,
                            CODEC_TIMEOUT_US
                    );

            if (outputIndex
                    == MediaCodec
                    .INFO_TRY_AGAIN_LATER) {

                continue;
            }

            if (outputIndex
                    == MediaCodec
                    .INFO_OUTPUT_FORMAT_CHANGED) {

                MediaFormat outputFormat =
                        decoder.getOutputFormat();

                if (outputFormat.containsKey(
                        MediaFormat.KEY_PCM_ENCODING
                )) {

                    pcmEncoding =
                            outputFormat.getInteger(
                                    MediaFormat.KEY_PCM_ENCODING
                            );
                }

                continue;
            }

            if (outputIndex < 0) {
                continue;
            }

            try {

                ByteBuffer outputBuffer =
                        decoder.getOutputBuffer(
                                outputIndex
                        );

                if (outputBuffer != null
                        && bufferInfo.size > 0) {

                    ByteBuffer pcm =
                            outputBuffer.duplicate();

                    pcm.position(
                            bufferInfo.offset
                    );

                    pcm.limit(
                            bufferInfo.offset
                                    + bufferInfo.size
                    );

                    pcm =
                            pcm.slice();

                    pcm.order(
                            ByteOrder.nativeOrder()
                    );

                    int level;

                    if (pcmEncoding
                            == AudioFormat
                            .ENCODING_PCM_FLOAT) {

                        level =
                                calculateFloatPcmLevel(
                                        pcm
                                );

                    } else {

                        level =
                                calculatePcm16Level(
                                        pcm
                                );
                    }

                    accumulator.add(
                            Math.max(
                                    0L,
                                    bufferInfo.presentationTimeUs
                            ),
                            level
                    );
                }

                boolean endOfStream =
                        (bufferInfo.flags
                                & MediaCodec
                                .BUFFER_FLAG_END_OF_STREAM)
                                != 0;

                if (endOfStream) {

                    outputFinished =
                            true;
                }

            } finally {

                /*
                 * Каждый полученный decoder output
                 * освобождается ровно один раз.
                 */
                decoder.releaseOutputBuffer(
                        outputIndex,
                        false
                );
            }
        }
    }

    /*
     * =========================================================
     * AUDIO TRACK
     * =========================================================
     */

    private int findAudioTrack(
            MediaExtractor extractor
    ) {

        for (int i = 0;
             i < extractor.getTrackCount();
             i++) {

            MediaFormat format =
                    extractor.getTrackFormat(
                            i
                    );

            String mime =
                    format.getString(
                            MediaFormat.KEY_MIME
                    );

            if (mime != null
                    && mime.startsWith(
                    "audio/"
            )) {

                return i;
            }
        }

        return -1;
    }

    /*
     * =========================================================
     * PCM 16 LEVEL
     * =========================================================
     */

    private int calculatePcm16Level(
            ByteBuffer buffer
    ) {

        if (buffer == null
                || buffer.remaining() < 2) {

            return 0;
        }

        double sumSquares =
                0.0;

        int sampleCount =
                0;

        while (buffer.remaining() >= 2) {

            short sample =
                    buffer.getShort();

            double normalized =
                    sample / 32768.0;

            sumSquares +=
                    normalized
                            * normalized;

            sampleCount++;
        }

        if (sampleCount == 0) {
            return 0;
        }

        double rms =
                Math.sqrt(
                        sumSquares
                                / sampleCount
                );

        return rmsToLevel(
                rms
        );
    }

    /*
     * =========================================================
     * FLOAT PCM LEVEL
     * =========================================================
     */

    private int calculateFloatPcmLevel(
            ByteBuffer buffer
    ) {

        if (buffer == null
                || buffer.remaining() < 4) {

            return 0;
        }

        double sumSquares =
                0.0;

        int sampleCount =
                0;

        while (buffer.remaining() >= 4) {

            float sample =
                    buffer.getFloat();

            double normalized =
                    Math.max(
                            -1.0,
                            Math.min(
                                    1.0,
                                    sample
                            )
                    );

            sumSquares +=
                    normalized
                            * normalized;

            sampleCount++;
        }

        if (sampleCount == 0) {
            return 0;
        }

        double rms =
                Math.sqrt(
                        sumSquares
                                / sampleCount
                );

        return rmsToLevel(
                rms
        );
    }

    /*
     * =========================================================
     * RMS → LEVEL
     * =========================================================
     */

    private int rmsToLevel(
            double rms
    ) {

        if (rms <= 0.0) {
            return 0;
        }

        double db =
                20.0
                        * Math.log10(
                        rms
                );

        if (db <= MIN_DB) {
            return 0;
        }

        if (db >= 0.0) {
            return 100;
        }

        double normalized =
                (db - MIN_DB)
                        / -MIN_DB;

        int level =
                (int) Math.round(
                        normalized
                                * 100.0
                );

        return Math.max(
                0,
                Math.min(
                        100,
                        level
                )
        );
    }

    /*
     * =========================================================
     * TASK CLEANUP
     * =========================================================
     */

    private void clearTaskIfCurrent(
            ExtractionTask task
    ) {

        synchronized (taskLock) {

            if (currentTask == task) {

                currentTask =
                        null;
            }
        }
    }

    /*
     * =========================================================
     * EXTRACTION TASK
     * =========================================================
     */

    private static final class ExtractionTask {

        private final File file;

        private final Callback callback;

        private volatile boolean cancelled =
                false;

        private volatile Future<?> future;

        private ExtractionTask(
                File file,
                Callback callback
        ) {

            this.file =
                    file;

            this.callback =
                    callback;
        }

        private boolean isCancelled() {

            return cancelled
                    || Thread.currentThread()
                    .isInterrupted();
        }
    }

    /*
     * =========================================================
     * BOUNDED WAVEFORM ACCUMULATOR
     * =========================================================
     *
     * Здесь решается AR-012B.
     *
     * Раньше:
     *
     * каждый decoder output
     *     ↓
     * ArrayList<Integer>
     *     ↓
     * весь файл хранится в памяти
     *     ↓
     * compress до 180 элементов
     *
     * Теперь:
     *
     * каждый decoder output
     *     ↓
     * один из 180 временных buckets
     *     ↓
     * итоговый int[180]
     *
     * Memory complexity:
     *
     * O(TARGET_POINTS), а не O(duration).
     */

    private static final class WaveformAccumulator {

        private final int targetPoints;

        private final long durationUs;

        /*
         * Для длинных записей.
         */
        private final long[] bucketSums;

        private final int[] bucketCounts;

        /*
         * Для коротких записей.
         *
         * Если decoder выдал <= TARGET_POINTS buffers,
         * сохраняем прежнее поведение и возвращаем
         * их непосредственно без искусственных дыр.
         */
        private final int[] initialLevels;

        private int totalLevels =
                0;

        private WaveformAccumulator(
                int targetPoints,
                long durationUs
        ) {

            this.targetPoints =
                    Math.max(
                            1,
                            targetPoints
                    );

            this.durationUs =
                    Math.max(
                            1L,
                            durationUs
                    );

            bucketSums =
                    new long[
                            this.targetPoints
                            ];

            bucketCounts =
                    new int[
                            this.targetPoints
                            ];

            initialLevels =
                    new int[
                            this.targetPoints
                            ];
        }

        private void add(
                long presentationTimeUs,
                int level
        ) {

            int safeLevel =
                    Math.max(
                            0,
                            Math.min(
                                    100,
                                    level
                            )
                    );

            /*
             * Первые 180 decoder-levels сохраняем,
             * чтобы короткий файл выглядел так же,
             * как до AR-012B.
             */
            if (totalLevels
                    < initialLevels.length) {

                initialLevels[
                        totalLevels
                        ] =
                        safeLevel;
            }

            totalLevels++;

            long safeTimeUs =
                    Math.max(
                            0L,
                            Math.min(
                                    durationUs,
                                    presentationTimeUs
                            )
                    );

            /*
             * Преобразуем timestamp
             * в диапазон 0 ... targetPoints-1.
             */
            long scaled =
                    safeTimeUs
                            * targetPoints;

            int bucket =
                    (int) (
                            scaled
                                    / durationUs
                    );

            if (bucket >= targetPoints) {

                bucket =
                        targetPoints - 1;
            }

            bucket =
                    Math.max(
                            0,
                            bucket
                    );

            bucketSums[
                    bucket
                    ] +=
                    safeLevel;

            bucketCounts[
                    bucket
                    ]++;
        }

        private int[] build() {

            if (totalLevels <= 0) {

                return new int[0];
            }

            /*
             * Короткие записи:
             *
             * сохраняем фактическое количество
             * decoder samples.
             */
            if (totalLevels
                    <= targetPoints) {

                return Arrays.copyOf(
                        initialLevels,
                        totalLevels
                );
            }

            /*
             * Длинные записи:
             *
             * всегда ровно TARGET_POINTS.
             */
            int[] result =
                    new int[
                            targetPoints
                            ];

            for (int i = 0;
                 i < targetPoints;
                 i++) {

                int count =
                        bucketCounts[i];

                if (count <= 0) {

                    result[i] =
                            -1;

                    continue;
                }

                result[i] =
                        (int) (
                                bucketSums[i]
                                        / count
                        );
            }

            fillMissingBuckets(
                    result
            );

            return result;
        }

        /*
         * Из-за timestamp rounding теоретически
         * некоторые buckets могут остаться пустыми.
         *
         * Не отображаем такие места как ложную тишину.
         * Используем линейную интерполяцию соседей.
         */
        private void fillMissingBuckets(
                int[] values
        ) {

            if (values == null
                    || values.length == 0) {

                return;
            }

            for (int i = 0;
                 i < values.length;
                 i++) {

                if (values[i] >= 0) {
                    continue;
                }

                int left =
                        i - 1;

                while (left >= 0
                        && values[left] < 0) {

                    left--;
                }

                int right =
                        i + 1;

                while (right < values.length
                        && values[right] < 0) {

                    right++;
                }

                if (left >= 0
                        && right < values.length) {

                    int leftValue =
                            values[left];

                    int rightValue =
                            values[right];

                    float fraction =
                            (float) (
                                    i - left
                            )
                                    / (
                                    right - left
                            );

                    values[i] =
                            Math.round(
                                    leftValue
                                            + (
                                            rightValue
                                                    - leftValue
                                    )
                                            * fraction
                            );

                } else if (left >= 0) {

                    values[i] =
                            values[left];

                } else if (right
                        < values.length) {

                    values[i] =
                            values[right];

                } else {

                    values[i] =
                            0;
                }
            }
        }
    }
}