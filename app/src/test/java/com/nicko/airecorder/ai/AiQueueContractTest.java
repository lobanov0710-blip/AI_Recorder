package com.nicko.airecorder.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

public class AiQueueContractTest {
    @Test
    public void unconfiguredProcessor_failsExplicitlyInsteadOfReturningFakeText() {
        AiProcessingException error = assertThrows(AiProcessingException.class,
                () -> AiProcessorFactory.create("").process(17L, new File("record_1000.m4a")));
        assertEquals("AI_NOT_CONFIGURED", error.getErrorCode());
        assertFalse(error.isRetryable());
    }

    @Test
    public void transientError_canBeDistinguishedFromPermanentFailure() {
        assertTrue(new AiProcessingException("NETWORK_UNAVAILABLE", true).isRetryable());
        assertFalse(new AiProcessingException("INVALID_RECORDING", false).isRetryable());
    }

    @Test
    public void freeTextOrOversizedErrorCodes_areRejected() {
        for (String code : new String[]{null, "", "Response body with private text", "A".repeat(65)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new AiProcessingException(code, false));
        }
    }
}
