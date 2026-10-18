package com.nicko.airecorder.ai;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.Proxy;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;

/** Sends a finalized M4A to the self-hosted API; owns no recording or Room state. */
public final class RemoteAiProcessor implements AiProcessor {
    static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private static final MediaType AUDIO_TYPE = MediaType.get("audio/mp4");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.MINUTES)
            .callTimeout(8, TimeUnit.MINUTES)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .proxy(Proxy.NO_PROXY)
            .build();

    private final HttpUrl endpoint;
    private final OkHttpClient client;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Call> activeCall = new AtomicReference<>();

    public RemoteAiProcessor(String baseUrl) {
        this(baseUrl, false);
    }

    public RemoteAiProcessor(String baseUrl, boolean allowLanHttp) {
        this(endpoint(baseUrl, allowLanHttp), CLIENT);
    }

    // Package-private transport injection for tests; production always uses CLIENT.
    RemoteAiProcessor(HttpUrl endpoint, OkHttpClient client) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.client = Objects.requireNonNull(client, "client").newBuilder()
                .retryOnConnectionFailure(false).followRedirects(false)
                .followSslRedirects(false).proxy(Proxy.NO_PROXY).build();
    }

    private static HttpUrl endpoint(String baseUrl, boolean allowLanHttp) {
        HttpUrl base = HttpUrl.get(baseUrl);
        if (!base.username().isEmpty() || !base.password().isEmpty()
                || base.query() != null || base.fragment() != null || !base.encodedPath().equals("/")) {
            throw new IllegalArgumentException("An origin URL without credentials is required");
        }
        if (!base.isHttps() && !(base.host().equals("10.0.2.2")
                || base.host().equals("127.0.0.1") || base.host().equals("localhost")
                || (allowLanHttp && isPrivateIpv4(base.host())))) {
            throw new IllegalArgumentException("HTTP is limited to local development");
        }
        return base.newBuilder().addPathSegments("v1/analyze").build();
    }

    private static boolean isPrivateIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) {
                return false;
            }
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i] > 255) {
                return false;
            }
        }
        return octets[0] == 10 || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168);
    }

    @Override
    public AiResult process(long recordId, File audioFile) throws IOException, InterruptedException {
        checkCancelled();
        if (recordId <= 0L) {
            throw new AiProcessingException("INVALID_RECORD_ID", false);
        }
        if (audioFile == null || !audioFile.isFile() || !audioFile.canRead()
                || !audioFile.getName().toLowerCase(Locale.ROOT).endsWith(".m4a")
                || audioFile.length() == 0L) {
            throw new AiProcessingException("INVALID_RECORDING", false);
        }
        if (audioFile.length() > MAX_FILE_BYTES) {
            throw new AiProcessingException("FILE_TOO_LARGE", false);
        }
        RequestBody multipart = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("record_id", Long.toString(recordId))
                .addFormDataPart("file", "recording.m4a", RequestBody.create(audioFile, AUDIO_TYPE))
                .build();
        Call call = client.newCall(new Request.Builder().url(endpoint)
                .header("Accept", "application/json").post(oneShot(multipart)).build());
        if (!activeCall.compareAndSet(null, call)) {
            throw new AiProcessingException("PROCESSING_FAILED", false);
        }
        try {
            checkCancelled();
            try (Response response = call.execute()) {
                ResponseBody responseBody = response.body();
                if (response.code() != 200) {
                    String code = null;
                    if (responseBody != null) {
                        try {
                            code = AiResponseParser.errorCode(readLimited(responseBody, 16 * 1024));
                        } catch (AiProcessingException invalidBody) {
                            // Error messages are untrusted; fall back to the HTTP status.
                        }
                    }
                    throw serverError(response.code(), code);
                }
                if (responseBody == null) {
                    throw new AiProcessingException("INVALID_AI_RESPONSE", false);
                }
                AiResult result = AiResponseParser.parse(
                        readLimited(responseBody, MAX_RESPONSE_BYTES), recordId);
                checkCancelled();
                return result;
            }
        } catch (AiProcessingException error) {
            checkCancelled();
            throw error;
        } catch (IOException error) {
            checkCancelled();
            if (error instanceof InterruptedIOException) {
                throw new AiProcessingException("AI_TIMEOUT", true);
            }
            if (!audioFile.isFile() || !audioFile.canRead()) {
                throw new AiProcessingException("INVALID_RECORDING", false);
            }
            throw new AiProcessingException("NETWORK_UNAVAILABLE", true);
        } finally {
            activeCall.compareAndSet(call, null);
        }
    }

    private static RequestBody oneShot(RequestBody body) {
        // Also prohibit protocol-level follow-ups, such as 503 with Retry-After: 0.
        return new RequestBody() {
            @Override public MediaType contentType() { return body.contentType(); }
            @Override public long contentLength() throws IOException { return body.contentLength(); }
            @Override public boolean isOneShot() { return true; }
            @Override public void writeTo(BufferedSink sink) throws IOException { body.writeTo(sink); }
        };
    }

    private byte[] readLimited(ResponseBody body, int limit) throws IOException, InterruptedException {
        if (body.contentLength() > limit) {
            throw new AiProcessingException("INVALID_AI_RESPONSE", false);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InputStream input = body.byteStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            checkCancelled();
            if (count > limit - output.size()) {
                throw new AiProcessingException("INVALID_AI_RESPONSE", false);
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static AiProcessingException serverError(int status, String code) {
        switch (status) {
            case 401:
            case 403: return new AiProcessingException("AI_AUTH_REQUIRED", false);
            case 404: return new AiProcessingException("AI_ENDPOINT_NOT_FOUND", false);
            case 408: return new AiProcessingException("UPLOAD_TIMEOUT", true);
            case 413:
                if ("TEXT_TOO_LONG".equals(code) || "AUDIO_TOO_LONG".equals(code)) {
                    return new AiProcessingException(code, false);
                }
                return new AiProcessingException("FILE_TOO_LARGE", false);
            case 415: return new AiProcessingException("UNSUPPORTED_FORMAT", false);
            case 422:
                if ("NO_SPEECH".equals(code)) {
                    return new AiProcessingException(code, false);
                }
                return new AiProcessingException("INVALID_RECORDING", false);
            case 429: return new AiProcessingException("SERVER_BUSY", true);
            case 502:
                if ("AI_TASK_UNGROUNDED".equals(code) || "AI_RESPONSE_INVALID".equals(code)) {
                    return new AiProcessingException(code, false);
                }
                break;
            case 503:
                if ("AI_MODEL_UNAVAILABLE".equals(code)) {
                    return new AiProcessingException(code, false);
                }
                return new AiProcessingException("AI_UNAVAILABLE", true);
            case 504: return new AiProcessingException("AI_TIMEOUT", true);
            default: break;
        }
        return new AiProcessingException(status >= 500 ? "AI_SERVER_ERROR" : "AI_REQUEST_INVALID",
                status >= 500);
    }

    private void checkCancelled() throws InterruptedException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("AI processing cancelled");
        }
    }

    @Override
    public void cancel() {
        cancelled.set(true);
        Call call = activeCall.get();
        if (call != null) {
            call.cancel();
        }
    }
}
