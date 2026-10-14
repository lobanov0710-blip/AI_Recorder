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

    /** Guarded updates never recreate a deleted row or overwrite another request. */
    @Query("UPDATE ai_analyses SET status = 'SUCCEEDED', transcript = :transcript, "
            + "summary = :summary, suggestedTitle = :suggestedTitle, tasksJson = :tasksJson, "
            + "errorCode = NULL, updatedAt = :updatedAt, processingVersion = :processingVersion "
            + "WHERE recordId = :recordId AND requestId = :requestId AND status = 'RUNNING'")
    int complete(long recordId, String requestId, String transcript, String summary,
                 String suggestedTitle, String tasksJson, long updatedAt, int processingVersion);

    @Query("UPDATE ai_analyses SET status = :status, errorCode = :errorCode, "
            + "updatedAt = :updatedAt WHERE recordId = :recordId AND requestId = :requestId "
            + "AND status IN ('PENDING', 'RUNNING')")
    int setActiveState(long recordId, String requestId, AiAnalysisStatus status,
                       @Nullable String errorCode, long updatedAt);

    @Query("UPDATE ai_analyses SET status = 'CANCELLED', errorCode = 'CANCELLED', "
            + "updatedAt = :updatedAt WHERE recordId = :recordId "
            + "AND status IN ('PENDING', 'RUNNING')")
    int cancelActive(long recordId, long updatedAt);
}
