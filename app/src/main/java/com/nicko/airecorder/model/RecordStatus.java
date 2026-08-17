package com.nicko.airecorder.model;

public class RecordStatus {

    private final boolean recording;

    private final long duration;

    public RecordStatus(

            boolean recording,

            long duration

    ){

        this.recording = recording;

        this.duration = duration;
    }
    public boolean isRecording(){
        return recording;
    }
    public long getDuration(){
        return duration;
    }
}