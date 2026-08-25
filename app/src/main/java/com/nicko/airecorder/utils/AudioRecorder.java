package com.nicko.airecorder.utils;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;

public class AudioRecorder {

    private static final String TAG =
            "AudioRecorder";

    private static final int SAMPLE_RATE =
            44100;

    private static final int CHANNEL_COUNT =
            1;

    private static final int CHANNEL_CONFIG =
            AudioFormat.CHANNEL_IN_MONO;

    private static final int PCM_ENCODING =
            AudioFormat.ENCODING_PCM_16BIT;

    private static final int PCM_BYTES_PER_SAMPLE =
            2;

    private static final int PCM_BUFFER_SAMPLES =
            1024;

    private static final int AAC_BIT_RATE =
            128000;

    private static final long MICROSECONDS_PER_SECOND =
            1_000_000L;

    /*
     * Encoder находится в отдельном потоке,
     * поэтому ему разрешено немного ждать
     * свободный input buffer.
     */
    private static final long CODEC_INPUT_TIMEOUT_US =
            10_000L;

    /*
     * Во время обычного drain ничего не ждём.
     */
    private static final long CODEC_POLL_TIMEOUT_US =
            0L;

    /*
     * При EOS можно ждать output.
     */
    private static final long CODEC_EOS_TIMEOUT_US =
            10_000L;

    private static final int MAX_EOS_WAIT_COUNT =
            300;

    private static final long CAPTURE_JOIN_TIMEOUT_MS =
            3000L;

    private static final long ENCODER_JOIN_TIMEOUT_MS =
            10000L;

    private static final PcmChunk END_CHUNK =
            new PcmChunk(
                    null,
                    0,
                    true
            );

    private final Object lifecycleLock =
            new Object();

    /*
     * Producer → Consumer.
     *
     * Capture thread никогда не ждёт MediaCodec.
     */
    private final LinkedBlockingQueue<PcmChunk> pcmQueue =
            new LinkedBlockingQueue<>();

    private volatile boolean recording =
            false;

    private volatile boolean paused =
            false;

    private volatile boolean stopRequested =
            false;

    private volatile boolean captureSuccess =
            false;

    private volatile boolean encoderSuccess =
            false;

    private volatile int maxAmplitude =
            0;

    private volatile Thread captureThread;

    private volatile Thread encoderThread;

    private volatile AudioRecord audioRecord;

    private MediaCodec encoder;

    private MediaMuxer muxer;

    private boolean muxerStarted =
            false;

    private int muxerTrackIndex =
            -1;

    private File outputFile;

    /*
     * Количество PCM samples,
     * действительно переданных encoder.
     *
     * Pause сюда не входит.
     */
    private long totalPcmSamples =
            0L;

    private long firstEncoderPresentationTimeUs =
            Long.MIN_VALUE;

    private long lastMuxerPresentationTimeUs =
            -1L;

    public AudioRecorder() {
    }

    public void startRecording(
            @NonNull File outputFile
    ) throws IOException {

        synchronized (lifecycleLock) {

            if (recording) {
                return;
            }

            if (isThreadAlive(captureThread)
                    || isThreadAlive(encoderThread)) {

                throw new IllegalStateException(
                        "Предыдущая запись ещё завершается"
                );
            }

            this.outputFile =
                    outputFile;

            prepareOutputFile(
                    outputFile
            );

            resetSessionState();

            try {

                prepareAudioRecord();

                prepareEncoder();

                prepareMuxer();

                AudioRecord localAudioRecord =
                        audioRecord;

                if (localAudioRecord == null) {

                    throw new IOException(
                            "AudioRecord не создан"
                    );
                }

                localAudioRecord.startRecording();

                if (localAudioRecord.getRecordingState()
                        != AudioRecord.RECORDSTATE_RECORDING) {

                    throw new IOException(
                            "AudioRecord не перешёл в RECORDING"
                    );
                }

                /*
                 * Encoder thread запускаем первым:
                 * он будет ждать первый PCM chunk.
                 */
                Thread newEncoderThread =
                        new Thread(
                                this::encoderLoop,
                                "AIRecorder-Encoder"
                        );

                Thread newCaptureThread =
                        new Thread(
                                this::captureLoop,
                                "AIRecorder-Capture"
                        );

                encoderThread =
                        newEncoderThread;

                captureThread =
                        newCaptureThread;

                recording =
                        true;

                newEncoderThread.start();

                newCaptureThread.start();

            } catch (IOException
                     | RuntimeException e) {

                recording =
                        false;

                paused =
                        false;

                stopRequested =
                        true;

                pcmQueue.offer(
                        END_CHUNK
                );

                cleanupAfterStartFailure();

                deleteOutputFileQuietly();

                throw e;
            }
        }
    }

    public boolean pauseRecording() {

        if (!recording
                || paused
                || stopRequested) {

            return false;
        }

        /*
         * AudioRecord НЕ останавливаем.
         *
         * Capture thread продолжает читать
         * hardware buffer и выбрасывает PCM.
         */
        paused =
                true;

        maxAmplitude =
                0;

        Log.d(
                TAG,
                "Pause включён"
        );

        return true;
    }

    public boolean resumeRecording() {

        if (!recording
                || !paused
                || stopRequested) {

            return false;
        }

        /*
         * Никакого restart/resume устройства.
         *
         * Просто снова разрешаем
         * помещать PCM в очередь.
         */
        paused =
                false;

        Log.d(
                TAG,
                "Resume выполнен"
        );

        return true;
    }

    public boolean stopRecording() {

        Thread localCaptureThread;

        Thread localEncoderThread;

        synchronized (lifecycleLock) {

            if (!recording
                    && captureThread == null
                    && encoderThread == null) {

                return false;
            }

            stopRequested =
                    true;

            paused =
                    false;

            localCaptureThread =
                    captureThread;

            localEncoderThread =
                    encoderThread;
        }

        /*
         * Прерываем blocking AudioRecord.read().
         *
         * Capture thread затем положит END_CHUNK.
         */
        requestAudioRecordStop();

        joinThread(
                localCaptureThread,
                CAPTURE_JOIN_TIMEOUT_MS,
                "capture"
        );

        /*
         * Encoder обязан обработать ВСЮ очередь,
         * которая была накоплена до Stop,
         * и только затем встретит END_CHUNK.
         */
        joinThread(
                localEncoderThread,
                ENCODER_JOIN_TIMEOUT_MS,
                "encoder"
        );

        boolean threadsFinished =
                !isThreadAlive(
                        localCaptureThread
                )
                        && !isThreadAlive(
                        localEncoderThread
                );

        boolean fileValid =
                outputFile != null
                        && outputFile.exists()
                        && outputFile.isFile()
                        && outputFile.length() > 0L;

        boolean success =
                threadsFinished
                        && captureSuccess
                        && encoderSuccess
                        && fileValid;

        synchronized (lifecycleLock) {

            recording =
                    false;

            paused =
                    false;

            maxAmplitude =
                    0;

            captureThread =
                    null;

            encoderThread =
                    null;
        }

        if (!success) {

            Log.e(
                    TAG,
                    "Финализация записи завершилась с ошибкой"
            );

            deleteOutputFileQuietly();
        }

        return success;
    }

    /*
     * PRODUCER
     *
     * Единственная задача этого потока:
     *
     * microphone → PCM → queue
     *
     * MediaCodec здесь вообще не используется.
     */
    private void captureLoop() {

        boolean success =
                false;

        try {

            Process.setThreadPriority(
                    Process.THREAD_PRIORITY_AUDIO
            );

            short[] pcmBuffer =
                    new short[
                            PCM_BUFFER_SAMPLES
                            ];

            while (!stopRequested) {

                AudioRecord localAudioRecord =
                        audioRecord;

                if (localAudioRecord == null) {

                    throw new IOException(
                            "AudioRecord отсутствует"
                    );
                }

                int readSamples =
                        localAudioRecord.read(
                                pcmBuffer,
                                0,
                                pcmBuffer.length,
                                AudioRecord.READ_BLOCKING
                        );

                if (readSamples > 0) {

                    /*
                     * Проверяем Pause ПОСЛЕ read.
                     *
                     * Таким образом hardware buffer
                     * микрофона обслуживается постоянно.
                     */
                    if (paused) {

                        maxAmplitude =
                                0;

                        continue;
                    }

                    updateAmplitude(
                            pcmBuffer,
                            readSamples
                    );

                    short[] copy =
                            Arrays.copyOf(
                                    pcmBuffer,
                                    readSamples
                            );

                    pcmQueue.put(
                            new PcmChunk(
                                    copy,
                                    readSamples,
                                    false
                            )
                    );

                    continue;
                }

                if (stopRequested) {

                    break;
                }

                if (readSamples == 0) {

                    continue;
                }

                throw new IOException(
                        "AudioRecord.read() вернул ошибку: "
                                + readSamples
                );
            }

            success =
                    true;

        } catch (InterruptedException e) {

            Thread.currentThread()
                    .interrupt();

            /*
             * Если Stop уже был запрошен,
             * interruption не считаем аварией.
             */
            success =
                    stopRequested;

            if (!stopRequested) {

                Log.e(
                        TAG,
                        "Capture thread прерван",
                        e
                );
            }

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Ошибка capture pipeline",
                    e
            );

            success =
                    false;

        } finally {

            captureSuccess =
                    success;

            stopAndReleaseAudioRecord();

            /*
             * END всегда идёт ПОСЛЕ всех PCM chunks.
             *
             * Поэтому encoder обработает очередь
             * полностью перед EOS.
             */
            pcmQueue.offer(
                    END_CHUNK
            );

            if (!success) {

                stopRequested =
                        true;
            }

            Log.d(
                    TAG,
                    "Capture завершён. success="
                            + captureSuccess
                            + ", queue="
                            + pcmQueue.size()
            );
        }
    }

    /*
     * CONSUMER
     *
     * Этот поток может ждать MediaCodec сколько нужно.
     *
     * Захват микрофона от этого больше
     * НЕ останавливается.
     */
    private void encoderLoop() {

        boolean pipelineSuccess =
                false;

        try {

            while (true) {

                PcmChunk chunk =
                        pcmQueue.take();

                if (chunk.endOfStream) {

                    break;
                }

                if (chunk.data == null
                        || chunk.sampleCount <= 0) {

                    continue;
                }

                queuePcmToEncoder(
                        chunk.data,
                        chunk.sampleCount
                );
            }

            if (totalPcmSamples <= 0L) {

                throw new IOException(
                        "Запись не содержит PCM"
                );
            }

            queueEndOfStream();

            drainEncoder(
                    true
            );

            writeMuxerEndMarker();

            pipelineSuccess =
                    muxerStarted
                            && muxerTrackIndex >= 0;

        } catch (InterruptedException e) {

            Thread.currentThread()
                    .interrupt();

            Log.e(
                    TAG,
                    "Encoder thread прерван",
                    e
            );

            pipelineSuccess =
                    false;

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Ошибка encoder pipeline",
                    e
            );

            pipelineSuccess =
                    false;

        } finally {

            /*
             * Если encoder упал раньше времени,
             * прекращаем capture.
             */
            if (!pipelineSuccess) {

                stopRequested =
                        true;

                requestAudioRecordStop();
            }

            releaseEncoder();

            boolean muxerSuccess =
                    stopAndReleaseMuxer();

            boolean fileValid =
                    outputFile != null
                            && outputFile.exists()
                            && outputFile.isFile()
                            && outputFile.length() > 0L;

            encoderSuccess =
                    pipelineSuccess
                            && muxerSuccess
                            && fileValid;

            if (!encoderSuccess) {

                deleteOutputFileQuietly();
            }

            Log.d(
                    TAG,
                    "Encoder завершён. success="
                            + encoderSuccess
                            + ", pcmSamples="
                            + totalPcmSamples
                            + ", durationUs="
                            + samplesToTimeUs(
                            totalPcmSamples
                    )
            );
        }
    }

    private void queuePcmToEncoder(
            short[] pcm,
            int sampleCount
    ) throws IOException {

        MediaCodec localEncoder =
                encoder;

        if (localEncoder == null) {

            throw new IOException(
                    "AAC encoder отсутствует"
            );
        }

        int sourceOffset =
                0;

        while (sourceOffset
                < sampleCount) {

            int inputIndex =
                    localEncoder.dequeueInputBuffer(
                            CODEC_INPUT_TIMEOUT_US
                    );

            if (inputIndex
                    == MediaCodec.INFO_TRY_AGAIN_LATER) {

                drainEncoder(
                        false
                );

                continue;
            }

            if (inputIndex < 0) {

                drainEncoder(
                        false
                );

                continue;
            }

            ByteBuffer inputBuffer =
                    localEncoder.getInputBuffer(
                            inputIndex
                    );

            if (inputBuffer == null) {

                throw new IOException(
                        "AAC input buffer = null"
                );
            }

            inputBuffer.clear();

            inputBuffer.order(
                    ByteOrder.nativeOrder()
            );

            int capacitySamples =
                    inputBuffer.remaining()
                            / PCM_BYTES_PER_SAMPLE;

            if (capacitySamples <= 0) {

                throw new IOException(
                        "AAC input buffer слишком мал"
                );
            }

            int chunkSamples =
                    Math.min(
                            capacitySamples,
                            sampleCount
                                    - sourceOffset
                    );

            for (int i = 0;
                 i < chunkSamples;
                 i++) {

                inputBuffer.putShort(
                        pcm[
                                sourceOffset
                                        + i
                                ]
                );
            }

            long presentationTimeUs =
                    samplesToTimeUs(
                            totalPcmSamples
                    );

            localEncoder.queueInputBuffer(
                    inputIndex,
                    0,
                    chunkSamples
                            * PCM_BYTES_PER_SAMPLE,
                    presentationTimeUs,
                    0
            );

            totalPcmSamples +=
                    chunkSamples;

            sourceOffset +=
                    chunkSamples;

            drainEncoder(
                    false
            );
        }
    }

    private void queueEndOfStream()
            throws IOException {

        MediaCodec localEncoder =
                encoder;

        if (localEncoder == null) {

            throw new IOException(
                    "AAC encoder отсутствует"
            );
        }

        int waitCount =
                0;

        while (true) {

            int inputIndex =
                    localEncoder.dequeueInputBuffer(
                            CODEC_INPUT_TIMEOUT_US
                    );

            if (inputIndex >= 0) {

                localEncoder.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        samplesToTimeUs(
                                totalPcmSamples
                        ),
                        MediaCodec
                                .BUFFER_FLAG_END_OF_STREAM
                );

                return;
            }

            drainEncoder(
                    false
            );

            waitCount++;

            if (waitCount
                    > MAX_EOS_WAIT_COUNT) {

                throw new IOException(
                        "Timeout AAC EOS input"
                );
            }
        }
    }

    private void drainEncoder(
            boolean waitForEndOfStream
    ) throws IOException {

        MediaCodec localEncoder =
                encoder;

        if (localEncoder == null) {

            throw new IOException(
                    "AAC encoder отсутствует"
            );
        }

        MediaCodec.BufferInfo bufferInfo =
                new MediaCodec.BufferInfo();

        int waitCount =
                0;

        while (true) {

            long timeoutUs =
                    waitForEndOfStream
                            ? CODEC_EOS_TIMEOUT_US
                            : CODEC_POLL_TIMEOUT_US;

            int outputIndex =
                    localEncoder.dequeueOutputBuffer(
                            bufferInfo,
                            timeoutUs
                    );

            if (outputIndex
                    == MediaCodec.INFO_TRY_AGAIN_LATER) {

                if (!waitForEndOfStream) {
                    return;
                }

                waitCount++;

                if (waitCount
                        > MAX_EOS_WAIT_COUNT) {

                    throw new IOException(
                            "Timeout AAC EOS output"
                    );
                }

                continue;
            }

            waitCount =
                    0;

            if (outputIndex
                    == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                if (muxerStarted) {

                    throw new IOException(
                            "AAC format изменился повторно"
                    );
                }

                MediaMuxer localMuxer =
                        muxer;

                if (localMuxer == null) {

                    throw new IOException(
                            "MediaMuxer отсутствует"
                    );
                }

                MediaFormat outputFormat =
                        localEncoder.getOutputFormat();

                muxerTrackIndex =
                        localMuxer.addTrack(
                                outputFormat
                        );

                localMuxer.start();

                muxerStarted =
                        true;

                Log.d(
                        TAG,
                        "MediaMuxer started"
                );

                continue;
            }

            if (outputIndex < 0) {
                continue;
            }

            ByteBuffer encodedBuffer =
                    localEncoder.getOutputBuffer(
                            outputIndex
                    );

            if (encodedBuffer == null) {

                localEncoder.releaseOutputBuffer(
                        outputIndex,
                        false
                );

                throw new IOException(
                        "AAC output buffer = null"
                );
            }

            boolean endOfStream =
                    (bufferInfo.flags
                            & MediaCodec
                            .BUFFER_FLAG_END_OF_STREAM)
                            != 0;

            if ((bufferInfo.flags
                    & MediaCodec
                    .BUFFER_FLAG_CODEC_CONFIG)
                    != 0) {

                bufferInfo.size =
                        0;
            }

            if (bufferInfo.size > 0) {

                if (!muxerStarted
                        || muxerTrackIndex < 0) {

                    localEncoder.releaseOutputBuffer(
                            outputIndex,
                            false
                    );

                    throw new IOException(
                            "AAC sample получен до старта muxer"
                    );
                }

                if (firstEncoderPresentationTimeUs
                        == Long.MIN_VALUE) {

                    firstEncoderPresentationTimeUs =
                            bufferInfo.presentationTimeUs;
                }

                long normalizedTimeUs =
                        bufferInfo.presentationTimeUs
                                - firstEncoderPresentationTimeUs;

                if (normalizedTimeUs < 0L) {

                    normalizedTimeUs =
                            0L;
                }

                if (lastMuxerPresentationTimeUs
                        >= 0L
                        && normalizedTimeUs
                        <= lastMuxerPresentationTimeUs) {

                    normalizedTimeUs =
                            lastMuxerPresentationTimeUs
                                    + 1L;
                }

                bufferInfo.presentationTimeUs =
                        normalizedTimeUs;

                encodedBuffer.position(
                        bufferInfo.offset
                );

                encodedBuffer.limit(
                        bufferInfo.offset
                                + bufferInfo.size
                );

                MediaMuxer localMuxer =
                        muxer;

                if (localMuxer == null) {

                    localEncoder.releaseOutputBuffer(
                            outputIndex,
                            false
                    );

                    throw new IOException(
                            "MediaMuxer отсутствует"
                    );
                }

                localMuxer.writeSampleData(
                        muxerTrackIndex,
                        encodedBuffer,
                        bufferInfo
                );

                lastMuxerPresentationTimeUs =
                        normalizedTimeUs;
            }

            localEncoder.releaseOutputBuffer(
                    outputIndex,
                    false
            );

            if (endOfStream) {
                return;
            }
        }
    }

    private void writeMuxerEndMarker()
            throws IOException {

        if (!muxerStarted
                || muxerTrackIndex < 0
                || lastMuxerPresentationTimeUs < 0L) {

            throw new IOException(
                    "MediaMuxer не содержит AAC"
            );
        }

        MediaMuxer localMuxer =
                muxer;

        if (localMuxer == null) {

            throw new IOException(
                    "MediaMuxer отсутствует"
            );
        }

        long expectedDurationUs =
                samplesToTimeUs(
                        totalPcmSamples
                );

        long endTimeUs =
                Math.max(
                        expectedDurationUs,
                        lastMuxerPresentationTimeUs
                                + 1L
                );

        ByteBuffer emptyBuffer =
                ByteBuffer.allocate(1);

        MediaCodec.BufferInfo endInfo =
                new MediaCodec.BufferInfo();

        endInfo.set(
                0,
                0,
                endTimeUs,
                MediaCodec
                        .BUFFER_FLAG_END_OF_STREAM
        );

        localMuxer.writeSampleData(
                muxerTrackIndex,
                emptyBuffer,
                endInfo
        );

        Log.d(
                TAG,
                "Финальная PCM-длительность: "
                        + expectedDurationUs
                        + " us"
        );
    }

    private long samplesToTimeUs(
            long pcmSamples
    ) {

        if (pcmSamples <= 0L) {
            return 0L;
        }

        return pcmSamples
                * MICROSECONDS_PER_SECOND
                / SAMPLE_RATE;
    }

    private void updateAmplitude(
            short[] pcm,
            int sampleCount
    ) {

        if (pcm == null
                || sampleCount <= 0) {

            maxAmplitude = 0;

            return;
        }

        double sumSquares =
                0.0;

        for (int i = 0;
             i < sampleCount;
             i++) {

            double normalized =
                    pcm[i] / 32768.0;

            sumSquares +=
                    normalized
                            * normalized;
        }

        double rms =
                Math.sqrt(
                        sumSquares
                                / sampleCount
                );

        int targetLevel =
                rmsToLevel(
                        rms
                );

        int currentLevel =
                maxAmplitude;

        /*
         * Attack / Release envelope.
         *
         * Рост громкости отображаем быстро,
         * спад — немного плавнее.
         */
        float smoothing;

        if (targetLevel > currentLevel) {

            smoothing =
                    0.65f;

        } else {

            smoothing =
                    0.20f;
        }

        int smoothedLevel =
                Math.round(
                        currentLevel
                                + (
                                targetLevel
                                        - currentLevel
                        )
                                * smoothing
                );

        maxAmplitude =
                Math.max(
                        0,
                        Math.min(
                                100,
                                smoothedLevel
                        )
                );
    }

    private int rmsToLevel(
            double rms
    ) {

        /*
         * Нижняя граница визуализатора.
         *
         * Всё тише -60 dB считаем
         * практически тишиной.
         */
        final double minDb =
                -60.0;

        if (rms <= 0.0) {
            return 0;
        }

        double db =
                20.0
                        * Math.log10(
                        rms
                );

        if (db <= minDb) {
            return 0;
        }

        if (db >= 0.0) {
            return 100;
        }

        double normalized =
                (db - minDb)
                        / -minDb;

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

    private void prepareOutputFile(
            File file
    ) throws IOException {

        File parent =
                file.getParentFile();

        if (parent == null) {

            throw new IOException(
                    "Папка записи отсутствует"
            );
        }

        if (!parent.exists()
                && !parent.mkdirs()) {

            throw new IOException(
                    "Не удалось создать папку записи"
            );
        }

        if (file.exists()
                && !file.delete()) {

            throw new IOException(
                    "Не удалось удалить старый файл"
            );
        }
    }

    @SuppressLint("MissingPermission")
    private void prepareAudioRecord()
            throws IOException {

        int minBufferSize =
                AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        PCM_ENCODING
                );

        if (minBufferSize <= 0) {

            throw new IOException(
                    "Некорректный AudioRecord buffer: "
                            + minBufferSize
            );
        }

        int requestedBufferSize =
                Math.max(
                        minBufferSize * 4,
                        PCM_BUFFER_SAMPLES
                                * PCM_BYTES_PER_SAMPLE
                                * 8
                );

        AudioRecord createdAudioRecord =
                new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        PCM_ENCODING,
                        requestedBufferSize
                );

        if (createdAudioRecord.getState()
                != AudioRecord.STATE_INITIALIZED) {

            createdAudioRecord.release();

            throw new IOException(
                    "AudioRecord не инициализирован"
            );
        }

        audioRecord =
                createdAudioRecord;
    }

    private void prepareEncoder()
            throws IOException {

        MediaFormat format =
                MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_AAC,
                        SAMPLE_RATE,
                        CHANNEL_COUNT
                );

        format.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo
                        .CodecProfileLevel
                        .AACObjectLC
        );

        format.setInteger(
                MediaFormat.KEY_BIT_RATE,
                AAC_BIT_RATE
        );

        format.setInteger(
                MediaFormat.KEY_MAX_INPUT_SIZE,
                PCM_BUFFER_SAMPLES
                        * PCM_BYTES_PER_SAMPLE
                        * 8
        );

        MediaCodec createdEncoder =
                MediaCodec.createEncoderByType(
                        MediaFormat.MIMETYPE_AUDIO_AAC
                );

        createdEncoder.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
        );

        createdEncoder.start();

        encoder =
                createdEncoder;
    }

    private void prepareMuxer()
            throws IOException {

        if (outputFile == null) {

            throw new IOException(
                    "Файл записи отсутствует"
            );
        }

        muxer =
                new MediaMuxer(
                        outputFile.getAbsolutePath(),
                        MediaMuxer.OutputFormat
                                .MUXER_OUTPUT_MPEG_4
                );
    }

    private void resetSessionState() {

        pcmQueue.clear();

        recording =
                false;

        paused =
                false;

        stopRequested =
                false;

        captureSuccess =
                false;

        encoderSuccess =
                false;

        maxAmplitude =
                0;

        totalPcmSamples =
                0L;

        firstEncoderPresentationTimeUs =
                Long.MIN_VALUE;

        lastMuxerPresentationTimeUs =
                -1L;

        muxerStarted =
                false;

        muxerTrackIndex =
                -1;
    }

    private void requestAudioRecordStop() {

        AudioRecord localAudioRecord =
                audioRecord;

        if (localAudioRecord == null) {
            return;
        }

        try {

            if (localAudioRecord.getRecordingState()
                    == AudioRecord.RECORDSTATE_RECORDING) {

                localAudioRecord.stop();
            }

        } catch (Exception e) {

            Log.w(
                    TAG,
                    "Ошибка принудительного AudioRecord.stop()",
                    e
            );
        }
    }

    private void stopAndReleaseAudioRecord() {

        AudioRecord localAudioRecord =
                audioRecord;

        audioRecord =
                null;

        if (localAudioRecord == null) {
            return;
        }

        try {

            if (localAudioRecord.getRecordingState()
                    == AudioRecord.RECORDSTATE_RECORDING) {

                localAudioRecord.stop();
            }

        } catch (Exception e) {

            Log.w(
                    TAG,
                    "Ошибка AudioRecord.stop()",
                    e
            );
        }

        try {

            localAudioRecord.release();

        } catch (Exception e) {

            Log.w(
                    TAG,
                    "Ошибка AudioRecord.release()",
                    e
            );
        }
    }

    private void releaseEncoder() {

        MediaCodec localEncoder =
                encoder;

        encoder =
                null;

        if (localEncoder == null) {
            return;
        }

        try {

            localEncoder.stop();

        } catch (Exception e) {

            Log.w(
                    TAG,
                    "Ошибка MediaCodec.stop()",
                    e
            );
        }

        try {

            localEncoder.release();

        } catch (Exception e) {

            Log.w(
                    TAG,
                    "Ошибка MediaCodec.release()",
                    e
            );
        }
    }

    private boolean stopAndReleaseMuxer() {

        MediaMuxer localMuxer =
                muxer;

        muxer =
                null;

        if (localMuxer == null) {
            return false;
        }

        boolean success =
                muxerStarted;

        if (muxerStarted) {

            try {

                localMuxer.stop();

            } catch (Exception e) {

                Log.e(
                        TAG,
                        "Ошибка MediaMuxer.stop()",
                        e
                );

                success =
                        false;
            }
        }

        try {

            localMuxer.release();

        } catch (Exception e) {

            Log.w(
                    TAG,
                    "Ошибка MediaMuxer.release()",
                    e
            );

            success =
                    false;
        }

        muxerStarted =
                false;

        muxerTrackIndex =
                -1;

        return success;
    }

    private void cleanupAfterStartFailure() {

        requestAudioRecordStop();

        stopAndReleaseAudioRecord();

        releaseEncoder();

        MediaMuxer localMuxer =
                muxer;

        muxer =
                null;

        if (localMuxer != null) {

            try {

                if (muxerStarted) {

                    localMuxer.stop();
                }

            } catch (Exception ignored) {
            }

            try {

                localMuxer.release();

            } catch (Exception ignored) {
            }
        }

        muxerStarted =
                false;

        muxerTrackIndex =
                -1;
    }

    private void joinThread(
            Thread thread,
            long timeoutMs,
            String name
    ) {

        if (thread == null
                || thread == Thread.currentThread()) {

            return;
        }

        try {

            thread.join(
                    timeoutMs
            );

        } catch (InterruptedException e) {

            Thread.currentThread()
                    .interrupt();

            Log.w(
                    TAG,
                    "Ожидание "
                            + name
                            + " thread прервано",
                    e
            );
        }

        if (thread.isAlive()) {

            Log.e(
                    TAG,
                    name
                            + " thread не завершился"
            );

            thread.interrupt();
        }
    }

    private boolean isThreadAlive(
            Thread thread
    ) {

        return thread != null
                && thread.isAlive();
    }

    private void deleteOutputFileQuietly() {

        File file =
                outputFile;

        if (file == null
                || !file.exists()) {

            return;
        }

        if (!file.delete()) {

            Log.w(
                    TAG,
                    "Не удалось удалить файл: "
                            + file.getAbsolutePath()
            );
        }
    }

    public int getMaxAmplitude() {

        if (!recording
                || paused) {

            return 0;
        }

        return maxAmplitude;
    }

    public File getOutputFile() {

        return outputFile;
    }

    public boolean hasValidRecording() {

        return outputFile != null
                && outputFile.exists()
                && outputFile.isFile()
                && outputFile.length() > 0L;
    }

    public boolean isRecording() {

        return recording;
    }

    public boolean isPaused() {

        return paused;
    }

    public String getFilePath() {

        if (outputFile == null) {
            return null;
        }

        return outputFile
                .getAbsolutePath();
    }

    private static final class PcmChunk {

        private final short[] data;

        private final int sampleCount;

        private final boolean endOfStream;

        private PcmChunk(
                short[] data,
                int sampleCount,
                boolean endOfStream
        ) {

            this.data =
                    data;

            this.sampleCount =
                    sampleCount;

            this.endOfStream =
                    endOfStream;
        }
    }
}