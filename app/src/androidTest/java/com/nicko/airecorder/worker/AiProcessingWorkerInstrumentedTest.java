package com.nicko.airecorder.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.Configuration;
import androidx.work.Data;
import androidx.work.ListenableWorker;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.WorkerFactory;
import androidx.work.WorkerParameters;
import androidx.work.testing.TestListenableWorkerBuilder;
import androidx.work.testing.WorkManagerTestInitHelper;

import com.nicko.airecorder.ai.AiProcessingException;
import com.nicko.airecorder.ai.AiProcessor;
import com.nicko.airecorder.ai.AiResult;
import com.nicko.airecorder.database.AiAnalysisEntity;
import com.nicko.airecorder.database.AiAnalysisStatus;
import com.nicko.airecorder.database.AppDatabase;
import com.nicko.airecorder.database.RecordEntity;
import com.nicko.airecorder.repository.AiRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class AiProcessingWorkerInstrumentedTest {
    private Context context;
    private AppDatabase database;
    private WorkManager workManager;
    private AiRepository repository;
    private File directory;
    private ExecutorService workerExecutor;
    private ExecutorService taskExecutor;
    private volatile AiProcessor processor;
    private final CountDownLatch release = new CountDownLatch(1);

    private final WorkerFactory factory = new WorkerFactory() {
        @Override
        public ListenableWorker createWorker(Context context, String workerClassName,
                                             WorkerParameters parameters) {
            if (!AiProcessingWorker.class.getName().equals(workerClassName)) {
                return null;
            }
            return new AiProcessingWorker(context, parameters, repository, processor);
        }
    };

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        directory = new File(context.getCacheDir(), "ai_queue_test_" + UUID.randomUUID());
        assertTrue(directory.mkdirs());
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase.class)
                .allowMainThreadQueries().build();
        workerExecutor = Executors.newFixedThreadPool(2);
        taskExecutor = Executors.newSingleThreadExecutor();
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
                new Configuration.Builder().setExecutor(workerExecutor)
                        .setTaskExecutor(taskExecutor).setWorkerFactory(factory).build(),
                WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS);
        workManager = WorkManager.getInstance(context);
        repository = new AiRepository(database, workManager, directory);
        // Tests must not depend on the configured debug server or send fixture audio.
        processor = (id, file) -> { throw new AiProcessingException("AI_NOT_CONFIGURED", false); };
    }

    @After
    public void tearDown() throws Exception {
        release.countDown();
        if (workManager != null) {
            workManager.cancelAllWork().getResult().get(10, TimeUnit.SECONDS);
        }
        if (workerExecutor != null) {
            workerExecutor.shutdown();
            assertTrue(workerExecutor.awaitTermination(10, TimeUnit.SECONDS));
        }
        if (repository != null) {
            repository.shutdownForTests();
        }
        WorkManagerTestInitHelper.closeWorkDatabase();
        if (taskExecutor != null) {
            taskExecutor.shutdown();
            assertTrue(taskExecutor.awaitTermination(10, TimeUnit.SECONDS));
        }
        if (database != null) {
            database.close();
        }
        if (directory != null) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    assertTrue(file.delete());
                }
            }
            assertTrue(directory.delete());
        }
    }

    @Test
    public void stoppedWorker_cancelsItsProcessor() throws Exception {
        long recordId = insertRecording();
        AtomicInteger cancellations = new AtomicInteger();
        processor = new AiProcessor() {
            @Override
            public AiResult process(long id, File file) {
                return result(id);
            }

            @Override
            public void cancel() {
                cancellations.incrementAndGet();
            }
        };
        worker(recordId, 0).onStopped();
        assertEquals(1, cancellations.get());
    }

    @Test
    public void successfulProcessing_storesAnalysisWithoutRenamingRecording() throws Exception {
        long recordId = insertRecording();
        processor = (id, file) -> result(id);
        AiProcessingWorker worker = worker(recordId, 0);

        assertEquals(ListenableWorker.Result.success(), worker.doWork());

        AiAnalysisEntity analysis = database.aiAnalysisDao().getByRecordId(recordId);
        assertNotNull(analysis);
        assertEquals(AiAnalysisStatus.SUCCEEDED, analysis.getStatus());
        assertEquals(worker.getId().toString(), analysis.getRequestId());
        assertEquals("Transcript", analysis.getTranscript());
        assertEquals("[\"Task\"]", analysis.getTasksJson());
        assertEquals("Original title", database.recordDao().getById(recordId).getTitle());
    }

    @Test
    public void missingProvider_isAPermanentExplicitFailure() throws Exception {
        long recordId = insertRecording();
        assertEquals(failure("AI_NOT_CONFIGURED"), worker(recordId, 0).doWork());
        assertEquals("AI_NOT_CONFIGURED",
                database.aiAnalysisDao().getByRecordId(recordId).getErrorCode());
    }

    @Test
    public void transientError_keepsTheRequestPendingForRetry() throws Exception {
        long recordId = insertRecording();
        processor = (id, file) -> { throw new AiProcessingException("NETWORK_UNAVAILABLE", true); };
        assertEquals(ListenableWorker.Result.retry(), worker(recordId, 0).doWork());
        assertEquals(AiAnalysisStatus.PENDING,
                database.aiAnalysisDao().getByRecordId(recordId).getStatus());
    }

    @Test
    public void thirdTransientFailure_stopsRetryingAndPreservesTheCause() throws Exception {
        for (String code : new String[]{"NETWORK_UNAVAILABLE", "AI_CONNECTION_FAILED",
                "SERVER_BUSY", "AI_TIMEOUT", "AI_SERVER_ERROR"}) {
            long recordId = insertRecording();
            processor = (id, file) -> { throw new AiProcessingException(code, true); };
            assertEquals(failure(code), worker(recordId, 2).doWork());
            AiAnalysisEntity stored = database.aiAnalysisDao().getByRecordId(recordId);
            assertEquals(AiAnalysisStatus.FAILED, stored.getStatus());
            assertEquals(code, stored.getErrorCode());
        }
    }

    @Test
    public void failedRefresh_preservesThePreviousResult() throws Exception {
        long recordId = insertRecording();
        AiAnalysisEntity previous = new AiAnalysisEntity(recordId, AiAnalysisStatus.SUCCEEDED,
                1000L, 1);
        previous.setTranscript("Saved transcript");
        previous.setSummary("Saved summary");
        previous.setTasksJson("[\"Saved task\"]");
        database.aiAnalysisDao().upsert(previous);

        assertEquals(failure("AI_NOT_CONFIGURED"), worker(recordId, 0).doWork());

        AiAnalysisEntity stored = database.aiAnalysisDao().getByRecordId(recordId);
        assertNotNull(stored);
        assertEquals(AiAnalysisStatus.FAILED, stored.getStatus());
        assertEquals("Saved transcript", stored.getTranscript());
        assertEquals("Saved summary", stored.getSummary());
        assertEquals("[\"Saved task\"]", stored.getTasksJson());
    }

    @Test
    public void alreadyStoredSuccess_isNotProcessedAgainAfterRecovery() throws Exception {
        long recordId = insertRecording();
        AtomicInteger calls = new AtomicInteger();
        processor = (id, file) -> { calls.incrementAndGet(); return result(id); };
        AiProcessingWorker first = worker(recordId, 0);
        assertEquals(ListenableWorker.Result.success(), first.doWork());
        AiProcessingWorker recovered = TestListenableWorkerBuilder.from(context, AiProcessingWorker.class)
                .setId(first.getId()).setInputData(input(recordId)).setWorkerFactory(factory).build();
        assertEquals(ListenableWorker.Result.success(), recovered.doWork());
        assertEquals(1, calls.get());
    }

    @Test
    public void lateResultAfterCancellation_isDiscarded() throws Exception {
        long recordId = insertRecording();
        CountDownLatch started = new CountDownLatch(1);
        processor = (id, file) -> {
            started.countDown();
            release.await();
            return result(id);
        };
        AiProcessingWorker worker = worker(recordId, 0);
        Future<ListenableWorker.Result> running = workerExecutor.submit(worker::doWork);
        assertTrue(started.await(10, TimeUnit.SECONDS));
        repository.cancelAndWait(recordId);
        release.countDown();
        assertEquals(failure("STALE_REQUEST"), running.get(10, TimeUnit.SECONDS));
        AiAnalysisEntity stored = database.aiAnalysisDao().getByRecordId(recordId);
        assertNotNull(stored);
        assertEquals(AiAnalysisStatus.CANCELLED, stored.getStatus());
        assertNull(stored.getTranscript());
    }

    @Test
    public void deletedRecording_isNotRecreatedByLateResult() throws Exception {
        long recordId = insertRecording();
        processor = (id, file) -> {
            database.recordDao().deleteById(id);
            return result(id);
        };
        assertEquals(failure("PROCESSING_FAILED"), worker(recordId, 0).doWork());
        assertNull(database.recordDao().getById(recordId));
        assertNull(database.aiAnalysisDao().getByRecordId(recordId));
    }

    @Test
    public void repeatedEnqueue_keepsOneActiveRequest() throws Exception {
        long recordId = insertRecording();
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        processor = (id, file) -> {
            calls.incrementAndGet();
            started.countDown();
            release.await();
            return result(id);
        };
        enqueueAndWait(recordId);
        assertTrue(started.await(10, TimeUnit.SECONDS));
        enqueueAndWait(recordId);
        long active = workManager.getWorkInfosForUniqueWork(AiRepository.workName(recordId))
                .get(10, TimeUnit.SECONDS).stream().filter(info -> !info.getState().isFinished()).count();
        assertEquals(1L, active);
        assertEquals(1, calls.get());
        repository.cancelAndWait(recordId);
        assertEquals(AiAnalysisStatus.CANCELLED,
                database.aiAnalysisDao().getByRecordId(recordId).getStatus());
        assertTrue(workManager.getWorkInfosForUniqueWork(AiRepository.workName(recordId))
                .get(10, TimeUnit.SECONDS).stream()
                .allMatch(info -> info.getState() == WorkInfo.State.CANCELLED));
    }

    private void enqueueAndWait(long recordId) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        repository.enqueue(recordId, (success, code) -> {
            if (!success) { error.set(code); }
            completed.countDown();
        });
        assertTrue(completed.await(10, TimeUnit.SECONDS));
        assertNull(error.get());
    }

    private AiProcessingWorker worker(long recordId, int attempts) {
        return TestListenableWorkerBuilder.from(context, AiProcessingWorker.class)
                .setInputData(input(recordId)).setRunAttemptCount(attempts)
                .setWorkerFactory(factory).build();
    }

    private Data input(long recordId) {
        return new Data.Builder().putLong(AiProcessingWorker.KEY_RECORD_ID, recordId).build();
    }

    private AiResult result(long recordId) {
        return new AiResult(recordId, "Transcript", "Summary", "Suggested title",
                Collections.singletonList("Task"));
    }

    private ListenableWorker.Result failure(String errorCode) {
        return ListenableWorker.Result.failure(new Data.Builder()
                .putString(AiProcessingWorker.KEY_ERROR_CODE, errorCode).build());
    }

    private long insertRecording() throws Exception {
        File file = new File(directory, "record_1000.m4a");
        // Test-only bytes: the injected processor does not decode audio.
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[]{1, 2, 3});
        }
        return database.recordDao().insert(new RecordEntity(file.getName(), file.getAbsolutePath(),
                1000L, "Original title", 5000L));
    }
}
