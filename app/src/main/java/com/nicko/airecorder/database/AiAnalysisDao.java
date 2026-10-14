package com.nicko.airecorder.database;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Query;
import androidx.room.Upsert;

@Dao
public interface AiAnalysisDao {

    /** Call from a background executor. The parent recording must exist. */
    @Upsert
    void upsert(AiAnalysisEntity analysis);

    @Query("SELECT * FROM ai_analyses WHERE recordId = :recordId")
    LiveData<AiAnalysisEntity> observeByRecordId(long recordId);

    /** Synchronous lookup for background processing. */
    @Nullable
    @Query("SELECT * FROM ai_analyses WHERE recordId = :recordId")
    AiAnalysisEntity getByRecordId(long recordId);

    @Query("DELETE FROM ai_analyses WHERE recordId = :recordId")
    int deleteByRecordId(long recordId);
}
