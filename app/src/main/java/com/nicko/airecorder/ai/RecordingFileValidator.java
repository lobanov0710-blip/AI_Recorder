package com.nicko.airecorder.ai;

import java.io.File;
import java.io.IOException;

public final class RecordingFileValidator {
    private RecordingFileValidator() {
    }

    /** Room supplies the path; arbitrary paths from worker input are not accepted. */
    public static File requireFinalFile(File filesDir, String filePath, long duration)
            throws IOException {
        if (filePath == null || duration <= 0L) {
            throw new IOException("INVALID_RECORDING");
        }
        File file = new File(filePath).getCanonicalFile();
        File directory = filesDir.getCanonicalFile();
        if (!directory.equals(file.getParentFile())
                || !file.getName().matches("record_[0-9]+\\.m4a")
                || !file.isFile() || !file.canRead() || file.length() <= 0L) {
            throw new IOException("INVALID_RECORDING");
        }
        // The recording service already validated the M4A before inserting Room.
        return file;
    }
}
