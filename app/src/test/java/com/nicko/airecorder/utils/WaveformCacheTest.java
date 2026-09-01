package com.nicko.airecorder.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WaveformCacheTest {

    private WaveformCache cache;

    /*
     * =========================================================
     * SETUP
     * =========================================================
     */

    @Before
    public void setUp() {

        cache =
                WaveformCache.getInstance();

        /*
         * Singleton существует между тестами,
         * поэтому каждый test начинаем
         * с чистого состояния.
         */
        cache.clear();
    }

    /*
     * =========================================================
     * CLEANUP
     * =========================================================
     */

    @After
    public void tearDown() {

        cache.clear();
    }

    /*
     * =========================================================
     * SINGLETON
     * =========================================================
     */

    @Test
    public void getInstance_returnsSameInstance() {

        WaveformCache first =
                WaveformCache.getInstance();

        WaveformCache second =
                WaveformCache.getInstance();

        assertSame(
                first,
                second
        );
    }

    /*
     * =========================================================
     * PUT / GET
     * =========================================================
     */

    @Test
    public void putAndGet_returnsStoredWaveform() {

        int[] waveform = {
                10,
                20,
                30,
                40
        };

        cache.put(
                "/records/record_1.m4a",
                waveform
        );

        int[] result =
                cache.get(
                        "/records/record_1.m4a"
                );

        assertArrayEquals(
                waveform,
                result
        );
    }

    /*
     * =========================================================
     * REPLACE
     * =========================================================
     */

    @Test
    public void putWithSamePath_replacesPreviousWaveform() {

        String path =
                "/records/record_1.m4a";

        cache.put(
                path,
                new int[]{
                        1,
                        2,
                        3
                }
        );

        int[] replacement = {
                7,
                8,
                9
        };

        cache.put(
                path,
                replacement
        );

        assertArrayEquals(
                replacement,
                cache.get(
                        path
                )
        );
    }

    /*
     * =========================================================
     * REMOVE
     * =========================================================
     */

    @Test
    public void remove_removesRequestedEntry() {

        String firstPath =
                "/records/record_1.m4a";

        String secondPath =
                "/records/record_2.m4a";

        cache.put(
                firstPath,
                new int[]{
                        10
                }
        );

        cache.put(
                secondPath,
                new int[]{
                        20
                }
        );

        cache.remove(
                firstPath
        );

        assertNull(
                cache.get(
                        firstPath
                )
        );

        assertArrayEquals(
                new int[]{
                        20
                },
                cache.get(
                        secondPath
                )
        );
    }

    /*
     * =========================================================
     * CLEAR
     * =========================================================
     */

    @Test
    public void clear_removesAllEntries() {

        cache.put(
                "/records/record_1.m4a",
                new int[]{
                        1
                }
        );

        cache.put(
                "/records/record_2.m4a",
                new int[]{
                        2
                }
        );

        cache.clear();

        assertNull(
                cache.get(
                        "/records/record_1.m4a"
                )
        );

        assertNull(
                cache.get(
                        "/records/record_2.m4a"
                )
        );
    }

    /*
     * =========================================================
     * LRU CAPACITY
     * =========================================================
     */

    @Test
    public void cacheEvictsLeastRecentlyUsedEntry_whenCapacityExceeded() {

        /*
         * MAX_CACHE_SIZE = 30.
         *
         * Заполняем cache полностью:
         *
         * record_0 ... record_29
         */
        for (int i = 0;
             i < 30;
             i++) {

            cache.put(
                    pathFor(
                            i
                    ),
                    new int[]{
                            i
                    }
            );
        }

        /*
         * record_0 становится recently used.
         *
         * Благодаря accessOrder=true
         * самым старым теперь должен стать record_1.
         */
        assertArrayEquals(
                new int[]{
                        0
                },
                cache.get(
                        pathFor(
                                0
                        )
                )
        );

        /*
         * Добавляем 31-й элемент.
         *
         * LinkedHashMap должен удалить
         * least recently used = record_1.
         */
        cache.put(
                pathFor(
                        30
                ),
                new int[]{
                        30
                }
        );

        /*
         * record_1 должен быть вытеснен.
         */
        assertNull(
                cache.get(
                        pathFor(
                                1
                        )
                )
        );

        /*
         * record_0 мы недавно читали,
         * поэтому он должен остаться.
         */
        assertArrayEquals(
                new int[]{
                        0
                },
                cache.get(
                        pathFor(
                                0
                        )
                )
        );

        /*
         * Новый элемент также присутствует.
         */
        assertArrayEquals(
                new int[]{
                        30
                },
                cache.get(
                        pathFor(
                                30
                        )
                )
        );
    }

    /*
     * =========================================================
     * HELPERS
     * =========================================================
     */

    private String pathFor(
            int index
    ) {

        return "/records/record_"
                + index
                + ".m4a";
    }
}