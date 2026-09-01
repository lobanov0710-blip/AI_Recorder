package com.nicko.airecorder.common;

public final class RecordActions {

    /*
     * =========================================================
     * NAMESPACE
     * =========================================================
     *
     * Broadcast action namespace глобален в Android.
     *
     * Поэтому все собственные actions должны находиться
     * в namespace нашего applicationId.
     */

    private static final String ACTION_PREFIX =
            "com.nicko.airecorder.action.";

    private static final String EXTRA_PREFIX =
            "com.nicko.airecorder.extra.";

    private RecordActions() {
    }

    /*
     * =========================================================
     * SERVICE COMMANDS
     * =========================================================
     */

    public static final String ACTION_START =
            ACTION_PREFIX
                    + "START_RECORD";

    public static final String ACTION_STOP =
            ACTION_PREFIX
                    + "STOP_RECORD";

    public static final String ACTION_PAUSE =
            ACTION_PREFIX
                    + "PAUSE_RECORD";

    public static final String ACTION_RESUME =
            ACTION_PREFIX
                    + "RESUME_RECORD";

    public static final String ACTION_REQUEST_STATE =
            ACTION_PREFIX
                    + "REQUEST_RECORD_STATE";

    /*
     * =========================================================
     * SERVICE → UI EVENTS
     * =========================================================
     */

    public static final String ACTION_RECORD_STARTED =
            ACTION_PREFIX
                    + "RECORD_STARTED";

    public static final String ACTION_RECORD_STOPPED =
            ACTION_PREFIX
                    + "RECORD_STOPPED";

    public static final String ACTION_RECORD_PAUSED =
            ACTION_PREFIX
                    + "RECORD_PAUSED";

    public static final String ACTION_RECORD_RESUMED =
            ACTION_PREFIX
                    + "RECORD_RESUMED";

    public static final String ACTION_RECORD_TIME =
            ACTION_PREFIX
                    + "RECORD_TIME";

    public static final String ACTION_RECORD_AMPLITUDE =
            ACTION_PREFIX
                    + "RECORD_AMPLITUDE";

    /*
     * =========================================================
     * EXTRAS
     * =========================================================
     */

    public static final String EXTRA_RECORD_DURATION =
            EXTRA_PREFIX
                    + "RECORD_DURATION";

    public static final String EXTRA_RECORD_AMPLITUDE =
            EXTRA_PREFIX
                    + "RECORD_AMPLITUDE";
}