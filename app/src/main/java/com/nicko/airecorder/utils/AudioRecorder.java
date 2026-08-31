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

    /*
     * =========================================================
     * AUDIO CONFIGURATION
     * =========================================================
     */

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
     * =========================================================
     * CODEC TIMEOUTS
     * =========================================================
     */

    private static final long CODEC_INPUT_TIMEOUT_US =
            10_000L;

    private static final long CODEC_POLL_TIMEOUT_US =
            0L;

    private static final long CODEC_EOS_TIMEOUT_US =
            10_000L;

    private static final int MAX_EOS_WAIT_COUNT =
            300;

    /*
     * =========================================================
     * WORKER TIMEOUTS
     * =========================================================
     */

    private static final long CAPTURE_JOIN_TIMEOUT_MS =
            3000L;

    private static final long ENCODER_JOIN_TIMEOUT_MS =
            10000L;

    /*
     * =========================================================
     * END MARKER
     * =========================================================
     */

    private static final PcmChunk END_CHUNK =
            new PcmChunk(
                    null,
                    0,
                    true
            );

    /*
     * =========================================================
     * GLOBAL LIFECYCLE
     * =========================================================
     */

    private final Object lifecycleLock =
            new Object();

    /*
     * Только одна RecorderSession может быть активной.
     *
     * Важно:
     * AudioRecord / MediaCodec / MediaMuxer / queue / threads
     * больше НЕ являются общими ресурсами AudioRecorder.
     */
    private volatile RecorderSession currentSession;

    /*
     * Последний успешно финализированный файл.
     *
     * RecordServiceController вызывает:
     *
     * stopRecording()
     *     ↓
     * getOutputFile()
     *
     * поэтому после очистки currentSession
     * сохраняем ссылку на успешный output.
     */
    private volatile File lastCompletedOutputFile;

    public AudioRecorder() {
    }

    /*
     * =========================================================
     * START
     * =========================================================
     */

    public void startRecording(
            @NonNull File outputFile
    ) throws IOException {

        synchronized (lifecycleLock) {

            cleanupTerminatedSessionLocked();

            RecorderSession existing =
                    currentSession;

            if (existing != null) {

                /*
                 * Повторный START во время уже идущей записи
                 * оставляем idempotent.
                 */
                if (existing.state == SessionState.PREPARING
                        || existing.state == SessionState.RECORDING
                        || existing.state == SessionState.PAUSED) {

                    return;
                }

                /*
                 * Главное production-правило:
                 *
                 * пока старая session реально не закончилась,
                 * новую запись не создаём.
                 */
                throw new IllegalStateException(
                        "Предыдущая запись ещё завершается"
                );
            }

            prepareOutputFile(
                    outputFile
            );

            RecorderSession session =
                    new RecorderSession(
                            outputFile
                    );

            currentSession =
                    session;

            try {

                prepareAudioRecord(
                        session
                );

                prepareEncoder(
                        session
                );

                prepareMuxer(
                        session
                );

                AudioRecord localAudioRecord =
                        getAudioRecord(
                                session
                        );

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
                 * Каждый worker получает конкретную session.
                 *
                 * Старый worker физически не сможет
                 * обратиться к ресурсам новой записи.
                 */
                Thread newEncoderThread =
                        new Thread(
                                () -> encoderLoop(session),
                                "AIRecorder-Encoder"
                        );

                Thread newCaptureThread =
                        new Thread(
                                () -> captureLoop(session),
                                "AIRecorder-Capture"
                        );

                session.encoderThread =
                        newEncoderThread;

                session.captureThread =
                        newCaptureThread;

                session.state =
                        SessionState.RECORDING;

                /*
                 * Encoder запускаем первым.
                 * Он ждёт первый PCM chunk.
                 */
                newEncoderThread.start();

                newCaptureThread.start();

                Log.d(
                        TAG,
                        "RecorderSession запущена: "
                                + outputFile.getAbsolutePath()
                );

            } catch (IOException
                     | RuntimeException e) {

                Log.e(
                        TAG,
                        "Ошибка создания RecorderSession",
                        e
                );

                abortStartSession(
                        session
                );

                synchronized (lifecycleLock) {

                    if (currentSession == session
                            && session.state
                            == SessionState.TERMINATED) {

                        currentSession =
                                null;
                    }
                }

                throw e;
            }
        }
    }

    /*
     * =========================================================
     * PAUSE
     * =========================================================
     */

    public boolean pauseRecording() {

        synchronized (lifecycleLock) {

            RecorderSession session =
                    currentSession;

            if (session == null
                    || session.state
                    != SessionState.RECORDING
                    || session.stopRequested
                    || session.forceFailure) {

                return false;
            }

            /*
             * AudioRecord не останавливаем.
             *
             * Capture продолжает обслуживать hardware buffer,
             * но PCM во время Pause выбрасывается.
             */
            session.paused =
                    true;

            session.maxAmplitude =
                    0;

            session.state =
                    SessionState.PAUSED;

            Log.d(
                    TAG,
                    "Pause включён"
            );

            return true;
        }
    }

    /*
     * =========================================================
     * RESUME
     * =========================================================
     */

    public boolean resumeRecording() {

        synchronized (lifecycleLock) {

            RecorderSession session =
                    currentSession;

            if (session == null
                    || session.state
                    != SessionState.PAUSED
                    || session.stopRequested
                    || session.forceFailure) {

                return false;
            }

            session.paused =
                    false;

            session.state =
                    SessionState.RECORDING;

            Log.d(
                    TAG,
                    "Resume выполнен"
            );

            return true;
        }
    }

    /*
     * =========================================================
     * STOP
     * =========================================================
     */

    public boolean stopRecording() {

        RecorderSession session;

        synchronized (lifecycleLock) {

            session =
                    currentSession;

            if (session == null) {

                return false;
            }

            if (session.state
                    == SessionState.TERMINATED) {

                cleanupTerminatedSessionLocked();

                return false;
            }

            session.stopRequested =
                    true;

            session.paused =
                    false;

            session.maxAmplitude =
                    0;

            session.state =
                    SessionState.STOPPING;
        }

        /*
         * Прерываем blocking AudioRecord.read().
         */
        requestAudioRecordStop(
                session
        );

        /*
         * =====================================================
         * CAPTURE
         * =====================================================
         */

        boolean captureFinishedInTime =
                joinThread(
                        session.captureThread,
                        CAPTURE_JOIN_TIMEOUT_MS,
                        "capture"
                );

        if (!captureFinishedInTime) {

            /*
             * Capture завис дольше допустимого.
             *
             * Запись уже не считаем безопасно финализируемой.
             */
            session.forceFailure =
                    true;

            /*
             * Encoder не должен бесконечно ждать END,
             * если Capture не дошёл до своего finally.
             */
            session.pcmQueue.offer(
                    END_CHUNK
            );
        }

        /*
         * =====================================================
         * ENCODER
         * =====================================================
         */

        boolean encoderFinishedInTime =
                joinThread(
                        session.encoderThread,
                        ENCODER_JOIN_TIMEOUT_MS,
                        "encoder"
                );

        if (!encoderFinishedInTime) {

            session.forceFailure =
                    true;

            /*
             * На всякий случай ещё раз просим Capture
             * прекратить чтение.
             */
            requestAudioRecordStop(
                    session
            );

            session.pcmQueue.offer(
                    END_CHUNK
            );
        }

        boolean captureThreadFinished =
                !isThreadAlive(
                        session.captureThread
                )
                        && session.captureFinished;

        boolean encoderThreadFinished =
                !isThreadAlive(
                        session.encoderThread
                )
                        && session.encoderFinished;

        boolean workersFinished =
                captureThreadFinished
                        && encoderThreadFinished;

        boolean fileValid =
                isFileValidBasic(
                        session.outputFile
                );

        boolean success =
                workersFinished
                        && !session.forceFailure
                        && session.captureSuccess
                        && session.encoderSuccess
                        && fileValid;

        if (success) {

            lastCompletedOutputFile =
                    session.outputFile;

        } else {

            Log.e(
                    TAG,
                    "Финализация RecorderSession завершилась с ошибкой"
            );

            /*
             * Если worker всё ещё жив —
             * файл здесь не удаляем.
             *
             * Encoder удалит его после release собственных
             * MediaCodec / MediaMuxer.
             */
            if (workersFinished) {

                deleteOutputFileQuietly(
                        session
                );
            }
        }

        synchronized (lifecycleLock) {

            if (workersFinished) {

                session.state =
                        SessionState.TERMINATED;

                if (currentSession
                        == session) {

                    currentSession =
                            null;
                }

            } else {

                /*
                 * КРИТИЧЕСКИ ВАЖНО:
                 *
                 * не обнуляем currentSession.
                 *
                 * Пока старые workers реально живы,
                 * следующая запись на этом AudioRecorder
                 * будет запрещена.
                 */
                session.state =
                        SessionState.STOPPING;
            }
        }

        return success;
    }

    /*
     * =========================================================
     * CAPTURE WORKER
     * =========================================================
     */

    private void captureLoop(
            RecorderSession session
    ) {

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

            while (!session.stopRequested) {

                AudioRecord localAudioRecord =
                        getAudioRecord(
                                session
                        );

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
                     * Pause проверяем ПОСЛЕ read.
                     *
                     * Hardware buffer продолжает обслуживаться.
                     */
                    if (session.paused) {

                        session.maxAmplitude =
                                0;

                        continue;
                    }

                    updateAmplitude(
                            session,
                            pcmBuffer,
                            readSamples
                    );

                    short[] copy =
                            Arrays.copyOf(
                                    pcmBuffer,
                                    readSamples
                            );

                    /*
                     * P0.1:
                     * очередь пока намеренно остаётся unbounded.
                     *
                     * Capacity / overflow policy будет P0.2.
                     */
                    session.pcmQueue.put(
                            new PcmChunk(
                                    copy,
                                    readSamples,
                                    false
                            )
                    );

                    continue;
                }

                if (session.stopRequested) {

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
             * Normal STOP может interrupt'ить worker.
             * Timeout/failure — уже нет.
             */
            success =
                    session.stopRequested
                            && !session.forceFailure;

            if (!session.stopRequested) {

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

            /*
             * forceFailure имеет приоритет
             * над обычным успешным выходом.
             */
            session.captureSuccess =
                    success
                            && !session.forceFailure;

            stopAndReleaseAudioRecord(
                    session
            );

            /*
             * END всегда должен идти после PCM,
             * когда Capture завершился штатно.
             */
            session.pcmQueue.offer(
                    END_CHUNK
            );

            if (!session.captureSuccess) {

                session.stopRequested =
                        true;
            }

            session.captureFinished =
                    true;

            markSessionTerminatedIfFinished(
                    session
            );

            Log.d(
                    TAG,
                    "Capture завершён. success="
                            + session.captureSuccess
                            + ", queue="
                            + session.pcmQueue.size()
            );
        }
    }

    /*
     * =========================================================
     * ENCODER WORKER
     * =========================================================
     */

    private void encoderLoop(
            RecorderSession session
    ) {

        boolean pipelineSuccess =
                false;

        try {

            while (true) {

                PcmChunk chunk =
                        session.pcmQueue.take();

                if (chunk.endOfStream) {

                    break;
                }

                if (chunk.data == null
                        || chunk.sampleCount <= 0) {

                    continue;
                }

                queuePcmToEncoder(
                        session,
                        chunk.data,
                        chunk.sampleCount
                );
            }

            /*
             * Если stop timeout уже признал session failed,
             * больше не пытаемся считать её успешной.
             */
            if (session.forceFailure) {

                throw new IOException(
                        "RecorderSession принудительно завершена"
                );
            }

            if (session.totalPcmSamples <= 0L) {

                throw new IOException(
                        "Запись не содержит PCM"
                );
            }

            queueEndOfStream(
                    session
            );

            drainEncoder(
                    session,
                    true
            );

            writeMuxerEndMarker(
                    session
            );

            pipelineSuccess =
                    session.muxerStarted
                            && session.muxerTrackIndex >= 0;

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

            if (!pipelineSuccess) {

                session.stopRequested =
                        true;

                session.forceFailure =
                        true;

                requestAudioRecordStop(
                        session
                );
            }

            /*
             * Освобождаются ТОЛЬКО ресурсы этой session.
             */
            releaseEncoder(
                    session
            );

            boolean muxerSuccess =
                    stopAndReleaseMuxer(
                            session
                    );

            boolean fileValid =
                    isFileValidBasic(
                            session.outputFile
                    );

            session.encoderSuccess =
                    pipelineSuccess
                            && muxerSuccess
                            && fileValid
                            && !session.forceFailure;

            if (!session.encoderSuccess) {

                deleteOutputFileQuietly(
                        session
                );
            }

            session.encoderFinished =
                    true;

            markSessionTerminatedIfFinished(
                    session
            );

            Log.d(
                    TAG,
                    "Encoder завершён. success="
                            + session.encoderSuccess
                            + ", pcmSamples="
                            + session.totalPcmSamples
                            + ", durationUs="
                            + samplesToTimeUs(
                            session.totalPcmSamples
                    )
            );
        }
    }

    /*
     * =========================================================
     * PCM → AAC INPUT
     * =========================================================
     */

    private void queuePcmToEncoder(
            RecorderSession session,
            short[] pcm,
            int sampleCount
    ) throws IOException {

        MediaCodec localEncoder =
                getEncoder(
                        session
                );

        if (localEncoder == null) {

            throw new IOException(
                    "AAC encoder отсутствует"
            );
        }

        int sourceOffset =
                0;

        while (sourceOffset
                < sampleCount) {

            if (session.forceFailure) {

                throw new IOException(
                        "RecorderSession отменена"
                );
            }

            int inputIndex =
                    localEncoder.dequeueInputBuffer(
                            CODEC_INPUT_TIMEOUT_US
                    );

            if (inputIndex
                    == MediaCodec.INFO_TRY_AGAIN_LATER) {

                drainEncoder(
                        session,
                        false
                );

                continue;
            }

            if (inputIndex < 0) {

                drainEncoder(
                        session,
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
                            session.totalPcmSamples
                    );

            localEncoder.queueInputBuffer(
                    inputIndex,
                    0,
                    chunkSamples
                            * PCM_BYTES_PER_SAMPLE,
                    presentationTimeUs,
                    0
            );

            session.totalPcmSamples +=
                    chunkSamples;

            sourceOffset +=
                    chunkSamples;

            drainEncoder(
                    session,
                    false
            );
        }
    }

    /*
     * =========================================================
     * AAC EOS
     * =========================================================
     */

    private void queueEndOfStream(
            RecorderSession session
    ) throws IOException {

        MediaCodec localEncoder =
                getEncoder(
                        session
                );

        if (localEncoder == null) {

            throw new IOException(
                    "AAC encoder отсутствует"
            );
        }

        int waitCount =
                0;

        while (true) {

            if (session.forceFailure) {

                throw new IOException(
                        "RecorderSession отменена до AAC EOS"
                );
            }

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
                                session.totalPcmSamples
                        ),
                        MediaCodec
                                .BUFFER_FLAG_END_OF_STREAM
                );

                return;
            }

            drainEncoder(
                    session,
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

    /*
     * =========================================================
     * AAC OUTPUT → MUXER
     * =========================================================
     */

    private void drainEncoder(
            RecorderSession session,
            boolean waitForEndOfStream
    ) throws IOException {

        MediaCodec localEncoder =
                getEncoder(
                        session
                );

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

            if (session.forceFailure
                    && !waitForEndOfStream) {

                throw new IOException(
                        "RecorderSession отменена"
                );
            }

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

                if (session.muxerStarted) {

                    throw new IOException(
                            "AAC format изменился повторно"
                    );
                }

                MediaMuxer localMuxer =
                        getMuxer(
                                session
                        );

                if (localMuxer == null) {

                    throw new IOException(
                            "MediaMuxer отсутствует"
                    );
                }

                MediaFormat outputFormat =
                        localEncoder.getOutputFormat();

                session.muxerTrackIndex =
                        localMuxer.addTrack(
                                outputFormat
                        );

                localMuxer.start();

                session.muxerStarted =
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

                if (!session.muxerStarted
                        || session.muxerTrackIndex < 0) {

                    localEncoder.releaseOutputBuffer(
                            outputIndex,
                            false
                    );

                    throw new IOException(
                            "AAC sample получен до старта muxer"
                    );
                }

                if (session.firstEncoderPresentationTimeUs
                        == Long.MIN_VALUE) {

                    session.firstEncoderPresentationTimeUs =
                            bufferInfo.presentationTimeUs;
                }

                long normalizedTimeUs =
                        bufferInfo.presentationTimeUs
                                - session.firstEncoderPresentationTimeUs;

                if (normalizedTimeUs < 0L) {

                    normalizedTimeUs =
                            0L;
                }

                if (session.lastMuxerPresentationTimeUs
                        >= 0L
                        && normalizedTimeUs
                        <= session.lastMuxerPresentationTimeUs) {

                    normalizedTimeUs =
                            session.lastMuxerPresentationTimeUs
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
                        getMuxer(
                                session
                        );

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
                        session.muxerTrackIndex,
                        encodedBuffer,
                        bufferInfo
                );

                session.lastMuxerPresentationTimeUs =
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

    /*
     * =========================================================
     * MUXER END MARKER
     * =========================================================
     */

    private void writeMuxerEndMarker(
            RecorderSession session
    ) throws IOException {

        if (!session.muxerStarted
                || session.muxerTrackIndex < 0
                || session.lastMuxerPresentationTimeUs < 0L) {

            throw new IOException(
                    "MediaMuxer не содержит AAC"
            );
        }

        MediaMuxer localMuxer =
                getMuxer(
                        session
                );

        if (localMuxer == null) {

            throw new IOException(
                    "MediaMuxer отсутствует"
            );
        }

        long expectedDurationUs =
                samplesToTimeUs(
                        session.totalPcmSamples
                );

        long endTimeUs =
                Math.max(
                        expectedDurationUs,
                        session.lastMuxerPresentationTimeUs
                                + 1L
                );

        ByteBuffer emptyBuffer =
                ByteBuffer.allocate(
                        1
                );

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
                session.muxerTrackIndex,
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

    /*
     * =========================================================
     * TIMESTAMPS
     * =========================================================
     */

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

    /*
     * =========================================================
     * AMPLITUDE
     * =========================================================
     */

    private void updateAmplitude(
            RecorderSession session,
            short[] pcm,
            int sampleCount
    ) {

        if (pcm == null
                || sampleCount <= 0) {

            session.maxAmplitude =
                    0;

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
                session.maxAmplitude;

        float smoothing;

        if (targetLevel
                > currentLevel) {

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

        session.maxAmplitude =
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

    /*
     * =========================================================
     * OUTPUT FILE
     * =========================================================
     */

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

    /*
     * =========================================================
     * AUDIO RECORD PREPARE
     * =========================================================
     */

    @SuppressLint("MissingPermission")
    private void prepareAudioRecord(
            RecorderSession session
    ) throws IOException {

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

        synchronized (session.resourceLock) {

            session.audioRecord =
                    createdAudioRecord;
        }
    }

    /*
     * =========================================================
     * ENCODER PREPARE
     * =========================================================
     */

    private void prepareEncoder(
            RecorderSession session
    ) throws IOException {

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

        /*
         * Сохраняем ссылку до configure/start.
         *
         * Если configure/start завершится ошибкой,
         * cleanup всё равно сможет release codec.
         */
        synchronized (session.resourceLock) {

            session.encoder =
                    createdEncoder;
        }

        createdEncoder.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
        );

        createdEncoder.start();
    }

    /*
     * =========================================================
     * MUXER PREPARE
     * =========================================================
     */

    private void prepareMuxer(
            RecorderSession session
    ) throws IOException {

        MediaMuxer createdMuxer =
                new MediaMuxer(
                        session.outputFile
                                .getAbsolutePath(),
                        MediaMuxer.OutputFormat
                                .MUXER_OUTPUT_MPEG_4
                );

        synchronized (session.resourceLock) {

            session.muxer =
                    createdMuxer;
        }
    }

    /*
     * =========================================================
     * GET SESSION RESOURCES
     * =========================================================
     */

    private AudioRecord getAudioRecord(
            RecorderSession session
    ) {

        synchronized (session.resourceLock) {

            return session.audioRecord;
        }
    }

    private MediaCodec getEncoder(
            RecorderSession session
    ) {

        synchronized (session.resourceLock) {

            return session.encoder;
        }
    }

    private MediaMuxer getMuxer(
            RecorderSession session
    ) {

        synchronized (session.resourceLock) {

            return session.muxer;
        }
    }

    /*
     * =========================================================
     * REQUEST AUDIO STOP
     * =========================================================
     */

    private void requestAudioRecordStop(
            RecorderSession session
    ) {

        synchronized (session.resourceLock) {

            AudioRecord localAudioRecord =
                    session.audioRecord;

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
    }

    /*
     * =========================================================
     * RELEASE AUDIO RECORD
     * =========================================================
     */

    private void stopAndReleaseAudioRecord(
            RecorderSession session
    ) {

        AudioRecord localAudioRecord;

        synchronized (session.resourceLock) {

            localAudioRecord =
                    session.audioRecord;

            session.audioRecord =
                    null;
        }

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

    /*
     * =========================================================
     * RELEASE ENCODER
     * =========================================================
     */

    private void releaseEncoder(
            RecorderSession session
    ) {

        MediaCodec localEncoder;

        synchronized (session.resourceLock) {

            localEncoder =
                    session.encoder;

            session.encoder =
                    null;
        }

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

    /*
     * =========================================================
     * RELEASE MUXER
     * =========================================================
     */

    private boolean stopAndReleaseMuxer(
            RecorderSession session
    ) {

        MediaMuxer localMuxer;

        boolean wasStarted;

        synchronized (session.resourceLock) {

            localMuxer =
                    session.muxer;

            session.muxer =
                    null;

            wasStarted =
                    session.muxerStarted;
        }

        if (localMuxer == null) {

            session.muxerStarted =
                    false;

            session.muxerTrackIndex =
                    -1;

            return false;
        }

        boolean success =
                wasStarted;

        if (wasStarted) {

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

        session.muxerStarted =
                false;

        session.muxerTrackIndex =
                -1;

        return success;
    }

    /*
     * =========================================================
     * START FAILURE
     * =========================================================
     */

    private void abortStartSession(
            RecorderSession session
    ) {

        session.forceFailure =
                true;

        session.stopRequested =
                true;

        session.paused =
                false;

        session.state =
                SessionState.STOPPING;

        requestAudioRecordStop(
                session
        );

        /*
         * Если encoder уже успел стартовать —
         * даём ему возможность выйти.
         */
        session.pcmQueue.offer(
                END_CHUNK
        );

        boolean captureDone =
                joinThread(
                        session.captureThread,
                        CAPTURE_JOIN_TIMEOUT_MS,
                        "capture-start-failure"
                );

        boolean encoderDone =
                joinThread(
                        session.encoderThread,
                        ENCODER_JOIN_TIMEOUT_MS,
                        "encoder-start-failure"
                );

        /*
         * Если конкретный worker вообще не стартовал
         * или уже завершён — cleanup его ресурса
         * выполняем здесь.
         */

        if (captureDone
                && !isThreadAlive(
                session.captureThread
        )) {

            stopAndReleaseAudioRecord(
                    session
            );

            session.captureSuccess =
                    false;

            session.captureFinished =
                    true;
        }

        if (encoderDone
                && !isThreadAlive(
                session.encoderThread
        )) {

            releaseEncoder(
                    session
            );

            stopAndReleaseMuxer(
                    session
            );

            session.encoderSuccess =
                    false;

            session.encoderFinished =
                    true;
        }

        markSessionTerminatedIfFinished(
                session
        );

        if (session.captureFinished
                && session.encoderFinished) {

            deleteOutputFileQuietly(
                    session
            );
        }
    }

    /*
     * =========================================================
     * SESSION TERMINATION
     * =========================================================
     */

    private void markSessionTerminatedIfFinished(
            RecorderSession session
    ) {

        if (!session.captureFinished
                || !session.encoderFinished) {

            return;
        }

        session.state =
                SessionState.TERMINATED;
    }

    private void cleanupTerminatedSessionLocked() {

        RecorderSession session =
                currentSession;

        if (session == null) {

            return;
        }

        if (session.state
                != SessionState.TERMINATED) {

            return;
        }

        if (isThreadAlive(
                session.captureThread
        )
                || isThreadAlive(
                session.encoderThread
        )) {

            /*
             * Теоретически state TERMINATED без завершённых
             * workers быть не должен.
             *
             * Но новый START всё равно не разрешаем.
             */
            return;
        }

        currentSession =
                null;
    }

    /*
     * =========================================================
     * THREAD JOIN
     * =========================================================
     */

    private boolean joinThread(
            Thread thread,
            long timeoutMs,
            String name
    ) {

        if (thread == null) {

            return true;
        }

        if (thread == Thread.currentThread()) {

            return false;
        }

        /*
         * NEW означает, что Thread объект создан,
         * но worker физически не стартовал.
         */
        if (!thread.isAlive()) {

            return true;
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

            return false;
        }

        if (thread.isAlive()) {

            Log.e(
                    TAG,
                    name
                            + " thread не завершился за "
                            + timeoutMs
                            + " ms"
            );

            /*
             * interrupt — только запрос.
             *
             * Ссылку на Thread после этого НЕ теряем.
             */
            thread.interrupt();

            return false;
        }

        return true;
    }

    private boolean isThreadAlive(
            Thread thread
    ) {

        return thread != null
                && thread.isAlive();
    }

    /*
     * =========================================================
     * FILE HELPERS
     * =========================================================
     */

    private boolean isFileValidBasic(
            File file
    ) {

        return file != null
                && file.exists()
                && file.isFile()
                && file.length() > 0L;
    }

    private void deleteOutputFileQuietly(
            RecorderSession session
    ) {

        if (session == null) {

            return;
        }

        File file =
                session.outputFile;

        if (!file.exists()) {

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

    /*
     * =========================================================
     * PUBLIC STATE
     * =========================================================
     */

    public int getMaxAmplitude() {

        RecorderSession session =
                currentSession;

        if (session == null
                || session.state
                != SessionState.RECORDING
                || session.paused
                || session.stopRequested) {

            return 0;
        }

        return session.maxAmplitude;
    }

    public File getOutputFile() {

        RecorderSession session =
                currentSession;

        if (session != null) {

            return session.outputFile;
        }

        return lastCompletedOutputFile;
    }

    public boolean hasValidRecording() {

        return isFileValidBasic(
                getOutputFile()
        );
    }

    public boolean isRecording() {

        RecorderSession session =
                currentSession;

        if (session == null) {

            return false;
        }

        return session.state
                == SessionState.RECORDING
                || session.state
                == SessionState.PAUSED;
    }

    public boolean isPaused() {

        RecorderSession session =
                currentSession;

        return session != null
                && session.state
                == SessionState.PAUSED;
    }

    public String getFilePath() {

        File file =
                getOutputFile();

        if (file == null) {

            return null;
        }

        return file.getAbsolutePath();
    }

    /*
     * =========================================================
     * SESSION STATE
     * =========================================================
     */

    private enum SessionState {

        PREPARING,

        RECORDING,

        PAUSED,

        STOPPING,

        TERMINATED
    }

    /*
     * =========================================================
     * RECORDER SESSION
     * =========================================================
     */

    private static final class RecorderSession {

        /*
         * Все native/media ресурсы этой записи
         * принадлежат только этой session.
         */
        private final Object resourceLock =
                new Object();

        private final File outputFile;

        /*
         * P0.1:
         * очередь намеренно остаётся unbounded.
         *
         * В P0.2 будет bounded capacity +
         * explicit overflow policy.
         */
        private final LinkedBlockingQueue<PcmChunk> pcmQueue =
                new LinkedBlockingQueue<>();

        private volatile SessionState state =
                SessionState.PREPARING;

        private volatile boolean paused =
                false;

        private volatile boolean stopRequested =
                false;

        private volatile boolean forceFailure =
                false;

        private volatile boolean captureSuccess =
                false;

        private volatile boolean encoderSuccess =
                false;

        private volatile boolean captureFinished =
                false;

        private volatile boolean encoderFinished =
                false;

        private volatile int maxAmplitude =
                0;

        private volatile Thread captureThread;

        private volatile Thread encoderThread;

        private AudioRecord audioRecord;

        private MediaCodec encoder;

        private MediaMuxer muxer;

        private volatile boolean muxerStarted =
                false;

        private volatile int muxerTrackIndex =
                -1;

        /*
         * Эти поля изменяет encoder worker.
         */
        private long totalPcmSamples =
                0L;

        private long firstEncoderPresentationTimeUs =
                Long.MIN_VALUE;

        private long lastMuxerPresentationTimeUs =
                -1L;

        private RecorderSession(
                @NonNull File outputFile
        ) {

            this.outputFile =
                    outputFile;
        }
    }

    /*
     * =========================================================
     * PCM CHUNK
     * =========================================================
     */

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