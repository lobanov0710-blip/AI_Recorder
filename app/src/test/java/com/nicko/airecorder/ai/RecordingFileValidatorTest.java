package com.nicko.airecorder.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class RecordingFileValidatorTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void savedFinalFileInsideStorage_isAccepted() throws Exception {
        File directory = temporaryFolder.newFolder("recordings");
        File file = writeFile(directory, "record_1000.m4a");
        assertEquals(file.getCanonicalFile(), RecordingFileValidator.requireFinalFile(
                directory, file.getPath(), 5000L
        ));
    }

    @Test
    public void pendingFile_isRejectedEvenWhenItExists() throws Exception {
        File directory = temporaryFolder.newFolder("recordings");
        File file = writeFile(directory, "record_1000.pending.m4a");
        assertThrows(IOException.class, () -> RecordingFileValidator.requireFinalFile(
                directory, file.getPath(), 5000L
        ));
    }

    @Test
    public void pathOutsideStorage_isRejected() throws Exception {
        File directory = temporaryFolder.newFolder("recordings");
        File outside = temporaryFolder.newFolder("outside");
        File file = writeFile(outside, "record_1000.m4a");
        assertThrows(IOException.class, () -> RecordingFileValidator.requireFinalFile(
                directory, file.getPath(), 5000L
        ));
    }

    @Test
    public void missingOrEmptyFile_isRejected() throws Exception {
        File directory = temporaryFolder.newFolder("recordings");
        File file = new File(directory, "record_1000.m4a");
        assertThrows(IOException.class, () -> RecordingFileValidator.requireFinalFile(
                directory, file.getPath(), 5000L
        ));
        Files.createFile(file.toPath());
        assertThrows(IOException.class, () -> RecordingFileValidator.requireFinalFile(
                directory, file.getPath(), 5000L
        ));
    }

    @Test
    public void absentPathOrUnvalidatedDuration_isRejected() throws Exception {
        File directory = temporaryFolder.newFolder("recordings");
        File file = writeFile(directory, "record_1000.m4a");
        assertThrows(IOException.class, () -> RecordingFileValidator.requireFinalFile(
                directory, null, 5000L
        ));
        assertThrows(IOException.class, () -> RecordingFileValidator.requireFinalFile(
                directory, file.getPath(), 0L
        ));
    }

    private File writeFile(File directory, String name) throws IOException {
        File file = new File(directory, name);
        // Only path/readability checks are tested; no audio decoder is involved.
        Files.write(file.toPath(), new byte[]{1, 2, 3});
        return file;
    }
}
