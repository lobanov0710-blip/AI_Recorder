package com.nicko.airecorder.viewmodel;

import static org.junit.Assert.*;

import android.app.Application;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.WorkInfo;

import com.nicko.airecorder.database.AiAnalysisEntity;
import com.nicko.airecorder.database.AiAnalysisStatus;
import com.nicko.airecorder.database.RecordEntity;
import com.nicko.airecorder.repository.AiDataSource;
import com.nicko.airecorder.repository.AiRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class AiViewModelInstrumentedTest {
    private FakeSource source;
    private AiViewModel model;
    private ViewModelStore store;
    private final Observer<AiScreenState> observer = value -> { };

    @Before
    public void setUp() {
        onMain(() -> {
            Application app = (Application) InstrumentationRegistry.getInstrumentation()
                    .getTargetContext().getApplicationContext();
            source = new FakeSource();
            model = new AiViewModel(app, source);
            store = new ViewModelStore();
            store.put("analysis", model);
            model.getState().observeForever(observer);
        });
    }

    @After
    public void tearDown() {
        onMain(() -> {
            model.getState().removeObserver(observer);
            store.clear();
        });
    }

    @Test
    public void waitsForAllSourcesBeforeEnablingStart() {
        onMain(() -> {
            model.initialize(9L);
            source.record.setValue(record());
            source.analysis.setValue(null);
            assertEquals(AiScreenState.Status.LOADING, state().getStatus());
            model.startAnalysis();
            assertEquals(0, source.enqueues);
            source.work.setValue(Collections.emptyList());
            assertEquals(AiScreenState.Status.EMPTY, state().getStatus());
            assertTrue(state().canStart());
        });
    }

    @Test
    public void repeatedInitializationAndObservation_neverEnqueueWork() {
        onMain(() -> {
            model.initialize(9L);
            source.ready();
            model.initialize(9L);
            model.getState().removeObserver(observer);
            model.getState().observeForever(observer);
            assertEquals(1, source.recordSubscriptions);
            assertEquals(0, source.enqueues);
            assertEquals(AiScreenState.Status.EMPTY, state().getStatus());
        });
    }

    @Test
    public void repeatedStartAndReattachedObserver_keepOneInFlightCommand() {
        onMain(() -> {
            model.initialize(9L);
            source.ready();
            model.startAnalysis();
            model.startAnalysis();
            model.getState().removeObserver(observer);
            model.getState().observeForever(observer);
            model.startAnalysis();
            assertEquals(1, source.enqueues);
            assertEquals(AiScreenState.Operation.ENQUEUE, state().getOperation());

            UUID id = UUID.randomUUID();
            source.analysis.setValue(analysis(AiAnalysisStatus.PENDING, id));
            source.work.setValue(Collections.singletonList(work(id, WorkInfo.State.ENQUEUED)));
            source.callback.onComplete(true, null);
            model.startAnalysis();
            assertEquals(1, source.enqueues);
            assertTrue(state().canCancel());
        });
    }

    @Test
    public void cancellation_releasesControlsWhenWorkStopsBeforeRoomUpdates() {
        onMain(() -> {
            model.initialize(9L);
            source.ready();
            UUID id = UUID.randomUUID();
            source.analysis.setValue(analysis(AiAnalysisStatus.RUNNING, id));
            source.work.setValue(Collections.singletonList(work(id, WorkInfo.State.RUNNING)));
            model.cancelAnalysis();
            model.cancelAnalysis();
            assertEquals(1, source.cancellations);
            source.work.setValue(Collections.singletonList(work(id, WorkInfo.State.CANCELLED)));
            assertFalse(state().canStart());
            source.callback.onComplete(true, null);
            assertEquals(AiScreenState.Status.CANCELLED, state().getStatus());
            assertTrue(state().canStart());
        });
    }

    @Test
    public void queueError_releasesTheButtonAndReportsTheCommandFailure() {
        onMain(() -> {
            model.initialize(9L);
            source.ready();
            model.startAnalysis();
            source.callback.onComplete(false, "INVALID_RECORDING");
            assertEquals("INVALID_RECORDING", state().getOperationErrorCode());
            assertTrue(state().canStart());
        });
    }

    @Test
    public void oldFailedOwner_doesNotMaskANewerActiveRequest() {
        onMain(() -> {
            model.initialize(9L);
            source.ready();
            UUID oldId = UUID.randomUUID();
            AiAnalysisEntity old = analysis(AiAnalysisStatus.FAILED, oldId);
            old.setTranscript("Saved transcript");
            old.setErrorCode("AI_NOT_CONFIGURED");
            source.analysis.setValue(old);
            source.work.setValue(Arrays.asList(work(oldId, WorkInfo.State.FAILED),
                    work(UUID.randomUUID(), WorkInfo.State.RUNNING)));
            assertEquals(AiScreenState.Status.RUNNING, state().getStatus());
            assertNull(state().getErrorCode());
            assertEquals("Saved transcript", state().getTranscript());
            assertFalse(state().canStart());
        });
    }

    @Test
    public void deletedRecording_hidesResultAndDisablesCommands() {
        onMain(() -> {
            model.initialize(9L);
            source.ready();
            source.analysis.setValue(analysis(AiAnalysisStatus.SUCCEEDED, UUID.randomUUID()));
            assertTrue(state().hasResult());
            source.record.setValue(null);
            model.startAnalysis();
            model.cancelAnalysis();
            assertEquals(AiScreenState.Status.MISSING, state().getStatus());
            assertFalse(state().hasResult());
            assertEquals(0, source.enqueues);
            assertEquals(0, source.cancellations);
        });
    }

    @Test
    public void invalidIntentId_neverQueriesOrQueuesARecording() {
        onMain(() -> {
            model.initialize(-1L);
            model.startAnalysis();
            assertEquals(AiScreenState.Status.MISSING, state().getStatus());
            assertEquals(0, source.recordSubscriptions);
            assertEquals(0, source.enqueues);
        });
    }

    private AiScreenState state() {
        AiScreenState value = model.getState().getValue();
        assertNotNull(value);
        return value;
    }

    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private static RecordEntity record() {
        RecordEntity record = new RecordEntity("record_1.m4a", "/files/record_1.m4a", 1L,
                "Original title", 1000L);
        record.setId(9L);
        return record;
    }

    private static AiAnalysisEntity analysis(AiAnalysisStatus status, UUID owner) {
        AiAnalysisEntity analysis = new AiAnalysisEntity(9L, status, 1L, 1);
        analysis.setRequestId(owner.toString());
        return analysis;
    }

    private static WorkInfo work(UUID id, WorkInfo.State state) {
        return new WorkInfo(id, state, Collections.emptySet());
    }

    private static final class FakeSource implements AiDataSource {
        final MutableLiveData<RecordEntity> record = new MutableLiveData<>();
        final MutableLiveData<AiAnalysisEntity> analysis = new MutableLiveData<>();
        final MutableLiveData<List<WorkInfo>> work = new MutableLiveData<>();
        int recordSubscriptions;
        int enqueues;
        int cancellations;
        AiRepository.QueueCallback callback;

        void ready() {
            record.setValue(AiViewModelInstrumentedTest.record());
            analysis.setValue(null);
            work.setValue(Collections.emptyList());
        }

        @Override public MutableLiveData<RecordEntity> observeRecord(long id) {
            recordSubscriptions++;
            return record;
        }
        @Override public MutableLiveData<AiAnalysisEntity> observeAnalysis(long id) { return analysis; }
        @Override public MutableLiveData<List<WorkInfo>> observeWork(long id) { return work; }
        @Override public void enqueue(long id, AiRepository.QueueCallback callback) {
            enqueues++;
            this.callback = callback;
        }
        @Override public void cancel(long id, AiRepository.QueueCallback callback) {
            cancellations++;
            this.callback = callback;
        }
    }
}
