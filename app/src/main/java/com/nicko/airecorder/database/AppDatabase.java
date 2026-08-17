package com.nicko.airecorder.database;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
        entities = {
                RecordEntity.class
        },
        version = 3,
        exportSchema = true
)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase instance;

    public abstract RecordDao recordDao();

    public static AppDatabase getInstance(Context context) {

        if (instance == null) {

            synchronized (AppDatabase.class) {

                if (instance == null) {

                    instance = Room.databaseBuilder(

                                    context.getApplicationContext(),

                                    AppDatabase.class,

                                    "records_db"

                            )

                            .fallbackToDestructiveMigration()

                            .build();

                }

            }

        }

        return instance;

    }

}