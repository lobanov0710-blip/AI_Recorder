package com.nicko.airecorder.ai;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;

public class AiProcessorTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void fakeProcessor_returnsFixedAnalysisWithoutChangingSource()
            throws Exception {
        File audioFile = temporaryFolder.newFile("record_1.m4a");
        // Dummy bytes: this fake does not decode or validate M4A audio.
        byte[] originalBytes = {1, 2, 3, 4};
        Files.write(audioFile.toPath(), originalBytes);

        AiProcessor processor = new FakeAiProcessor();
        AiResult result = processor.process(17L, audioFile);

        assertEquals(17L, result.getRecordId());
        assertEquals("Test transcript", result.getTranscript());
        assertEquals("Test summary", result.getSummary());
        assertEquals("Test title", result.getSuggestedTitle());
        assertEquals(
                Collections.singletonList("Test task"),
                result.getTasks()
        );
        assertArrayEquals(originalBytes, Files.readAllBytes(audioFile.toPath()));
    }

    @Test
    public void fakeProcessor_keepsResultsAssociatedWithTheirRecordIds()
            throws Exception {
        AiProcessor processor = new FakeAiProcessor();

        AiResult first = processor.process(
                17L,
                temporaryFolder.newFile("record_1.m4a")
        );
        AiResult second = processor.process(
                18L,
                temporaryFolder.newFile("record_2.m4a")
        );

        assertEquals(17L, first.getRecordId());
        assertEquals(18L, second.getRecordId());
    }

    /** Test-only fake: no audio decoding, network calls or production wiring. */
    private static final class FakeAiProcessor implements AiProcessor {

        @Override
        public AiResult process(long recordId, File audioFile) {
            return new AiResult(
                    recordId,
                    "Test transcript",
                    "Test summary",
                    "Test title",
                    Collections.singletonList("Test task")
            );
        }
    }
}
