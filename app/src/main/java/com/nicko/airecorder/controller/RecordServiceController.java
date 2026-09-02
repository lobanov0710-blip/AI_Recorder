package com.nicko.airecorder.controller;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import com.nicko.airecorder.database.RecordEntity;
import com.nicko.airecorder.repository.RecordRepository;
import com.nicko.airecorder.utils.AudioRecorder;

import java.io.File;

public class RecordServiceController {

    private static final String TAG =
            "RecordServiceController";

    /*
     * =========================================================
     * FILE NAMING
     * =========================================================
     */

    private static final String RECORD_PREFIX =
            "record_";

    private static final String FINAL_EXTENSION =
            ".m4a";

    private static final String PENDING_EXTENSION =
            ".pending.m4a";

    /*
     * =========================================================
     * COMPONENTS
     * =========================================================
     */

    private final AudioRecorder audioRecorder;

    private final RecordRepository repository;

    private final Context context;

    /*
     * =========================================================
     * CURRENT RECORDING FILE STATE
     * =========================================================
     *
     * Во время записи AudioRecorder пишет только в:
     *
     * record_<timestamp>.pending.m4a
     *
     * После successful stop + media validation
     * файл становится:
     *
     * record_<timestamp>.m4a
     */
    private File pendingOutputFile;

    private File finalOutputFile;

    /*
     * Timestamp создаётся один раз при START.
     *
     * Он используется:
     *
     * - в имени файла;
     * - как createdAt в Room.
     */
    private long recordingCreatedAt =
            0L;

    /*
     * =========================================================
     * CONSTRUCTOR
     * =========================================================
     */

    public RecordServiceController(
            Context context
    ) {

        this.context =
                context.getApplicationContext();

        this.audioRecorder =
                new AudioRecorder();

        this.repository =
                new RecordRepository(
                        this.context
                );
    }

    /*
     * =========================================================
     * PAUSE
     * =========================================================
     */

    public boolean pauseRecording() {

        return audioRecorder
                .pauseRecording();
    }

    /*
     * =========================================================
     * RESUME
     * =========================================================
     */

    public boolean resumeRecording() {

        return audioRecorder
                .resumeRecording();
    }

    /*
     * =========================================================
     * STATE
     * =========================================================
     */

    public boolean isPaused() {

        return audioRecorder
                .isPaused();
    }

    public boolean isRecording() {

        return audioRecorder
                .isRecording();
    }

    public int getMaxAmplitude() {

        return audioRecorder
                .getMaxAmplitude();
    }

    /*
     * =========================================================
     * START RECORDING
     * =========================================================
     */

    public File startRecording()
            throws Exception {

        /*
         * Защитный idempotent path.
         *
         * Обычно RecordService сам предотвращает
         * повторный START, но Controller тоже
         * не должен создавать новое имя файла,
         * если AudioRecorder уже пишет.
         */
        if (audioRecorder.isRecording()) {

            return pendingOutputFile;
        }

        long createdAt =
                System.currentTimeMillis();

        String baseName =
                RECORD_PREFIX
                        + createdAt;

        File newPendingFile =
                new File(
                        context.getFilesDir(),
                        baseName
                                + PENDING_EXTENSION
                );

        File newFinalFile =
                new File(
                        context.getFilesDir(),
                        baseName
                                + FINAL_EXTENSION
                );

        /*
         * Timestamp collision практически невозможен,
         * но существующий FINAL никогда не перезаписываем.
         */
        if (newFinalFile.exists()) {

            throw new IllegalStateException(
                    "Финальный файл записи уже существует: "
                            + newFinalFile.getAbsolutePath()
            );
        }

        /*
         * Сохраняем metadata до старта pipeline.
         *
         * Если startRecording() завершится exception,
         * catch очистит этот transient state.
         */
        recordingCreatedAt =
                createdAt;

        pendingOutputFile =
                newPendingFile;

        finalOutputFile =
                newFinalFile;

        try {

            audioRecorder.startRecording(
                    newPendingFile
            );

            Log.d(
                    TAG,
                    "Запись начата во временный файл: "
                            + newPendingFile.getAbsolutePath()
            );

            return newPendingFile;

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Не удалось запустить запись",
                    e
            );

            /*
             * AudioRecorder сам выполняет cleanup
             * своих media/native ресурсов.
             *
             * Здесь удаляем только оставшийся
             * filesystem artifact, если он существует
             * и больше не используется recorder pipeline.
             *
             * Если AudioRecorder ещё считает запись активной,
             * файл не трогаем.
             */

            clearRecordingFileState();

            throw e;
        }
    }

    /*
     * =========================================================
     * STOP RECORDING
     * =========================================================
     */

    public boolean stopRecording() {

        return audioRecorder
                .stopRecording();
    }

    /*
     * =========================================================
     * SAVE RECORD
     * =========================================================
     *
     * Этот метод вызывается только после:
     *
     * audioRecorder.stopRecording() == true
     *
     * Порядок:
     *
     * 1. проверяем pending M4A;
     * 2. переводим pending → final;
     * 3. ещё раз проверяем final artifact;
     * 4. выполняем confirmed Room INSERT;
     * 5. возвращаем true только после DB commit.
     */
    public boolean saveRecord() {

        File pendingFile =
                pendingOutputFile;

        File finalFile =
                finalOutputFile;

        long createdAt =
                recordingCreatedAt;

        /*
         * =====================================================
         * INTERNAL STATE VALIDATION
         * =====================================================
         */

        if (pendingFile == null
                || finalFile == null
                || createdAt <= 0L) {

            Log.e(
                    TAG,
                    "Отсутствует file state для сохранения записи"
            );

            return false;
        }

        /*
         * P0.1/P0.2 invariant:
         *
         * AudioRecorder должен вернуть тот же output,
         * который был передан ему при START.
         */
        File recorderOutput =
                audioRecorder
                        .getOutputFile();

        if (recorderOutput == null) {

            Log.e(
                    TAG,
                    "AudioRecorder не вернул output file"
            );

            return false;
        }

        if (!sameFilePath(
                pendingFile,
                recorderOutput
        )) {

            Log.e(
                    TAG,
                    "Несовпадение output file. expected="
                            + pendingFile.getAbsolutePath()
                            + ", actual="
                            + recorderOutput.getAbsolutePath()
            );

            return false;
        }

        /*
         * =====================================================
         * PENDING VALIDATION
         * =====================================================
         */

        long pendingMediaDuration =
                readMediaDuration(
                        pendingFile
                );

        if (pendingMediaDuration <= 0L) {

            Log.e(
                    TAG,
                    "Pending M4A не прошёл media validation: "
                            + pendingFile.getAbsolutePath()
            );

            deleteFileQuietly(
                    pendingFile,
                    "invalid pending recording"
            );

            clearRecordingFileState();

            return false;
        }

        /*
         * =====================================================
         * PENDING → FINAL
         * =====================================================
         */

        if (!promotePendingToFinal(
                pendingFile,
                finalFile
        )) {

            Log.e(
                    TAG,
                    "Не удалось перевести pending recording в final"
            );

            /*
             * Валидный pending не уничтожаем.
             */
            return false;
        }

        /*
         * =====================================================
         * FINAL MEDIA VALIDATION
         * =====================================================
         *
         * Именно duration FINAL M4A является
         * persisted source of truth.
         */
        long finalMediaDuration =
                readMediaDuration(
                        finalFile
                );

        if (finalMediaDuration <= 0L) {

            Log.e(
                    TAG,
                    "Final M4A не прошёл validation: "
                            + finalFile.getAbsolutePath()
            );

            deleteFileQuietly(
                    finalFile,
                    "invalid final recording"
            );

            clearRecordingFileState();

            return false;
        }

        /*
         * =====================================================
         * ROOM ENTITY
         * =====================================================
         *
         * P1.5:
         *
         * duration больше НЕ берётся из RecordTimer.
         *
         * Persisted duration = реальная длительность
         * финализированного media container.
         */
        RecordEntity entity =
                new RecordEntity(

                        finalFile.getName(),

                        finalFile.getAbsolutePath(),

                        createdAt,

                        finalFile.getName(),

                        finalMediaDuration
                );

        /*
         * =====================================================
         * CONFIRMED ROOM INSERT
         * =====================================================
         */

        boolean insertSuccess =
                repository.insertAndWait(
                        entity
                );

        if (!insertSuccess) {

            Log.e(
                    TAG,
                    "Room INSERT не подтверждён. "
                            + "Final M4A сохранён для recovery: "
                            + finalFile.getAbsolutePath()
            );

            /*
             * Валидный final-файл сохраняем.
             * Startup reconciliation сможет
             * восстановить запись.
             */
            clearRecordingFileState();

            return false;
        }

        Log.d(
                TAG,
                "Запись подтверждённо сохранена в Room: "
                        + finalFile.getAbsolutePath()
                        + ", mediaDuration="
                        + finalMediaDuration
                        + " ms"
        );

        clearRecordingFileState();

        return true;
    }

    /*
     * =========================================================
     * PENDING → FINAL
     * =========================================================
     */

    private boolean promotePendingToFinal(
            File pendingFile,
            File finalFile
    ) {

        if (pendingFile == null
                || finalFile == null) {

            return false;
        }

        if (!pendingFile.exists()
                || !pendingFile.isFile()) {

            return false;
        }

        /*
         * Никогда не перезаписываем существующий final.
         */
        if (finalFile.exists()) {

            Log.e(
                    TAG,
                    "Final path уже существует: "
                            + finalFile.getAbsolutePath()
            );

            return false;
        }

        boolean renamed =
                pendingFile.renameTo(
                        finalFile
                );

        if (!renamed) {

            Log.e(
                    TAG,
                    "File.renameTo() failed: "
                            + pendingFile.getAbsolutePath()
                            + " -> "
                            + finalFile.getAbsolutePath()
            );

            return false;
        }

        /*
         * Проверяем итоговое состояние filesystem.
         */
        if (!finalFile.exists()
                || !finalFile.isFile()
                || finalFile.length() <= 0L) {

            Log.e(
                    TAG,
                    "Final file отсутствует после rename: "
                            + finalFile.getAbsolutePath()
            );

            return false;
        }

        Log.d(
                TAG,
                "Recording promoted: "
                        + pendingFile.getName()
                        + " -> "
                        + finalFile.getName()
        );

        return true;
    }

    /*
     * =========================================================
     * SAME FILE
     * =========================================================
     */

    private boolean sameFilePath(
            File first,
            File second
    ) {

        if (first == null
                || second == null) {

            return false;
        }

        return first.getAbsolutePath()
                .equals(
                        second.getAbsolutePath()
                );
    }

    /*
     * =========================================================
     * MEDIA VALIDATION / DURATION
     * =========================================================
     */

    private long readMediaDuration(
            File file
    ) {

        if (file == null) {

            return -1L;
        }

        if (!file.exists()) {

            return -1L;
        }

        if (!file.isFile()) {

            return -1L;
        }

        if (file.length() <= 0L) {

            return -1L;
        }

        MediaMetadataRetriever retriever =
                new MediaMetadataRetriever();

        try {

            retriever.setDataSource(
                    file.getAbsolutePath()
            );

            String mediaDuration =
                    retriever.extractMetadata(
                            MediaMetadataRetriever
                                    .METADATA_KEY_DURATION
                    );

            if (mediaDuration == null) {

                return -1L;
            }

            long parsedDuration =
                    Long.parseLong(
                            mediaDuration
                    );

            return parsedDuration > 0L
                    ? parsedDuration
                    : -1L;

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Не удалось проверить M4A: "
                            + file.getAbsolutePath(),
                    e
            );

            return -1L;

        } finally {

            try {

                retriever.release();

            } catch (Exception e) {

                Log.w(
                        TAG,
                        "Ошибка release MediaMetadataRetriever",
                        e
                );
            }
        }
    }

    /*
     * =========================================================
     * FILE DELETE
     * =========================================================
     */

    private void deleteFileQuietly(
            File file,
            String reason
    ) {

        if (file == null
                || !file.exists()) {

            return;
        }

        if (!file.delete()) {

            Log.w(
                    TAG,
                    "Не удалось удалить файл ("
                            + reason
                            + "): "
                            + file.getAbsolutePath()
            );
        }
    }

    /*
     * =========================================================
     * CLEAR TRANSACTION STATE
     * =========================================================
     */

    private void clearRecordingFileState() {

        pendingOutputFile =
                null;

        finalOutputFile =
                null;

        recordingCreatedAt =
                0L;
    }

    /*
     * =========================================================
     * SHUTDOWN
     * =========================================================
     */

    public void shutdown() {

        repository.shutdown();
    }
}