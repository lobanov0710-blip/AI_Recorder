package com.nicko.airecorder.model;

public class RecordItem {

    private final long id;
    private final String fileName;
    private final String filePath;
    private final long createdAt;
    private final long duration;
    private final String title;

    public RecordItem(
            long id,
            String fileName,
            String filePath,
            long createdAt,
            String title,
            long duration
    ) {
        this.id = id;
        this.fileName = fileName;
        this.filePath = filePath;
        this.createdAt = createdAt;
        this.title = title;
        this.duration = duration;
    }

    public long getId() {
        return id;
    }
    public long getDuration() {
        return duration;
    }
    public String getFileName() {
        return fileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public String getTitle() {
        return title;
    }

    @Override
    public boolean equals(Object obj) {

        if (this == obj) {
            return true;
        }

        if (!(obj instanceof RecordItem)) {
            return false;
        }

        RecordItem other = (RecordItem) obj;

        return id == other.id
                && createdAt == other.createdAt
                && duration == other.duration
                && java.util.Objects.equals(fileName, other.fileName)
                && java.util.Objects.equals(filePath, other.filePath)
                && java.util.Objects.equals(title, other.title);
    }

    @Override
    public int hashCode() {

        return java.util.Objects.hash(
                id,
                fileName,
                filePath,
                createdAt,
                duration,
                title
        );
    }
}