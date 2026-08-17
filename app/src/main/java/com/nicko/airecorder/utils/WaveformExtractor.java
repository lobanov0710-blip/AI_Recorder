package com.nicko.airecorder.utils;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class WaveformExtractor {

    private static final int TARGET_POINTS = 180;

    public interface Callback {
        void onWaveformReady(int[] waveform);
    }

    public void extract(File file, Callback callback) {

        WaveformCache cache = WaveformCache.getInstance();

        int[] cached = cache.get(file.getAbsolutePath());

        if (cached != null) {

            if (callback != null) {

                new Handler(Looper.getMainLooper()).post(() ->
                        callback.onWaveformReady(cached)
                );

            }

            return;
        }

        new Thread(() -> {

            int[] result = buildWaveform(file);

            cache.put(file.getAbsolutePath(), result);

            if (callback != null) {

                new Handler(Looper.getMainLooper()).post(() ->
                        callback.onWaveformReady(result)
                );

            }

        }).start();
    }

    private int[] buildWaveform(File file) {

        List<Integer> amplitudes = new ArrayList<>();

        MediaExtractor extractor = new MediaExtractor();

        try {

            extractor.setDataSource(file.getAbsolutePath());

            int audioTrack = -1;

            for (int i = 0; i < extractor.getTrackCount(); i++) {

                MediaFormat format = extractor.getTrackFormat(i);

                String mime = format.getString(MediaFormat.KEY_MIME);

                if (mime != null && mime.startsWith("audio/")) {

                    audioTrack = i;
                    break;

                }

            }

            if (audioTrack == -1) {
                return new int[0];
            }

            extractor.selectTrack(audioTrack);

            ByteBuffer buffer = ByteBuffer.allocate(8192);

            while (true) {

                int size = extractor.readSampleData(buffer, 0);

                if (size < 0) {
                    break;
                }

                int amplitude = 0;

                for (int i = 0; i < size; i++) {

                    amplitude += Math.abs(buffer.get(i));

                }

                amplitudes.add(
                        amplitude / Math.max(size, 1)
                );

                buffer.clear();

                extractor.advance();

            }

        } catch (IOException e) {

            e.printStackTrace();

        } finally {

            extractor.release();

        }

        if (amplitudes.isEmpty()) {
            return new int[0];
        }

        int[] waveform = new int[amplitudes.size()];

        for (int i = 0; i < amplitudes.size(); i++) {

            waveform[i] = amplitudes.get(i);

        }

        waveform = normalize(waveform);

        waveform = compress(waveform, TARGET_POINTS);

        return waveform;
    }

    private int[] normalize(int[] data) {

        int max = 1;

        for (int value : data) {

            if (value > max) {
                max = value;
            }

        }

        for (int i = 0; i < data.length; i++) {

            data[i] = data[i] * 100 / max;

        }

        return data;

    }

    private int[] compress(int[] source, int targetSize) {

        if (source.length <= targetSize) {
            return source;
        }

        int[] result = new int[targetSize];

        float step = (float) source.length / targetSize;

        for (int i = 0; i < targetSize; i++) {

            int start = (int) (i * step);

            int end = (int) ((i + 1) * step);

            int max = 0;

            for (int j = start; j < end && j < source.length; j++) {

                if (source[j] > max) {

                    max = source[j];

                }

            }

            result[i] = max;

        }

        return result;

    }

}