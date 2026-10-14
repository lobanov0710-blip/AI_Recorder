package com.nicko.airecorder.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.database.sqlite.SQLiteConstraintException;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AiAnalysisDaoInstrumentedTest {

    private AppDatabase database;
    private RecordDao recordDao;
    private AiAnalysisDao analysisDao;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        recordDao = database.recordDao();
        analysisDao = database.aiAnalysisDao();
    }

    @After
    public void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    public void upsert_updatesAnalysisWithoutRenamingParentRecord() {
        long recordId = insertRecord("record_1000.m4a");
        analysisDao.upsert(new AiAnalysisEntity(
                recordId, AiAnalysisStatus.PENDING, 1000L, 1
        ));

        AiAnalysisEntity pending = analysisDao.getByRecordId(recordId);
        assertNotNull(pending);
        assertEquals(AiAnalysisStatus.PENDING, pending.getStatus());
        assertNull(pending.getTranscript());
        assertNull(pending.getTasksJson());

        AiAnalysisEntity completed = new AiAnalysisEntity(
                recordId, AiAnalysisStatus.SUCCEEDED, 2000L, 1
        );
        completed.setTranscript("Call back tomorrow.");
        completed.setSummary("A follow-up call.");
        completed.setSuggestedTitle("Follow-up");
        completed.setTasksJson("[\"Call back tomorrow\"]");
        analysisDao.upsert(completed);

        AiAnalysisEntity stored = analysisDao.getByRecordId(recordId);
        assertNotNull(stored);
        assertEquals(recordId, stored.getRecordId());
        assertEquals(AiAnalysisStatus.SUCCEEDED, stored.getStatus());
        assertEquals(completed.getTranscript(), stored.getTranscript());
        assertEquals(completed.getSummary(), stored.getSummary());
        assertEquals(completed.getSuggestedTitle(), stored.getSuggestedTitle());
        assertEquals(completed.getTasksJson(), stored.getTasksJson());
        assertEquals(2000L, stored.getUpdatedAt());
        assertEquals(1, stored.getProcessingVersion());
        assertNull(stored.getErrorCode());
        assertEquals(1, recordDao.getAllSync().size());
        assertEquals("Original title", recordDao.getAllSync().get(0).getTitle());
    }

    @Test
    public void analysisWithoutParentRecord_isRejected() {
        assertThrows(
                SQLiteConstraintException.class,
                () -> analysisDao.upsert(new AiAnalysisEntity(
                        999L, AiAnalysisStatus.PENDING, 1000L, 1
                ))
        );
        assertNull(analysisDao.getByRecordId(999L));
    }

    @Test
    public void failedAnalysis_preservesErrorAndProcessingVersion() {
        long recordId = insertRecord("record_1000.m4a");
        AiAnalysisEntity failed = new AiAnalysisEntity(
                recordId, AiAnalysisStatus.FAILED, 3000L, 2
        );
        failed.setErrorCode("TRANSCRIPTION_FAILED");
        analysisDao.upsert(failed);

        AiAnalysisEntity stored = analysisDao.getByRecordId(recordId);
        assertNotNull(stored);
        assertEquals(AiAnalysisStatus.FAILED, stored.getStatus());
        assertEquals("TRANSCRIPTION_FAILED", stored.getErrorCode());
        assertEquals(3000L, stored.getUpdatedAt());
        assertEquals(2, stored.getProcessingVersion());
        assertNull(stored.getTranscript());
    }

    @Test
    public void deleteRecordById_cascadesOnlyToItsAnalysis() {
        long firstId = insertRecord("record_1000.m4a");
        long secondId = insertRecord("record_2000.m4a");
        insertPendingAnalysis(firstId);
        insertPendingAnalysis(secondId);

        assertEquals(1, recordDao.deleteById(firstId));

        assertNull(analysisDao.getByRecordId(firstId));
        assertNotNull(analysisDao.getByRecordId(secondId));
        assertEquals(1, recordDao.getAllSync().size());
    }

    @Test
    public void deleteRecordByFilePath_cascadesToItsAnalysis() {
        long recordId = insertRecord("record_1000.m4a");
        insertPendingAnalysis(recordId);

        assertEquals(1, recordDao.deleteByFilePath("/test/record_1000.m4a"));

        assertNull(analysisDao.getByRecordId(recordId));
    }

    @Test
    public void deleteAnalysis_keepsParentRecord() {
        long recordId = insertRecord("record_1000.m4a");
        insertPendingAnalysis(recordId);

        assertEquals(1, analysisDao.deleteByRecordId(recordId));

        assertNull(analysisDao.getByRecordId(recordId));
        assertEquals(1, recordDao.getAllSync().size());
        assertEquals(0, analysisDao.deleteByRecordId(recordId));
    }

    private long insertRecord(String fileName) {
        return recordDao.insert(new RecordEntity(
                fileName, "/test/" + fileName, 1000L, "Original title", 5000L
        ));
    }

    private void insertPendingAnalysis(long recordId) {
        analysisDao.upsert(new AiAnalysisEntity(
                recordId, AiAnalysisStatus.PENDING, 1000L, 1
        ));
    }
}
