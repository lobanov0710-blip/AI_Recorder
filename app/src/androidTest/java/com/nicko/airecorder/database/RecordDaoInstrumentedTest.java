package com.nicko.airecorder.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class RecordDaoInstrumentedTest {

    private AppDatabase database;

    private RecordDao recordDao;

    /*
     * =========================================================
     * SETUP
     * =========================================================
     */

    @Before
    public void setUp() {

        Context context =
                InstrumentationRegistry
                        .getInstrumentation()
                        .getTargetContext();

        /*
         * In-memory database:
         *
         * - не затрагивает реальную records_db;
         * - создаётся заново перед каждым test;
         * - уничтожается после test.
         */
        database =
                Room.inMemoryDatabaseBuilder(
                                context,
                                AppDatabase.class
                        )
                        .allowMainThreadQueries()
                        .build();

        recordDao =
                database.recordDao();
    }

    /*
     * =========================================================
     * CLEANUP
     * =========================================================
     */

    @After
    public void tearDown() {

        if (database != null) {

            database.close();
        }
    }

    /*
     * =========================================================
     * INSERT + FILE PATH LOOKUP
     * =========================================================
     */

    @Test
    public void insert_returnsPositiveId_andFilePathExists() {

        RecordEntity entity =
                createRecord(
                        "record_1000.m4a",
                        "/test/record_1000.m4a",
                        1000L,
                        5000L
                );

        long rowId =
                recordDao.insert(
                        entity
                );

        assertTrue(
                rowId > 0L
        );

        int count =
                recordDao.countByFilePath(
                        "/test/record_1000.m4a"
                );

        assertEquals(
                1,
                count
        );
    }

    /*
     * =========================================================
     * GET ALL ORDER
     * =========================================================
     */

    @Test
    public void getAllSync_ordersRecordsByCreatedAtDescending() {

        RecordEntity older =
                createRecord(
                        "record_1000.m4a",
                        "/test/record_1000.m4a",
                        1000L,
                        5000L
                );

        RecordEntity newer =
                createRecord(
                        "record_2000.m4a",
                        "/test/record_2000.m4a",
                        2000L,
                        6000L
                );

        recordDao.insert(
                older
        );

        recordDao.insert(
                newer
        );

        List<RecordEntity> records =
                recordDao.getAllSync();

        assertNotNull(
                records
        );

        assertEquals(
                2,
                records.size()
        );

        /*
         * DAO contract:
         *
         * ORDER BY createdAt DESC
         */
        assertEquals(
                "/test/record_2000.m4a",
                records.get(0)
                        .getFilePath()
        );

        assertEquals(
                "/test/record_1000.m4a",
                records.get(1)
                        .getFilePath()
        );
    }

    /*
     * =========================================================
     * RENAME
     * =========================================================
     */

    @Test
    public void rename_updatesOnlyRequestedRecord() {

        RecordEntity first =
                createRecord(
                        "record_1000.m4a",
                        "/test/record_1000.m4a",
                        1000L,
                        5000L
                );

        RecordEntity second =
                createRecord(
                        "record_2000.m4a",
                        "/test/record_2000.m4a",
                        2000L,
                        6000L
                );

        long firstId =
                recordDao.insert(
                        first
                );

        recordDao.insert(
                second
        );

        recordDao.rename(
                firstId,
                "Meeting"
        );

        List<RecordEntity> records =
                recordDao.getAllSync();

        assertEquals(
                2,
                records.size()
        );

        RecordEntity renamed =
                findById(
                        records,
                        firstId
                );

        assertNotNull(
                renamed
        );

        assertEquals(
                "Meeting",
                renamed.getTitle()
        );

        /*
         * Вторая запись не должна измениться.
         */
        RecordEntity untouched =
                findByPath(
                        records,
                        "/test/record_2000.m4a"
                );

        assertNotNull(
                untouched
        );

        assertEquals(
                "record_2000.m4a",
                untouched.getTitle()
        );
    }

    /*
     * =========================================================
     * DELETE BY ID
     * =========================================================
     */

    @Test
    public void deleteById_removesRequestedRecord() {

        RecordEntity entity =
                createRecord(
                        "record_1000.m4a",
                        "/test/record_1000.m4a",
                        1000L,
                        5000L
                );

        long rowId =
                recordDao.insert(
                        entity
                );

        int deletedRows =
                recordDao.deleteById(
                        rowId
                );

        assertEquals(
                1,
                deletedRows
        );

        assertEquals(
                0,
                recordDao.countByFilePath(
                        "/test/record_1000.m4a"
                )
        );

        assertTrue(
                recordDao.getAllSync()
                        .isEmpty()
        );
    }

    /*
     * =========================================================
     * DELETE BY FILE PATH
     * =========================================================
     */

    @Test
    public void deleteByFilePath_removesRequestedRecord() {

        RecordEntity entity =
                createRecord(
                        "record_1000.m4a",
                        "/test/record_1000.m4a",
                        1000L,
                        5000L
                );

        recordDao.insert(
                entity
        );

        int deletedRows =
                recordDao.deleteByFilePath(
                        "/test/record_1000.m4a"
                );

        assertEquals(
                1,
                deletedRows
        );

        assertEquals(
                0,
                recordDao.countByFilePath(
                        "/test/record_1000.m4a"
                )
        );
    }

    /*
     * =========================================================
     * DELETE NON-EXISTING
     * =========================================================
     */

    @Test
    public void deleteById_returnsZero_whenRecordDoesNotExist() {

        int deletedRows =
                recordDao.deleteById(
                        999999L
                );

        assertEquals(
                0,
                deletedRows
        );
    }

    /*
     * =========================================================
     * HELPERS
     * =========================================================
     */

    private RecordEntity createRecord(
            String fileName,
            String filePath,
            long createdAt,
            long duration
    ) {

        return new RecordEntity(

                fileName,

                filePath,

                createdAt,

                fileName,

                duration
        );
    }

    private RecordEntity findById(
            List<RecordEntity> records,
            long id
    ) {

        if (records == null) {

            return null;
        }

        for (RecordEntity record : records) {

            if (record != null
                    && record.getId() == id) {

                return record;
            }
        }

        return null;
    }

    private RecordEntity findByPath(
            List<RecordEntity> records,
            String filePath
    ) {

        if (records == null
                || filePath == null) {

            return null;
        }

        for (RecordEntity record : records) {

            if (record != null
                    && filePath.equals(
                    record.getFilePath()
            )) {

                return record;
            }
        }

        return null;
    }
}