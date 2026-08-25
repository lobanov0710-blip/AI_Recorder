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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WaveformExtractor {

    private static final String TAG =
            "WaveformExtractor";

    private static final int TARGET_POINTS =
            180;

    private static final long CODEC_TIMEOUT_US =
            10_000L;

    private static final double MIN_DB =
            -60.0;

    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor();

    private final Handler mainHandler =
            new Handler(
                    Looper.getMainLooper()
            );

    public interface Callback {

        void onWaveformReady(
                int[] waveform
        );
    }

    public void extract(
            File file,
            Callback callback
    ) {

        if (file == null
                || !file.exists()
                || !file.isFile()) {

            deliverResult(
                    callback,
                    new int[0]
            );

            return;
        }

        WaveformCache cache =
                WaveformCache.getInstance();

        int[] cached =
                cache.get(
                        file.getAbsolutePath()
                );

        if (cached != null) {

            deliverResult(
                    callback,
                    cached
            );

            return;
        }

        EXECUTOR.execute(() -> {

            int[] result =
                    buildWaveform(
                            file
                    );

            cache.put(
                    file.getAbsolutePath(),
                    result
            );

            deliverResult(
                    callback,
                    result
            );
        });
    }

    private void deliverResult(
            Callback callback,
            int[] result
    ) {

        if (callback == null) {
            return;
        }

        mainHandler.post(() ->
                callback.onWaveformReady(
                        result
                )
        );
    }

    private int[] buildWaveform(
            File file
    ) {

        List<Integer> levels =
                new ArrayList<>();

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

            extractor.selectTrack(
                    audioTrack
            );

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

            decodeToLevels(
                    extractor,
                    decoder,
                    levels
            );

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Ошибка декодирования waveform",
                    e
            );

        } finally {

            if (decoder != null) {

                if (decoderStarted) {

                    try {

                        decoder.stop();

                    } catch (Exception e) {

                        Log.w(
                                TAG,
                                "Ошибка MediaCodec.stop()",
                                e
                        );
                    }
                }

                try {

                    decoder.release();

                } catch (Exception e) {

                    Log.w(
                            TAG,
                            "Ошибка MediaCodec.release()",
                            e
                    );
                }
            }

            try {

                extractor.release();

            } catch (Exception e) {

                Log.w(
                        TAG,
                        "Ошибка MediaExtractor.release()",
                        e
                );
            }
        }

        if (levels.isEmpty()) {
            return new int[0];
        }

        int[] waveform =
                new int[
                        levels.size()
                        ];

        for (int i = 0;
             i < levels.size();
             i++) {

            waveform[i] =
                    levels.get(i);
        }

        return compress(
                waveform,
                TARGET_POINTS
        );
    }

    private void decodeToLevels(
            MediaExtractor extractor,
            MediaCodec decoder,
            List<Integer> levels
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

            /*
             * Feed encoded AAC data
             * from MediaExtractor into decoder.
             */
            if (!inputFinished) {

                int inputIndex =
                        decoder.dequeueInputBuffer(
                                CODEC_TIMEOUT_US
                        );

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

            /*
             * Read DECODED PCM.
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

                levels.add(
                        level
                );
            }

            boolean endOfStream =
                    (bufferInfo.flags
                            & MediaCodec
                            .BUFFER_FLAG_END_OF_STREAM)
                            != 0;

            decoder.releaseOutputBuffer(
                    outputIndex,
                    false
            );

            if (endOfStream) {

                outputFinished =
                        true;
            }
        }
    }

    private int findAudioTrack(
            MediaExtractor extractor
    ) {

        for (int i = 0;
             i < extractor.getTrackCount();
             i++) {

            MediaFormat format =
                    extractor.getTrackFormat(i);

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

    private int[] compress(
            int[] source,
            int targetSize
    ) {

        if (source == null
                || source.length == 0) {

            return new int[0];
        }

        if (source.length <= targetSize) {

            return source;
        }

        int[] result =
                new int[
                        targetSize
                        ];

        float step =
                (float) source.length
                        / targetSize;

        for (int i = 0;
             i < targetSize;
             i++) {

            int start =
                    (int) (
                            i * step
                    );

            int end =
                    (int) (
                            (i + 1)
                                    * step
                    );

            end =
                    Math.max(
                            start + 1,
                            end
                    );

            end =
                    Math.min(
                            source.length,
                            end
                    );

            long sum =
                    0L;

            int count =
                    0;

            for (int j = start;
                 j < end;
                 j++) {

                sum +=
                        source[j];

                count++;
            }

            if (count > 0) {

                result[i] =
                        (int) (
                                sum / count
                        );
            }
        }

        return result;
    }
}