package com.nicko.airecorder.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;

import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Collections;

@RunWith(AndroidJUnit4.class)
public class AppDatabaseMigrationTest {

    private static final String TEST_DATABASE = "ai_migration_test";

    @Rule
    public MigrationTestHelper helper = new MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase.class,
            Collections.emptyList(),
            new FrameworkSQLiteOpenHelperFactory()
    );

    @Test
    public void migration3To4_preservesRecordingDataAndNullableFields()
            throws IOException {
        SupportSQLiteDatabase database = helper.createDatabase(TEST_DATABASE, 3);
        database.execSQL(
                "INSERT INTO records "
                        + "(id, fileName, filePath, createdAt, duration, title) "
                        + "VALUES (17, 'record_1000.m4a', '/test/record_1000.m4a', "
                        + "1000, 5000, 'Original title')"
        );
        database.execSQL(
                "INSERT INTO records "
                        + "(id, fileName, filePath, createdAt, duration, title) "
                        + "VALUES (18, NULL, NULL, 2000, 6000, NULL)"
        );
        database.close();

        database = helper.runMigrationsAndValidate(
                TEST_DATABASE, 4, true, DatabaseMigrations.MIGRATION_3_4
        );

        try (Cursor cursor = database.query("SELECT * FROM records ORDER BY id")) {
            assertEquals(2, cursor.getCount());
            assertTrue(cursor.moveToFirst());
            assertEquals(17L, cursor.getLong(cursor.getColumnIndexOrThrow("id")));
            assertEquals("record_1000.m4a", cursor.getString(
                    cursor.getColumnIndexOrThrow("fileName")
            ));
            assertEquals("/test/record_1000.m4a", cursor.getString(
                    cursor.getColumnIndexOrThrow("filePath")
            ));
            assertEquals("Original title", cursor.getString(
                    cursor.getColumnIndexOrThrow("title")
            ));
            assertEquals(1000L, cursor.getLong(cursor.getColumnIndexOrThrow("createdAt")));
            assertEquals(5000L, cursor.getLong(cursor.getColumnIndexOrThrow("duration")));
            assertTrue(cursor.moveToNext());
            assertEquals(18L, cursor.getLong(cursor.getColumnIndexOrThrow("id")));
            assertEquals(2000L, cursor.getLong(cursor.getColumnIndexOrThrow("createdAt")));
            assertEquals(6000L, cursor.getLong(cursor.getColumnIndexOrThrow("duration")));
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("fileName")));
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("filePath")));
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("title")));
        }
        try (Cursor cursor = database.query("SELECT COUNT(*) FROM ai_analyses")) {
            assertTrue(cursor.moveToFirst());
            assertEquals(0, cursor.getInt(0));
        }
    }

    @Test
    public void migratedDatabase_enforcesCascadeOnRecordDeletion()
            throws IOException {
        SupportSQLiteDatabase database = helper.createDatabase(TEST_DATABASE, 3);
        database.execSQL(
                "INSERT INTO records (id, createdAt, duration) VALUES (17, 1000, 5000)"
        );
        database.close();

        database = helper.runMigrationsAndValidate(
                TEST_DATABASE, 4, true, DatabaseMigrations.MIGRATION_3_4
        );
        // Foreign keys are connection-local. Room enables them when opening
        // the application database; this helper connection needs the same setting.
        database.execSQL("PRAGMA foreign_keys = ON");
        database.execSQL(
                "INSERT INTO ai_analyses "
                        + "(recordId, status, updatedAt, processingVersion) "
                        + "VALUES (17, 'PENDING', 1000, 1)"
        );
        database.execSQL("DELETE FROM records WHERE id = 17");

        try (Cursor cursor = database.query("SELECT COUNT(*) FROM ai_analyses")) {
            assertTrue(cursor.moveToFirst());
            assertEquals(0, cursor.getInt(0));
        }
    }

    @Test
    public void migration4To5_preservesExistingAnalysisAndAddsNullableRequestId()
            throws IOException {
        SupportSQLiteDatabase database = helper.createDatabase(TEST_DATABASE, 4);
        database.execSQL("INSERT INTO records (id, createdAt, duration) VALUES (17, 1000, 5000)");
        database.execSQL("INSERT INTO ai_analyses "
                + "(recordId, status, transcript, summary, suggestedTitle, tasksJson, "
                + "errorCode, updatedAt, processingVersion) VALUES "
                + "(17, 'SUCCEEDED', 'Transcript', 'Summary', 'Title', '[\"Task\"]', NULL, 2000, 1)");
        database.close();
        database = helper.runMigrationsAndValidate(
                TEST_DATABASE, 5, true, DatabaseMigrations.MIGRATION_4_5
        );
        try (Cursor cursor = database.query("SELECT * FROM ai_analyses WHERE recordId = 17")) {
            assertTrue(cursor.moveToFirst());
            assertEquals("SUCCEEDED", cursor.getString(cursor.getColumnIndexOrThrow("status")));
            assertEquals("Transcript", cursor.getString(cursor.getColumnIndexOrThrow("transcript")));
            assertEquals("Summary", cursor.getString(cursor.getColumnIndexOrThrow("summary")));
            assertEquals("Title", cursor.getString(cursor.getColumnIndexOrThrow("suggestedTitle")));
            assertEquals("[\"Task\"]", cursor.getString(cursor.getColumnIndexOrThrow("tasksJson")));
            assertEquals(2000L, cursor.getLong(cursor.getColumnIndexOrThrow("updatedAt")));
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("processingVersion")));
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("errorCode")));
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("requestId")));
        }
    }

    @Test
    public void migration3To5_supportsTheCompleteUpgradePath() throws IOException {
        SupportSQLiteDatabase database = helper.createDatabase(TEST_DATABASE, 3);
        database.execSQL("INSERT INTO records (id, createdAt, duration) VALUES (17, 1000, 5000)");
        database.close();
        database = helper.runMigrationsAndValidate(TEST_DATABASE, 5, true,
                DatabaseMigrations.MIGRATION_3_4, DatabaseMigrations.MIGRATION_4_5);
        try (Cursor cursor = database.query("SELECT id, duration FROM records")) {
            assertEquals(1, cursor.getCount());
            assertTrue(cursor.moveToFirst());
            assertEquals(17L, cursor.getLong(0));
            assertEquals(5000L, cursor.getLong(1));
        }
    }
}
