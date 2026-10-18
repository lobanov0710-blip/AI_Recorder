package com.nicko.airecorder.ai;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.RandomAccessFile;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;

/** Real local HTTP requests; fixture bytes are not decoded or sent to the AI models. */
public class RemoteAiProcessorTest {
    private static final String JSON = "{\"recordId\":17,"
            + "\"transcript\":\"Завтра в 10 утра позвонить Андрею.\","
            + "\"summary\":\"Завтра в 10 утра позвонить Андрею.\","
            + "\"suggestedTitle\":\"Голосовая заметка\","
            + "\"tasks\":[\"Завтра в 10 утра позвонить Андрею.\"]}";
    private static final byte[] AUDIO = "test-only-audio-bytes".getBytes(StandardCharsets.UTF_8);

    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();
    private MockWebServer server;
    private File audio;
    private RemoteAiProcessor processor;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        audio = temporaryFolder.newFile("record_1000.m4a");
        Files.write(audio.toPath(), AUDIO);
        processor = new RemoteAiProcessor(server.url("/").toString());
    }

    @After
    public void tearDown() throws Exception {
        processor.cancel();
        server.shutdown();
    }

    @Test
    public void multipartUpload_mapsAllFieldsAndKeepsSourceUnchanged() throws Exception {
        server.enqueue(json(JSON));
        AiResult result = processor.process(17L, audio);
        assertEquals(17L, result.getRecordId());
        assertEquals("Завтра в 10 утра позвонить Андрею.", result.getTranscript());
        assertEquals(result.getTranscript(), result.getSummary());
        assertEquals("Голосовая заметка", result.getSuggestedTitle());
        assertEquals(1, result.getTasks().size());
        assertEquals(result.getTranscript(), result.getTasks().get(0));
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("POST", request.getMethod());
        assertEquals("/v1/analyze", request.getPath());
        assertEquals("application/json", request.getHeader("Accept"));
        assertTrue(request.getHeader("Content-Type").startsWith("multipart/form-data; boundary="));
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("name=\"record_id\""));
        assertTrue(body.contains("\r\n\r\n17\r\n"));
        assertTrue(body.contains("name=\"file\"; filename=\"recording.m4a\""));
        assertTrue(body.contains("Content-Type: audio/mp4"));
        assertTrue(body.contains(new String(AUDIO, StandardCharsets.UTF_8)));
        assertArrayEquals(AUDIO, Files.readAllBytes(audio.toPath()));
    }

    @Test
    public void emptyTaskArray_isAValidResult() throws Exception {
        server.enqueue(json(JSON.replace("[\"Завтра в 10 утра позвонить Андрею.\"]", "[]")));
        assertTrue(processor.process(17L, audio).getTasks().isEmpty());
    }

    @Test
    public void longIds_areNotRoundedThroughFloatingPoint() throws Exception {
        server.enqueue(json(JSON.replace("\"recordId\":17", "\"recordId\":9223372036854775807")));
        assertEquals(Long.MAX_VALUE, processor.process(Long.MAX_VALUE, audio).getRecordId());
    }

    @Test
    public void anotherRecordId_isAPermanentFailure() throws Exception {
        server.enqueue(json(JSON.replace("\"recordId\":17", "\"recordId\":18")));
        assertError("INVALID_AI_RESULT", false);
    }

    @Test
    public void wrongTypesMissingFieldsDuplicatesAndTrailingData_areRejected() throws Exception {
        String[] invalid = {
                JSON.replace("\"recordId\":17", "\"recordId\":\"17\""),
                JSON.replace("\"recordId\":17", "\"recordId\":17.0"),
                JSON.replace("\"recordId\":17", "\"recordId\":1e1"),
                JSON.replace("\"recordId\":17", "\"recordId\":9223372036854775808"),
                JSON.replace("\"recordId\":17", "\"recordId\":17,\"recordId\":17"),
                JSON.replace("\"recordId\":17,", ""),
                JSON.replace("\"summary\":\"Завтра в 10 утра позвонить Андрею.\"", "\"summary\":null"),
                JSON.replace("[\"Завтра в 10 утра позвонить Андрею.\"]", "[123]"),
                JSON.replace("[\"Завтра в 10 утра позвонить Андрею.\"]", "null"),
                JSON.replace("\"Голосовая заметка\"", "\"  \""),
                JSON.replace("\"Голосовая заметка\"", "\"\\uD800\""),
                JSON.replace("{\"recordId\"", "{\"extra\":true,\"recordId\""),
                JSON + " {}", "not JSON", JSON.substring(0, JSON.length() - 1),
                JSON.replace("\"recordId\"", "'recordId'"),
        };
        for (String value : invalid) {
            server.enqueue(json(value));
            assertError("INVALID_AI_RESPONSE", false);
        }
    }

    @Test
    public void stringAndTaskCountLimits_areEnforced() throws Exception {
        server.enqueue(json(JSON.replace("Голосовая заметка", "x".repeat(161))));
        assertError("INVALID_AI_RESPONSE", false);
        String manyTasks = "[" + "\"Task\",".repeat(20) + "\"Task\"]";
        server.enqueue(json(JSON.replace("[\"Завтра в 10 утра позвонить Андрею.\"]", manyTasks)));
        assertError("INVALID_AI_RESPONSE", false);
    }

    @Test
    public void invalidUtf8_isRejected() throws Exception {
        server.enqueue(new MockResponse().setBody(new Buffer().write(new byte[]{(byte) 0xC3, 0x28})));
        assertError("INVALID_AI_RESPONSE", false);
    }

    @Test
    public void knownLengthAndChunkedResponses_areBounded() throws Exception {
        String oversized = "x".repeat(RemoteAiProcessor.MAX_RESPONSE_BYTES + 1);
        server.enqueue(json(oversized));
        assertError("INVALID_AI_RESPONSE", false);
        server.enqueue(new MockResponse().setChunkedBody(oversized, 2048));
        assertError("INVALID_AI_RESPONSE", false);
    }

    @Test
    public void busyAndUnavailableServers_areRetryable() throws Exception {
        for (int status : new int[]{429, 500, 503}) {
            server.enqueue(new MockResponse().setResponseCode(status).setBody("untrusted error message"));
            assertError(status == 429 ? "SERVER_BUSY" : status == 503 ? "AI_UNAVAILABLE" : "AI_SERVER_ERROR", true);
        }
    }

    @Test
    public void serverValidationFailures_andMissingModels_arePermanent() throws Exception {
        Object[][] cases = {
                {413, "TEXT_TOO_LONG"}, {413, "AUDIO_TOO_LONG"}, {413, "FILE_TOO_LARGE"},
                {415, "UNSUPPORTED_FORMAT"}, {422, "NO_SPEECH"},
                {502, "AI_TASK_UNGROUNDED"}, {502, "AI_RESPONSE_INVALID"}, {503, "AI_MODEL_UNAVAILABLE"},
        };
        for (Object[] item : cases) {
            server.enqueue(json("{\"detail\":{\"code\":\"" + item[1] + "\",\"message\":\"private text\"}}")
                    .setResponseCode((int) item[0]));
            assertError((String) item[1], false);
        }
    }

    @Test
    public void authAndMissingEndpoints_failWithoutRetryOrPrivateErrorText() throws Exception {
        for (int status : new int[]{401, 403, 404}) {
            server.enqueue(new MockResponse().setResponseCode(status).setBody("private transcript here"));
            AiProcessingException error = assertError(status == 404 ? "AI_ENDPOINT_NOT_FOUND" : "AI_AUTH_REQUIRED", false);
            assertFalse(error.getMessage().contains("private"));
        }
    }

    @Test
    public void redirect_isNotFollowedOrReplayed() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(307).setHeader("Location", server.url("/other")));
        assertError("AI_REQUEST_INVALID", false);
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void retryAfterZero_doesNotReplayTheAudioWithinOneAttempt() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "0"));
        server.enqueue(json(JSON));
        assertError("AI_UNAVAILABLE", true);
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void invalidAndOversizedFiles_areRejectedBeforeAnyRequest() throws Exception {
        AiProcessingException invalidId = assertThrows(AiProcessingException.class,
                () -> processor.process(0L, audio));
        assertEquals("INVALID_RECORD_ID", invalidId.getErrorCode());
        assertEquals("INVALID_RECORDING", assertThrows(AiProcessingException.class,
                () -> processor.process(17L, temporaryFolder.newFile("empty.m4a"))).getErrorCode());
        File huge = temporaryFolder.newFile("large.m4a");
        try (RandomAccessFile handle = new RandomAccessFile(huge, "rw")) {
            handle.setLength(RemoteAiProcessor.MAX_FILE_BYTES + 1L);
        }
        assertEquals("FILE_TOO_LARGE", assertThrows(AiProcessingException.class,
                () -> processor.process(17L, huge)).getErrorCode());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void totalCallTimeout_isRetryableWithoutReplayingTheUpload() throws Exception {
        processor = new RemoteAiProcessor(server.url("/v1/analyze"), new OkHttpClient.Builder()
                .callTimeout(200, TimeUnit.MILLISECONDS).readTimeout(5, TimeUnit.SECONDS).build());
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        assertError("AI_TIMEOUT", true);
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void failedConnection_isRetryable() throws Exception {
        processor = new RemoteAiProcessor(server.url("/v1/analyze"), new OkHttpClient.Builder()
                .addInterceptor(chain -> { throw new ConnectException("private connection text"); }).build());
        assertError("NETWORK_UNAVAILABLE", true);
    }

    @Test
    public void cancellation_abortsBlockedHttpAndPreventsFurtherProcessing() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AiResult> future = executor.submit(() -> processor.process(17L, audio));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            processor.cancel();
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> future.get(2, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof InterruptedException);
            assertThrows(InterruptedException.class, () -> processor.process(17L, audio));
            assertEquals(1, server.getRequestCount());
        } finally {
            processor.cancel();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void interruptedThread_doesNotStartAnUpload() throws Exception {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> processor.process(17L, audio));
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void configuredFactoryCreatesRemoteProvider_andBadConfigurationFailsSafely() throws Exception {
        assertTrue(AiProcessorFactory.create(server.url("/").toString()) instanceof RemoteAiProcessor);
        for (String url : new String[]{"", "not a URL", "http://example.com/", "https://user:secret@example.com/",
                "https://example.com/?token=secret", "https://example.com/other/"}) {
            AiProcessingException error = assertThrows(AiProcessingException.class,
                    () -> AiProcessorFactory.create(url).process(17L, audio));
            assertEquals("AI_NOT_CONFIGURED", error.getErrorCode());
            assertFalse(error.isRetryable());
        }
    }

    private AiProcessingException assertError(String code, boolean retryable) {
        AiProcessingException error = assertThrows(AiProcessingException.class,
                () -> processor.process(17L, audio));
        assertEquals(code, error.getErrorCode());
        assertEquals(retryable, error.isRetryable());
        return error;
    }

    private MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
