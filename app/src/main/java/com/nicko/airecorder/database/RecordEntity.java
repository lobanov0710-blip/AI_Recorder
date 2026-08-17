package com.nicko.airecorder.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "records")
public class RecordEntity {

    @PrimaryKey(autoGenerate = true)
    private long id;

    private String fileName;

    private String filePath;

    private long createdAt;

    private long duration;

    private String title;

    public RecordEntity(

            String fileName,

            String filePath,

            long createdAt,

            String title,

            long duration

    ) {

        this.fileName = fileName;

        this.filePath = filePath;

        this.createdAt = createdAt;

        this.title = title;

        this.duration = duration;

    }

    public long getId() {

        return id;

    }

    public void setId(long id) {

        this.id = id;

    }

    public String getFileName() {

        return fileName;

    }

    public void setFileName(String fileName) {

        this.fileName = fileName;

    }

    public String getFilePath() {

        return filePath;

    }

    public void setFilePath(String filePath) {

        this.filePath = filePath;

    }

    public long getCreatedAt() {

        return createdAt;

    }

    public void setCreatedAt(long createdAt) {

        this.createdAt = createdAt;

    }

    public long getDuration() {

        return duration;

    }

    public void setDuration(long duration) {

        this.duration = duration;

    }

    public String getTitle() {

        return title;

    }

    public void setTitle(String title) {

        this.title = title;

    }

}