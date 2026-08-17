package com.nicko.airecorder.common;

public final class RecordActions {

    private RecordActions() {
    }

    public static final String ACTION_START =
            "START_RECORD";

    public static final String ACTION_STOP =
            "STOP_RECORD";

    public static final String ACTION_PAUSE =
            "PAUSE_RECORD";

    public static final String ACTION_RESUME =
            "RESUME_RECORD";

    public static final String ACTION_RECORD_STARTED =
            "RECORD_STARTED";

    public static final String ACTION_RECORD_STOPPED =
            "RECORD_STOPPED";

    public static final String ACTION_RECORD_PAUSED =
            "RECORD_PAUSED";

    public static final String ACTION_RECORD_RESUMED =
            "RECORD_RESUMED";

    public static final String ACTION_RECORD_TIME =
            "RECORD_TIME";

    public static final String ACTION_RECORD_AMPLITUDE =
            "com.nicko.airecorder.ACTION_RECORD_AMPLITUDE";

    public static final String EXTRA_RECORD_DURATION =
            "duration";

    public static final String EXTRA_RECORD_AMPLITUDE =
            "amplitude";

}
