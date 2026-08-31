package com.nicko.airecorder.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface RecordDao {

    /*
     * =========================================================
     * INSERT
     * =========================================================
     *
     * Room возвращает реальный rowId.
     *
     * Это позволяет вызывающему коду понять,
     * что INSERT действительно завершился.
     */
    @Insert
    long insert(
            RecordEntity record
    );

    /*
     * =========================================================
     * DELETE BY ID
     * =========================================================
     *
     * Возвращается количество удалённых строк.
     *
     * 0 — строки уже не было.
     * 1 — строка была удалена.
     */
    @Query(
            "DELETE FROM records " +
                    "WHERE id = :id"
    )
    int deleteById(
            long id
    );

    /*
     * =========================================================
     * DELETE BY FILE PATH
     * =========================================================
     *
     * Используется consistency/recovery механизмом.
     */
    @Query(
            "DELETE FROM records " +
                    "WHERE filePath = :filePath"
    )
    int deleteByFilePath(
            String filePath
    );

    /*
     * =========================================================
     * RENAME
     * =========================================================
     */

    @Query(
            "UPDATE records " +
                    "SET title = :title " +
                    "WHERE id = :id"
    )
    void rename(
            long id,
            String title
    );

    /*
     * =========================================================
     * OBSERVABLE LIST
     * =========================================================
     */

    @Query(
            "SELECT * FROM records " +
                    "ORDER BY createdAt DESC"
    )
    LiveData<List<RecordEntity>> getAll();

    /*
     * =========================================================
     * SYNCHRONOUS SNAPSHOT
     * =========================================================
     *
     * Вызывается ТОЛЬКО с Repository executor.
     *
     * Нужен для filesystem ↔ Room reconciliation.
     */
    @Query(
            "SELECT * FROM records " +
                    "ORDER BY createdAt DESC"
    )
    List<RecordEntity> getAllSync();

    /*
     * =========================================================
     * FILE PATH LOOKUP
     * =========================================================
     *
     * Не требует schema migration.
     *
     * Проверяем наличие записи перед восстановлением
     * orphan .m4a и перед confirmed insert.
     */
    @Query(
            "SELECT COUNT(*) FROM records " +
                    "WHERE filePath = :filePath"
    )
    int countByFilePath(
            String filePath
    );
}