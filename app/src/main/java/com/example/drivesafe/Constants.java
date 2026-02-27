package com.example.drivesafe;

/**
 * Centralized constants for the DriveSafe app.
 * Eliminates magic numbers scattered across fragments.
 */
public final class Constants {

    private Constants() { /* Non-instantiable */ }

    // ─── SharedPreferences ───────────────────────────────────────────────
    public static final String PREFS_NAME = "DriveSafePrefs";

    // Preference keys
    public static final String KEY_ALARM_SOUND = "alarm_sound";
    public static final String KEY_ALARM_VOLUME = "alarm_volume";
    public static final String KEY_EMERGENCY_NUMBER = "emergency_number";
    public static final String KEY_PROFILE_NAME = "profile_name";
    public static final String KEY_PROFILE_IMAGE_PATH = "profile_image_path";
    public static final String KEY_DARK_MODE = "dark_mode";
    public static final String KEY_SPEED_LIMIT = "speed_limit";

    // Default values
    public static final String DEFAULT_ALARM_SOUND = "Sound 1";
    public static final float DEFAULT_ALARM_VOLUME = 100f;
    public static final float DEFAULT_SPEED_LIMIT = 80.0f;

    // ─── Eye Tracking Thresholds ─────────────────────────────────────────
    /** Eye Aspect Ratio below this value = eyes are closed */
    public static final float EAR_THRESHOLD = 0.25f;

    /** Duration (ms) of closed eyes before WARNING ("WAKE UP!") */
    public static final long WARNING_DURATION_MS = 1000L;

    /** Duration (ms) of closed eyes before CRITICAL ("PULL OVER!") */
    public static final long CRITICAL_DURATION_MS = 10_000L;

    /** Head turn angle (degrees) beyond which the driver is looking away */
    public static final float HEAD_TURN_THRESHOLD = 25f;

    /** Head tilt angle (degrees) below which the driver is nodding off */
    public static final float HEAD_TILT_THRESHOLD = -20f;

    /** Mouth-to-nose gap as fraction of face height to detect yawning */
    public static final float YAWN_RATIO = 0.35f;

    /** Duration (ms) driver must be looking away before distraction fires */
    public static final long DISTRACTION_DURATION_MS = 2000L;

    /** Window (ms) for measuring blink rate — one minute */
    public static final long BLINK_RATE_WINDOW_MS = 60_000L;

    // ─── Speed Tracking ──────────────────────────────────────────────────
    /** Minimum interval (ms) between logging speed violations to DB */
    public static final long SPEED_LOG_COOLDOWN_MS = 30_000L;

    // ─── Logging Tag ─────────────────────────────────────────────────────
    public static final String TAG = "DriveSafe";
}
