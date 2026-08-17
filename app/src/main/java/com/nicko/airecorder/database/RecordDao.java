package com.nicko.airecorder.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface RecordDao {

    @Insert
    void insert(RecordEntity record);

    @Query(
            "DELETE FROM records " +
                    "WHERE id = :id"
    )
    void deleteById(long id);

    @Query(
            "UPDATE records " +
                    "SET title = :title " +
                    "WHERE id = :id"
    )
    void rename(
            long id,
            String title
    );

    @Query(
            "SELECT * FROM records " +
                    "ORDER BY createdAt DESC"
    )
    LiveData<List<RecordEntity>> getAll();

}