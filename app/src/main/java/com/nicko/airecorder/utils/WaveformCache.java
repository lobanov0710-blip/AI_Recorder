package com.nicko.airecorder.utils;

import java.util.LinkedHashMap;
import java.util.Map;

public class WaveformCache {

    private static final int MAX_CACHE_SIZE = 30;

    private static WaveformCache instance;

    private final Map<String, int[]> cache =
            new LinkedHashMap<String, int[]>(16, 0.75f, true) {

                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, int[]> eldest
                ) {
                    return size() > MAX_CACHE_SIZE;
                }
            };

    private WaveformCache() {
    }

    public static synchronized WaveformCache getInstance() {

        if (instance == null) {
            instance = new WaveformCache();
        }

        return instance;
    }

    public synchronized int[] get(String path) {

        return cache.get(path);

    }

    public synchronized void put(String path, int[] waveform) {

        cache.put(path, waveform);

    }

    public synchronized void remove(String path) {

        cache.remove(path);

    }

    public synchronized void clear() {

        cache.clear();

    }

}