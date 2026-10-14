package com.nicko.airecorder.viewmodel;

import static com.nicko.airecorder.viewmodel.AiScreenState.Operation.NONE;
import static com.nicko.airecorder.viewmodel.AiScreenState.WorkState.*;
import static org.junit.Assert.*;

import com.nicko.airecorder.database.AiAnalysisEntity;
import com.nicko.airecorder.database.AiAnalysisStatus;
import com.nicko.airecorder.database.RecordEntity;

import org.junit.Test;

public class AiScreenStateTest {
    private final RecordEntity record = new RecordEntity("record_1.m4a", "/files/record_1.m4a",
            1L, "Original title", 1000L);

    @Test
    public void incompleteSources_doNotExposeStartOrCancel() {
        AiScreenState state = AiScreenState.from(false, record, null,
                AiScreenState.WorkState.NONE, AiScreenState.WorkState.NONE, NONE, null);
        assertEquals(AiScreenState.Status.LOADING, state.getStatus());
        assertFalse(state.canStart());
        assertFalse(state.canCancel());
    }

    @Test
    public void deletedParent_hidesEvenAnAlreadyLoadedResult() {
        AiScreenState state = AiScreenState.from(true, null, saved(AiAnalysisStatus.SUCCEEDED),
                RUNNING, RUNNING, NONE, null);
        assertEquals(AiScreenState.Status.MISSING, state.getStatus());
        assertFalse(state.hasResult());
        assertNull(state.getTranscript());
        assertFalse(state.canStart());
        assertFalse(state.canCancel());
    }

    @Test
    public void activeWorkWithoutAnalysis_isStillCancellable() {
        AiScreenState state = state(null, PENDING, AiScreenState.WorkState.NONE);
        assertEquals(AiScreenState.Status.PENDING, state.getStatus());
        assertTrue(state.canCancel());
        assertFalse(state.canStart());
    }

    @Test
    public void newAttempt_preservesResultAndHidesPreviousError() {
        AiAnalysisEntity old = saved(AiAnalysisStatus.FAILED);
        old.setErrorCode("AI_NOT_CONFIGURED");
        AiScreenState state = state(old, RUNNING, FAILED);
        assertEquals(AiScreenState.Status.RUNNING, state.getStatus());
        assertNull(state.getErrorCode());
        assertEquals("Saved text", state.getTranscript());
        assertTrue(state.hasResult());
    }

    @Test
    public void scheduledRetry_doesNotBecomeAManualFailure() {
        AiAnalysisEntity analysis = saved(AiAnalysisStatus.PENDING);
        analysis.setErrorCode("NETWORK_UNAVAILABLE");
        AiScreenState state = state(analysis, PENDING, PENDING);
        assertEquals(AiScreenState.Status.PENDING, state.getStatus());
        assertEquals("NETWORK_UNAVAILABLE", state.getErrorCode());
        assertFalse(state.canStart());
    }

    @Test
    public void cancelledWork_releasesControlsBeforeRoomCatchesUp() {
        AiScreenState state = state(saved(AiAnalysisStatus.RUNNING),
                AiScreenState.WorkState.NONE, CANCELLED);
        assertEquals(AiScreenState.Status.CANCELLED, state.getStatus());
        assertTrue(state.canStart());
        assertFalse(state.canCancel());
    }

    @Test
    public void failedWork_releasesControlsBeforeRoomCatchesUp() {
        AiScreenState state = state(saved(AiAnalysisStatus.RUNNING),
                AiScreenState.WorkState.NONE, FAILED);
        assertEquals(AiScreenState.Status.FAILED, state.getStatus());
        assertEquals("PROCESSING_FAILED", state.getErrorCode());
        assertTrue(state.canStart());
    }

    @Test
    public void orphanPendingRow_canBeRestartedInsteadOfWaitingForever() {
        AiScreenState state = state(saved(AiAnalysisStatus.PENDING),
                AiScreenState.WorkState.NONE, AiScreenState.WorkState.NONE);
        assertEquals(AiScreenState.Status.FAILED, state.getStatus());
        assertEquals("ANALYSIS_INTERRUPTED", state.getErrorCode());
        assertTrue(state.canStart());
    }

    @Test
    public void successfulWork_waitsForTheRoomResult() {
        AiScreenState state = state(saved(AiAnalysisStatus.RUNNING),
                AiScreenState.WorkState.NONE, SUCCEEDED);
        assertEquals(AiScreenState.Status.LOADING, state.getStatus());
        assertFalse(state.canStart());
    }

    @Test
    public void savedResult_remainsAvailableAfterWorkHistoryIsPruned() {
        AiScreenState state = state(saved(AiAnalysisStatus.SUCCEEDED),
                AiScreenState.WorkState.NONE, AiScreenState.WorkState.NONE);
        assertEquals(AiScreenState.Status.SUCCEEDED, state.getStatus());
        assertEquals("Original title", state.getRecordTitle());
        assertEquals("Suggested title", state.getSuggestedTitle());
        assertTrue(state.canStart());
    }

    @Test
    public void silentRecording_canHaveAnEmptySuccessfulResult() {
        AiAnalysisEntity analysis = new AiAnalysisEntity(9L, AiAnalysisStatus.SUCCEEDED, 1L, 1);
        analysis.setTranscript("");
        analysis.setSummary("");
        analysis.setSuggestedTitle("");
        analysis.setTasksJson("[]");
        AiScreenState state = state(analysis, AiScreenState.WorkState.NONE, SUCCEEDED);
        assertTrue(state.hasResult());
        assertEquals(AiScreenState.Status.SUCCEEDED, state.getStatus());
    }

    @Test
    public void commandInProgress_blocksBothActionsWithoutDiscardingResult() {
        AiScreenState state = AiScreenState.from(true, record, saved(AiAnalysisStatus.SUCCEEDED),
                AiScreenState.WorkState.NONE, SUCCEEDED, AiScreenState.Operation.ENQUEUE, null);
        assertTrue(state.isBusy());
        assertTrue(state.hasResult());
        assertFalse(state.canStart());
        assertFalse(state.canCancel());
    }

    @Test
    public void commandFailure_isSeparateFromTheSavedAnalysis() {
        AiScreenState state = AiScreenState.from(true, record, saved(AiAnalysisStatus.SUCCEEDED),
                AiScreenState.WorkState.NONE, SUCCEEDED, NONE, "QUEUE_FAILED");
        assertEquals(AiScreenState.Status.SUCCEEDED, state.getStatus());
        assertEquals("QUEUE_FAILED", state.getOperationErrorCode());
        assertTrue(state.hasResult());
        assertTrue(state.canStart());
    }

    @Test
    public void snapshot_isUnaffectedByLaterEntityMutations() {
        AiAnalysisEntity analysis = saved(AiAnalysisStatus.SUCCEEDED);
        AiScreenState state = state(analysis, AiScreenState.WorkState.NONE, SUCCEEDED);
        analysis.setTranscript("Changed");
        analysis.setStatus(AiAnalysisStatus.FAILED);
        assertEquals("Saved text", state.getTranscript());
        assertEquals(AiScreenState.Status.SUCCEEDED, state.getStatus());
    }

    private AiScreenState state(AiAnalysisEntity analysis, AiScreenState.WorkState active,
                                AiScreenState.WorkState owner) {
        return AiScreenState.from(true, record, analysis, active, owner, NONE, null);
    }

    private AiAnalysisEntity saved(AiAnalysisStatus status) {
        AiAnalysisEntity analysis = new AiAnalysisEntity(9L, status, 1L, 1);
        analysis.setTranscript("Saved text");
        analysis.setSummary("Saved summary");
        analysis.setSuggestedTitle("Suggested title");
        analysis.setTasksJson("[\"Task\"]");
        return analysis;
    }
}
