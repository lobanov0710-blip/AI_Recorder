package com.nicko.airecorder.repository;

import androidx.lifecycle.LiveData;
import androidx.work.WorkInfo;

import com.nicko.airecorder.database.AiAnalysisEntity;
import com.nicko.airecorder.database.RecordEntity;

import java.util.List;

/** Observable data and explicit queue commands; command callbacks run on the main thread. */
public interface AiDataSource {
    LiveData<RecordEntity> observeRecord(long recordId);
    LiveData<AiAnalysisEntity> observeAnalysis(long recordId);
    LiveData<List<WorkInfo>> observeWork(long recordId);
    void enqueue(long recordId, AiRepository.QueueCallback callback);
    void cancel(long recordId, AiRepository.QueueCallback callback);
}
