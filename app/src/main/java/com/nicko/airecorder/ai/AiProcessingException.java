package com.nicko.airecorder.ai;

import java.io.IOException;

/** A safe error code and an explicit indication whether retrying may help. */
public final class AiProcessingException extends IOException {
    private static final long serialVersionUID = 1L;
    private final String errorCode;
    private final boolean retryable;

    public AiProcessingException(String errorCode, boolean retryable) {
        super(errorCode);
        if (errorCode == null || !errorCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("A bounded error code is required");
        }
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
