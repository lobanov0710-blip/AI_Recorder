package com.nicko.airecorder.repository;

import android.content.Context;

import androidx.lifecycle.LiveData;

import com.nicko.airecorder.database.AppDatabase;
import com.nicko.airecorder.database.RecordDao;
import com.nicko.airecorder.database.RecordEntity;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RecordRepository {

    private final RecordDao recordDao;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    public RecordRepository(Context context) {

        AppDatabase database = AppDatabase.getInstance(
                context.getApplicationContext()
        );

        recordDao = database.recordDao();

    }

    public LiveData<List<RecordEntity>> getAll() {

        return recordDao.getAll();

    }

    public void insert(RecordEntity entity) {

        executor.execute(() ->
                recordDao.insert(entity)
        );

    }

    public void rename(long id, String title) {

        executor.execute(() ->
                recordDao.rename(id, title)
        );

    }

    public void delete(long id) {

        executor.execute(() ->
                recordDao.deleteById(id)
        );

    }

    public void shutdown() {

        if (!executor.isShutdown()) {

            executor.shutdown();

        }

    }

}