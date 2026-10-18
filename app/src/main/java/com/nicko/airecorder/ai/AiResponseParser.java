package com.nicko.airecorder.ai;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Parses the server contract without reflection, coercion or accepting partial results. */
final class AiResponseParser {
    private AiResponseParser() {
    }

    static AiResult parse(byte[] json, long expectedRecordId) throws AiProcessingException {
        long recordId = -1L;
        String transcript = null;
        String summary = null;
        String title = null;
        List<String> tasks = new ArrayList<>();
        Set<String> fields = new HashSet<>();
        try (JsonReader reader = reader(json)) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!fields.add(name)) {
                    throw new IOException("Duplicate field");
                }
                switch (name) {
                    case "recordId":
                        if (reader.peek() != JsonToken.NUMBER) {
                            throw new IOException("Numeric ID required");
                        }
                        String number = reader.nextString();
                        if (!number.matches("[1-9][0-9]{0,18}")) {
                            throw new IOException("Positive integer ID required");
                        }
                        recordId = Long.parseLong(number);
                        break;
                    case "transcript": transcript = text(reader, 8192); break;
                    case "summary": summary = text(reader, 3000); break;
                    case "suggestedTitle": title = text(reader, 160); break;
                    case "tasks":
                        reader.beginArray();
                        while (reader.hasNext()) {
                            if (tasks.size() >= 20) {
                                throw new IOException("Too many tasks");
                            }
                            tasks.add(text(reader, 4096));
                        }
                        reader.endArray();
                        break;
                    default: throw new IOException("Unexpected field");
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || fields.size() != 5) {
                throw new IOException("Incomplete contract or trailing JSON");
            }
        } catch (IOException | IllegalStateException | NumberFormatException error) {
            throw new AiProcessingException("INVALID_AI_RESPONSE", false);
        }
        if (recordId != expectedRecordId) {
            throw new AiProcessingException("INVALID_AI_RESULT", false);
        }
        return new AiResult(recordId, transcript, summary, title, tasks);
    }

    static String errorCode(byte[] json) {
        String code = null;
        Set<String> rootFields = new HashSet<>();
        try (JsonReader reader = reader(json)) {
            reader.beginObject();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!rootFields.add(field)) {
                    return null;
                }
                if (!field.equals("detail") || reader.peek() != JsonToken.BEGIN_OBJECT) {
                    reader.skipValue();
                    continue;
                }
                Set<String> details = new HashSet<>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String detail = reader.nextName();
                    if (!details.add(detail)) {
                        return null;
                    }
                    if (detail.equals("code")) {
                        code = text(reader, 64);
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endObject();
            }
            reader.endObject();
            return reader.peek() == JsonToken.END_DOCUMENT && code != null
                    && code.matches("[A-Z][A-Z0-9_]{0,63}") ? code : null;
        } catch (IOException | IllegalStateException error) {
            return null;
        }
    }

    private static JsonReader reader(byte[] json) throws IOException {
        String decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(json)).toString();
        JsonReader reader = new JsonReader(new StringReader(decoded));
        reader.setStrictness(Strictness.STRICT);
        reader.setNestingLimit(8);
        return reader;
    }

    private static String text(JsonReader reader, int maxLength) throws IOException {
        if (reader.peek() != JsonToken.STRING) {
            throw new IOException("String required");
        }
        String value = reader.nextString();
        if (value.trim().isEmpty() || value.length() > maxLength) {
            throw new IOException("Invalid string length");
        }
        // Reject lone UTF-16 surrogates decoded from JSON escape sequences.
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IOException("Invalid Unicode");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new IOException("Invalid Unicode");
            }
        }
        return value;
    }
}
