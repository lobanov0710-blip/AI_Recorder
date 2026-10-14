package com.nicko.airecorder.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AiResultTest {

    @Test
    public void tasks_areIsolatedFromLaterChangesToInputList() {
        List<String> tasks = new ArrayList<>(
                Arrays.asList("Call back", "Send notes")
        );
        AiResult result = new AiResult(17L, "", "", "", tasks);

        tasks.set(0, "Changed task");
        tasks.clear();

        assertEquals(
                Arrays.asList("Call back", "Send notes"),
                result.getTasks()
        );
    }

    @Test
    public void tasks_cannotBeChangedThroughReturnedList() {
        AiResult result = new AiResult(
                17L,
                "",
                "",
                "",
                Collections.singletonList("Call back")
        );

        assertThrows(
                UnsupportedOperationException.class,
                () -> result.getTasks().set(0, "Changed task")
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.getTasks().add("New task")
        );
    }

    @Test
    public void emptyAnalysis_isAllowedForRecordingWithoutRecognizedSpeech() {
        AiResult result = new AiResult(
                17L,
                "",
                "",
                "",
                Collections.emptyList()
        );

        assertEquals("", result.getTranscript());
        assertEquals("", result.getSummary());
        assertEquals("", result.getSuggestedTitle());
        assertTrue(result.getTasks().isEmpty());
    }

    @Test
    public void nonPositiveRecordId_isRejected() {
        for (long recordId : new long[]{0L, -1L}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AiResult(
                            recordId,
                            "",
                            "",
                            "",
                            Collections.emptyList()
                    )
            );
        }
    }

    @Test
    public void nullTextFields_areRejected() {
        assertThrows(
                NullPointerException.class,
                () -> new AiResult(17L, null, "", "", Collections.emptyList())
        );
        assertThrows(
                NullPointerException.class,
                () -> new AiResult(17L, "", null, "", Collections.emptyList())
        );
        assertThrows(
                NullPointerException.class,
                () -> new AiResult(17L, "", "", null, Collections.emptyList())
        );
    }

    @Test
    public void nullTasksOrNullTaskItem_areRejected() {
        assertThrows(
                NullPointerException.class,
                () -> new AiResult(17L, "", "", "", null)
        );
        assertThrows(
                NullPointerException.class,
                () -> new AiResult(
                        17L,
                        "",
                        "",
                        "",
                        Arrays.asList("Call back", null)
                )
        );
    }
}
