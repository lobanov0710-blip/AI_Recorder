package com.nicko.airecorder.utils;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

public final class AudioSegmentMerger {

    private static final String TAG =
            "AudioSegmentMerger";

    private static final int DEFAULT_BUFFER_SIZE =
            1024 * 1024;

    /*
     * AAC LC при 44.1 kHz:
     * примерно 1024 samples на frame.
     */
    private static final long DEFAULT_SAMPLE_DURATION_US =
            23_220L;

    public boolean merge(
            List<File> segments,
            File outputFile
    ) {

        if (segments == null
                || segments.isEmpty()
                || outputFile == null) {

            return false;
        }

        MediaMuxer muxer = null;

        boolean muxerStarted = false;

        boolean success = false;

        try {

            MediaFormat referenceFormat =
                    getAudioTrackFormat(
                            segments.get(0)
                    );

            if (referenceFormat == null) {

                Log.e(
                        TAG,
                        "Не найден аудиотрек первого сегмента"
                );

                return false;
            }

            if (outputFile.exists()
                    && !outputFile.delete()) {

                Log.e(
                        TAG,
                        "Не удалось удалить старый итоговый файл"
                );

                return false;
            }

            muxer =
                    new MediaMuxer(
                            outputFile.getAbsolutePath(),
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                    );

            int outputTrackIndex =
                    muxer.addTrack(
                            referenceFormat
                    );

            muxer.start();

            muxerStarted = true;

            long nextPresentationTimeUs = 0L;

            for (File segment : segments) {

                long result =
                        writeSegment(
                                segment,
                                muxer,
                                outputTrackIndex,
                                referenceFormat,
                                nextPresentationTimeUs
                        );

                if (result < 0L) {

                    throw new IOException(
                            "Не удалось объединить сегмент: "
                                    + segment.getAbsolutePath()
                    );

                }

                nextPresentationTimeUs =
                        result;

            }

            success = true;

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Ошибка объединения аудиосегментов",
                    e
            );

            success = false;

        } finally {

            if (muxer != null) {

                if (muxerStarted) {

                    try {

                        muxer.stop();

                    } catch (Exception e) {

                        Log.e(
                                TAG,
                                "Ошибка MediaMuxer.stop()",
                                e
                        );

                        success = false;

                    }

                }

                try {

                    muxer.release();

                } catch (Exception e) {

                    Log.w(
                            TAG,
                            "Ошибка MediaMuxer.release()",
                            e
                    );

                }

            }

            if (!success
                    && outputFile.exists()
                    && !outputFile.delete()) {

                Log.w(
                        TAG,
                        "Не удалось удалить повреждённый итоговый файл"
                );

            }

        }

        return success;

    }

    private long writeSegment(
            File segment,
            MediaMuxer muxer,
            int outputTrackIndex,
            MediaFormat referenceFormat,
            long timelineOffsetUs
    ) {

        if (segment == null
                || !segment.exists()
                || !segment.isFile()
                || segment.length() <= 0L) {

            return -1L;
        }

        MediaExtractor extractor =
                new MediaExtractor();

        try {

            extractor.setDataSource(
                    segment.getAbsolutePath()
            );

            int inputTrackIndex =
                    findAudioTrack(
                            extractor
                    );

            if (inputTrackIndex < 0) {

                Log.e(
                        TAG,
                        "Аудиотрек не найден: "
                                + segment.getAbsolutePath()
                );

                return -1L;
            }

            MediaFormat segmentFormat =
                    extractor.getTrackFormat(
                            inputTrackIndex
                    );

            if (!areFormatsCompatible(
                    referenceFormat,
                    segmentFormat
            )) {

                Log.e(
                        TAG,
                        "Формат сегмента отличается: "
                                + segment.getAbsolutePath()
                );

                return -1L;
            }

            extractor.selectTrack(
                    inputTrackIndex
            );

            int bufferSize =
                    DEFAULT_BUFFER_SIZE;

            if (segmentFormat.containsKey(
                    MediaFormat.KEY_MAX_INPUT_SIZE
            )) {

                bufferSize =
                        Math.max(
                                bufferSize,
                                segmentFormat.getInteger(
                                        MediaFormat.KEY_MAX_INPUT_SIZE
                                )
                        );

            }

            ByteBuffer buffer =
                    ByteBuffer.allocateDirect(
                            bufferSize
                    );

            MediaCodec.BufferInfo bufferInfo =
                    new MediaCodec.BufferInfo();

            long firstSampleTimeUs = -1L;

            long previousInputTimeUs = -1L;

            long previousOutputTimeUs = -1L;

            long sampleDurationUs =
                    DEFAULT_SAMPLE_DURATION_US;

            while (true) {

                buffer.clear();

                int sampleSize =
                        extractor.readSampleData(
                                buffer,
                                0
                        );

                if (sampleSize < 0) {
                    break;
                }

                long sampleTimeUs =
                        extractor.getSampleTime();

                if (sampleTimeUs < 0L) {
                    break;
                }

                if (firstSampleTimeUs < 0L) {

                    firstSampleTimeUs =
                            sampleTimeUs;

                }

                if (previousInputTimeUs >= 0L
                        && sampleTimeUs
                        > previousInputTimeUs) {

                    sampleDurationUs =
                            sampleTimeUs
                                    - previousInputTimeUs;

                }

                long normalizedTimeUs =
                        Math.max(
                                0L,
                                sampleTimeUs
                                        - firstSampleTimeUs
                        );

                long outputTimeUs =
                        timelineOffsetUs
                                + normalizedTimeUs;

                /*
                 * MediaMuxer требует монотонные timestamps.
                 */
                if (previousOutputTimeUs >= 0L
                        && outputTimeUs
                        <= previousOutputTimeUs) {

                    outputTimeUs =
                            previousOutputTimeUs + 1L;

                }

                bufferInfo.set(
                        0,
                        sampleSize,
                        outputTimeUs,
                        extractor.getSampleFlags()
                );

                buffer.position(0);

                buffer.limit(
                        sampleSize
                );

                muxer.writeSampleData(
                        outputTrackIndex,
                        buffer,
                        bufferInfo
                );

                previousInputTimeUs =
                        sampleTimeUs;

                previousOutputTimeUs =
                        outputTimeUs;

                extractor.advance();

            }

            if (previousOutputTimeUs < 0L) {

                return -1L;

            }

            return previousOutputTimeUs
                    + Math.max(
                    1L,
                    sampleDurationUs
            );

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Ошибка чтения сегмента: "
                            + segment.getAbsolutePath(),
                    e
            );

            return -1L;

        } finally {

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

    }

    private MediaFormat getAudioTrackFormat(
            File file
    ) {

        if (file == null
                || !file.exists()) {

            return null;
        }

        MediaExtractor extractor =
                new MediaExtractor();

        try {

            extractor.setDataSource(
                    file.getAbsolutePath()
            );

            int trackIndex =
                    findAudioTrack(
                            extractor
                    );

            if (trackIndex < 0) {
                return null;
            }

            return extractor.getTrackFormat(
                    trackIndex
            );

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Не удалось получить формат сегмента",
                    e
            );

            return null;

        } finally {

            try {

                extractor.release();

            } catch (Exception ignored) {

            }

        }

    }

    private int findAudioTrack(
            MediaExtractor extractor
    ) {

        int trackCount =
                extractor.getTrackCount();

        for (int i = 0;
             i < trackCount;
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

    private boolean areFormatsCompatible(
            MediaFormat first,
            MediaFormat second
    ) {

        if (first == null
                || second == null) {

            return false;
        }

        String firstMime =
                first.getString(
                        MediaFormat.KEY_MIME
                );

        String secondMime =
                second.getString(
                        MediaFormat.KEY_MIME
                );

        if (firstMime == null
                || !firstMime.equals(
                secondMime
        )) {

            return false;
        }

        if (!sameIntegerValue(
                first,
                second,
                MediaFormat.KEY_SAMPLE_RATE
        )) {

            return false;
        }

        return sameIntegerValue(
                first,
                second,
                MediaFormat.KEY_CHANNEL_COUNT
        );

    }

    private boolean sameIntegerValue(
            MediaFormat first,
            MediaFormat second,
            String key
    ) {

        if (!first.containsKey(key)
                || !second.containsKey(key)) {

            return true;
        }

        return first.getInteger(key)
                == second.getInteger(key);

    }

}