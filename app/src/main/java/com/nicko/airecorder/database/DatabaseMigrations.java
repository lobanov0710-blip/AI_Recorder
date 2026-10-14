package com.nicko.airecorder.database;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

public final class DatabaseMigrations {

    private DatabaseMigrations() {
    }

    /** Adds AI storage without modifying existing recording rows or files. */
    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ai_analyses` ("
                            + "`recordId` INTEGER NOT NULL, "
                            + "`status` TEXT NOT NULL, "
                            + "`transcript` TEXT, "
                            + "`summary` TEXT, "
                            + "`suggestedTitle` TEXT, "
                            + "`tasksJson` TEXT, "
                            + "`errorCode` TEXT, "
                            + "`updatedAt` INTEGER NOT NULL, "
                            + "`processingVersion` INTEGER NOT NULL, "
                            + "PRIMARY KEY(`recordId`), "
                            + "FOREIGN KEY(`recordId`) REFERENCES `records`(`id`) "
                            + "ON UPDATE NO ACTION ON DELETE CASCADE)"
            );
        }
    };
}
