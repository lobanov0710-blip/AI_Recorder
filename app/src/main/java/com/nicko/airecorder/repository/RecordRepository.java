package com.nicko.airecorder.repository;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.LiveData;

import com.nicko.airecorder.database.AppDatabase;
import com.nicko.airecorder.database.RecordDao;
import com.nicko.airecorder.database.RecordEntity;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RecordRepository {

    private static final String TAG =
            "RecordRepository";

    /*
     * =========================================================
     * MANAGED FINAL RECORDING NAME
     * =========================================================
     *
     * Reconciliation имеет право автоматически
     * восстанавливать ТОЛЬКО файлы такого формата:
     *
     * record_<timestamp>.m4a
     *
     * Чужие файлы из filesDir не трогаются.
     */
    private static final Pattern FINAL_RECORD_PATTERN =
            Pattern.compile(
                    "^record_(\\d+)\\.m4a$"
            );

    /*
     * =========================================================
     * PROCESS-WIDE CONSISTENCY LOCK
     * =========================================================
     *
     * Сейчас приложение создаёт несколько экземпляров
     * RecordRepository:
     *
     * - RecordServiceController
     * - RecordViewModel
     *
     * У каждого собственный ExecutorService.
     *
     * Этот lock сериализует критические filesystem + Room
     * операции между всеми Repository внутри процесса.
     */
    private static final Object CONSISTENCY_LOCK =
            new Object();

    private final Context context;

    private final RecordDao recordDao;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private final Handler mainHandler =
            new Handler(
                    Looper.getMainLooper()
            );

    /*
     * =========================================================
     * CALLBACK
     * =========================================================
     */

    public interface OperationCallback {

        void onComplete(
                boolean success
        );
    }

    /*
     * =========================================================
     * CONSTRUCTOR
     * =========================================================
     */

    public RecordRepository(
            Context context
    ) {

        this.context =
                context.getApplicationContext();

        AppDatabase database =
                AppDatabase.getInstance(
                        this.context
                );

        recordDao =
                database.recordDao();
    }

    /*
     * =========================================================
     * OBSERVABLE RECORDS
     * =========================================================
     */

    public LiveData<List<RecordEntity>> getAll() {

        return recordDao.getAll();
    }

    /*
     * =========================================================
     * CONFIRMED INSERT
     * =========================================================
     *
     * Возвращает true только после того,
     * как Room реально завершил INSERT.
     *
     * DB операция выполняется на executor,
     * а вызывающий поток ждёт её подтверждения.
     *
     * Никакой сети здесь нет.
     */
    public boolean insertAndWait(
            RecordEntity entity
    ) {

        if (entity == null) {

            return false;
        }

        String filePath =
                entity.getFilePath();

        if (filePath == null
                || filePath.trim().isEmpty()) {

            Log.e(
                    TAG,
                    "INSERT отклонён: filePath отсутствует"
            );

            return false;
        }

        Future<Boolean> future;

        try {

            future =
                    executor.submit(
                            () -> {

                                synchronized (CONSISTENCY_LOCK) {

                                    /*
                                     * Возможен recovery race:
                                     *
                                     * reconciliation могла восстановить
                                     * этот же файл непосредственно перед
                                     * confirmed insert.
                                     *
                                     * В таком случае состояние уже
                                     * консистентно — считаем операцию
                                     * успешной.
                                     */
                                    int existing =
                                            recordDao.countByFilePath(
                                                    filePath
                                            );

                                    if (existing > 0) {

                                        Log.d(
                                                TAG,
                                                "Запись уже присутствует в Room: "
                                                        + filePath
                                        );

                                        return true;
                                    }

                                    long rowId =
                                            recordDao.insert(
                                                    entity
                                            );

                                    boolean success =
                                            rowId > 0L;

                                    if (!success) {

                                        Log.e(
                                                TAG,
                                                "Room INSERT не вернул валидный rowId: "
                                                        + rowId
                                        );
                                    }

                                    return success;
                                }
                            }
                    );

        } catch (RejectedExecutionException e) {

            Log.e(
                    TAG,
                    "Repository executor уже остановлен",
                    e
            );

            return false;
        }

        return awaitBooleanFuture(
                future,
                "confirmed insert"
        );
    }

    /*
     * =========================================================
     * RENAME
     * =========================================================
     */

    public void rename(
            long id,
            String title
    ) {

        if (id <= 0L
                || title == null
                || title.trim().isEmpty()) {

            return;
        }

        executeSafely(
                () -> {

                    synchronized (CONSISTENCY_LOCK) {

                        recordDao.rename(
                                id,
                                title
                        );
                    }
                },
                "rename"
        );
    }

    /*
     * =========================================================
     * CONSISTENT RECORD DELETE
     * =========================================================
     *
     * Весь delete lifecycle выполняется здесь:
     *
     * 1. физический файл;
     * 2. Room row;
     * 3. callback на main thread.
     *
     * Activity больше не должна сама удалять .m4a.
     */
    public void deleteRecord(
            long id,
            String filePath,
            OperationCallback callback
    ) {

        if (id <= 0L) {

            postCallback(
                    callback,
                    false
            );

            return;
        }

        try {

            executor.execute(
                    () -> {

                        boolean success;

                        try {

                            synchronized (CONSISTENCY_LOCK) {

                                success =
                                        deleteRecordInternal(
                                                id,
                                                filePath
                                        );
                            }

                        } catch (Exception e) {

                            Log.e(
                                    TAG,
                                    "Ошибка удаления записи",
                                    e
                            );

                            success =
                                    false;
                        }

                        postCallback(
                                callback,
                                success
                        );
                    }
            );

        } catch (RejectedExecutionException e) {

            Log.e(
                    TAG,
                    "Repository executor уже остановлен",
                    e
            );

            postCallback(
                    callback,
                    false
            );
        }
    }

    /*
     * =========================================================
     * DELETE IMPLEMENTATION
     * =========================================================
     */

    private boolean deleteRecordInternal(
            long id,
            String filePath
    ) {

        /*
         * Если filePath отсутствует,
         * DB row всё равно можно удалить.
         */
        if (filePath == null
                || filePath.trim().isEmpty()) {

            recordDao.deleteById(
                    id
            );

            return true;
        }

        File file =
                new File(
                        filePath
                );

        /*
         * Сначала filesystem.
         *
         * Если physical delete не получился —
         * Room row сохраняем, чтобы пользователь
         * не потерял доступ к существующему файлу.
         */
        if (file.exists()
                && !file.delete()) {

            Log.e(
                    TAG,
                    "Не удалось удалить файл: "
                            + file.getAbsolutePath()
            );

            return false;
        }

        /*
         * Затем Room.
         *
         * Если процесс аварийно завершится после file.delete()
         * и до этого DELETE, startup reconciliation удалит
         * stale DB row.
         */
        recordDao.deleteById(
                id
        );

        Log.d(
                TAG,
                "Запись удалена: id="
                        + id
                        + ", file="
                        + filePath
        );

        return true;
    }

    /*
     * =========================================================
     * STORAGE ↔ ROOM RECONCILIATION
     * =========================================================
     *
     * Выполняется асинхронно.
     *
     * Два направления:
     *
     * A. Room row → файла нет
     *    stale row удаляется.
     *
     * B. final record_*.m4a → Room row нет
     *    валидный файл восстанавливается.
     *
     * ВАЖНО:
     *
     * *.pending.m4a сюда не попадёт.
     */
    public void reconcileStorage() {

        executeSafely(
                () -> {

                    synchronized (CONSISTENCY_LOCK) {

                        reconcileStorageInternal();
                    }
                },
                "storage reconciliation"
        );
    }

    /*
     * =========================================================
     * RECONCILIATION IMPLEMENTATION
     * =========================================================
     */

    private void reconcileStorageInternal() {

        List<RecordEntity> records =
                recordDao.getAllSync();

        Set<String> databasePaths =
                new HashSet<>();

        int removedRows =
                0;

        int recoveredFiles =
                0;

        /*
         * =====================================================
         * ROOM → FILESYSTEM
         * =====================================================
         */

        if (records != null) {

            for (RecordEntity entity : records) {

                if (entity == null) {

                    continue;
                }

                String filePath =
                        entity.getFilePath();

                /*
                 * DB row без filePath не может быть
                 * полезной записью.
                 */
                if (filePath == null
                        || filePath.trim().isEmpty()) {

                    recordDao.deleteById(
                            entity.getId()
                    );

                    removedRows++;

                    continue;
                }

                File file =
                        new File(
                                filePath
                        );

                /*
                 * Файл исчез.
                 *
                 * Удаляем stale DB row.
                 */
                if (!file.exists()
                        || !file.isFile()) {

                    recordDao.deleteById(
                            entity.getId()
                    );

                    removedRows++;

                    Log.w(
                            TAG,
                            "Удалена stale Room запись: "
                                    + filePath
                    );

                    continue;
                }

                databasePaths.add(
                        file.getAbsolutePath()
                );
            }
        }

        /*
         * =====================================================
         * FILESYSTEM → ROOM
         * =====================================================
         */

        File filesDir =
                context.getFilesDir();

        File[] files =
                filesDir.listFiles();

        if (files == null) {

            logReconciliationResult(
                    removedRows,
                    recoveredFiles
            );

            return;
        }

        for (File file : files) {

            /*
             * Reconciliation имеет право автоматически
             * восстанавливать только наши FINAL recordings.
             *
             * Никакие другие filesDir-файлы не трогаем.
             */
            if (!isManagedFinalRecording(
                    file
            )) {

                continue;
            }

            String absolutePath =
                    file.getAbsolutePath();

            if (databasePaths.contains(
                    absolutePath
            )) {

                continue;
            }

            /*
             * Дополнительная DB-проверка нужна на случай,
             * если snapshot выше уже устарел.
             */
            if (recordDao.countByFilePath(
                    absolutePath
            ) > 0) {

                databasePaths.add(
                        absolutePath
                );

                continue;
            }

            long duration =
                    readMediaDuration(
                            file
                    );

            /*
             * Повреждённый orphan автоматически
             * в библиотеку НЕ восстанавливаем.
             *
             * Сам файл при этом не удаляем:
             * recovery не должен уничтожать данные
             * без дополнительной политики.
             */
            if (duration <= 0L) {

                Log.w(
                        TAG,
                        "Orphan M4A не прошёл media validation: "
                                + absolutePath
                );

                continue;
            }

            long createdAt =
                    extractCreatedAt(
                            file
                    );

            RecordEntity recoveredEntity =
                    new RecordEntity(

                            file.getName(),

                            absolutePath,

                            createdAt,

                            file.getName(),

                            duration
                    );

            long rowId =
                    recordDao.insert(
                            recoveredEntity
                    );

            if (rowId > 0L) {

                databasePaths.add(
                        absolutePath
                );

                recoveredFiles++;

                Log.w(
                        TAG,
                        "Восстановлен orphan recording: "
                                + absolutePath
                );
            }
        }

        logReconciliationResult(
                removedRows,
                recoveredFiles
        );
    }

    /*
     * =========================================================
     * MANAGED FILE CHECK
     * =========================================================
     */

    private boolean isManagedFinalRecording(
            File file
    ) {

        if (file == null
                || !file.exists()
                || !file.isFile()) {

            return false;
        }

        Matcher matcher =
                FINAL_RECORD_PATTERN.matcher(
                        file.getName()
                );

        return matcher.matches();
    }

    /*
     * =========================================================
     * CREATED AT FROM FILE NAME
     * =========================================================
     */

    private long extractCreatedAt(
            File file
    ) {

        if (file != null) {

            Matcher matcher =
                    FINAL_RECORD_PATTERN.matcher(
                            file.getName()
                    );

            if (matcher.matches()) {

                try {

                    long timestamp =
                            Long.parseLong(
                                    matcher.group(1)
                            );

                    if (timestamp > 0L) {

                        return timestamp;
                    }

                } catch (NumberFormatException e) {

                    Log.w(
                            TAG,
                            "Не удалось получить timestamp из имени: "
                                    + file.getName(),
                            e
                    );
                }
            }

            long modified =
                    file.lastModified();

            if (modified > 0L) {

                return modified;
            }
        }

        return System.currentTimeMillis();
    }

    /*
     * =========================================================
     * MEDIA VALIDATION / DURATION
     * =========================================================
     */

    private long readMediaDuration(
            File file
    ) {

        if (file == null
                || !file.exists()
                || !file.isFile()
                || file.length() <= 0L) {

            return -1L;
        }

        MediaMetadataRetriever retriever =
                new MediaMetadataRetriever();

        try {

            retriever.setDataSource(
                    file.getAbsolutePath()
            );

            String value =
                    retriever.extractMetadata(
                            MediaMetadataRetriever
                                    .METADATA_KEY_DURATION
                    );

            if (value == null) {

                return -1L;
            }

            long duration =
                    Long.parseLong(
                            value
                    );

            return duration > 0L
                    ? duration
                    : -1L;

        } catch (Exception e) {

            Log.w(
                    TAG,
                    "Не удалось прочитать M4A metadata: "
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
                        "Ошибка MediaMetadataRetriever.release()",
                        e
                );
            }
        }
    }

    /*
     * =========================================================
     * CONFIRMED FUTURE
     * =========================================================
     *
     * Не используем timeout.
     *
     * Timeout оставил бы неопределённое состояние:
     * вызывающий поток мог бы получить failure,
     * хотя Room INSERT уже продолжает выполняться.
     *
     * Если waiting thread был interrupted,
     * дожидаемся однозначного результата,
     * а interrupt status восстанавливаем после этого.
     */
    private boolean awaitBooleanFuture(
            Future<Boolean> future,
            String operationName
    ) {

        boolean interrupted =
                false;

        try {

            while (true) {

                try {

                    return future.get();

                } catch (InterruptedException e) {

                    interrupted =
                            true;

                    Log.w(
                            TAG,
                            "Ожидание "
                                    + operationName
                                    + " было прервано; "
                                    + "дожидаемся однозначного DB результата"
                    );
                }
            }

        } catch (ExecutionException e) {

            Log.e(
                    TAG,
                    "Ошибка "
                            + operationName,
                    e.getCause() != null
                            ? e.getCause()
                            : e
            );

            return false;

        } finally {

            if (interrupted) {

                Thread.currentThread()
                        .interrupt();
            }
        }
    }

    /*
     * =========================================================
     * SAFE ASYNC EXECUTION
     * =========================================================
     */

    private void executeSafely(
            Runnable operation,
            String operationName
    ) {

        try {

            executor.execute(
                    () -> {

                        try {

                            operation.run();

                        } catch (Exception e) {

                            Log.e(
                                    TAG,
                                    "Ошибка "
                                            + operationName,
                                    e
                            );
                        }
                    }
            );

        } catch (RejectedExecutionException e) {

            Log.e(
                    TAG,
                    "Не удалось запустить "
                            + operationName
                            + ": executor остановлен",
                    e
            );
        }
    }

    /*
     * =========================================================
     * MAIN THREAD CALLBACK
     * =========================================================
     */

    private void postCallback(
            OperationCallback callback,
            boolean success
    ) {

        if (callback == null) {

            return;
        }

        mainHandler.post(
                () -> callback.onComplete(
                        success
                )
        );
    }

    /*
     * =========================================================
     * RECONCILIATION LOG
     * =========================================================
     */

    private void logReconciliationResult(
            int removedRows,
            int recoveredFiles
    ) {

        Log.d(
                TAG,
                "Storage reconciliation завершён: "
                        + "removedRows="
                        + removedRows
                        + ", recoveredFiles="
                        + recoveredFiles
        );
    }

    /*
     * =========================================================
     * SHUTDOWN
     * =========================================================
     */

    public void shutdown() {

        if (!executor.isShutdown()) {

            /*
             * shutdown(), а не shutdownNow().
             *
             * Уже поставленные consistency операции
             * должны завершиться.
             */
            executor.shutdown();
        }
    }
}