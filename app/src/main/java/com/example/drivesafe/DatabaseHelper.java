package com.example.drivesafe;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import android.util.Log;

public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "SafeDriveLogs.db";
    // Version 3: fix session insert + clean rebuild
    private static final int DATABASE_VERSION = 3;

    private static DatabaseHelper sInstance;

    /** Use this to get a shared instance — avoids multiple helpers conflicting. */
    public static synchronized DatabaseHelper getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new DatabaseHelper(context.getApplicationContext());
        }
        return sInstance;
    }

    private DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // A drive session = one START MONITORING → STOP MONITORING cycle
        db.execSQL("CREATE TABLE Sessions (" +
                "ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "START_TIME DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                "END_TIME DATETIME, " +
                "DURATION_SECONDS INTEGER DEFAULT 0, " +
                "FATIGUE_WARNING_COUNT INTEGER DEFAULT 0, " +
                "FATIGUE_CRITICAL_COUNT INTEGER DEFAULT 0, " +
                "BLINK_COUNT INTEGER DEFAULT 0)");

        // Speed limit violations logged independently (GPS always running)
        db.execSQL("CREATE TABLE SpeedAlerts (" +
                "ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "TIME DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                "SPEED_KMH REAL, " +
                "LIMIT_KMH REAL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Drop everything and recreate cleanly
        db.execSQL("DROP TABLE IF EXISTS Logs");
        db.execSQL("DROP TABLE IF EXISTS Sessions");
        db.execSQL("DROP TABLE IF EXISTS SpeedAlerts");
        onCreate(db);
    }

    // ─── SESSION METHODS ─────────────────────────────────────────────────────

    /**
     * Call when the user presses START MONITORING.
     * Returns the new session's ID to track throughout the session.
     */
    public long startSession() {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("START_TIME", currentTimestamp());
        long id = db.insert("Sessions", null, values);
        Log.d("DriveSafe-DB", "startSession() inserted row ID=" + id);
        return id;
    }

    /**
     * Call when the user presses STOP MONITORING or the fragment is destroyed.
     * Finalises the session with all accumulated stats.
     */
    public void endSession(long sessionId, int durationSeconds,
                           int warningCount, int criticalCount, int blinkCount) {
        if (sessionId < 0) return;
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("END_TIME", currentTimestamp());
        values.put("DURATION_SECONDS", durationSeconds);
        values.put("FATIGUE_WARNING_COUNT", warningCount);
        values.put("FATIGUE_CRITICAL_COUNT", criticalCount);
        values.put("BLINK_COUNT", blinkCount);
        db.update("Sessions", values, "ID = ?", new String[]{String.valueOf(sessionId)});
        Log.d("DriveSafe-DB", "endSession() updated ID=" + sessionId
                + " duration=" + durationSeconds + "s warn=" + warningCount
                + " crit=" + criticalCount + " blinks=" + blinkCount);
    }

    /** Returns all completed sessions, newest first. */
    public List<Session> getAllSessions() {
        List<Session> sessions = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(
                    "SELECT ID, START_TIME, END_TIME, DURATION_SECONDS, " +
                    "FATIGUE_WARNING_COUNT, FATIGUE_CRITICAL_COUNT, BLINK_COUNT " +
                    "FROM Sessions WHERE END_TIME IS NOT NULL ORDER BY ID DESC", null);
            Log.d("DriveSafe-DB", "getAllSessions() query returned " + cursor.getCount() + " rows");
            while (cursor.moveToNext()) {
                Session s = new Session();
                s.id = cursor.getLong(0);
                s.startTime = cursor.getString(1);
                s.endTime = cursor.getString(2);
                s.durationSeconds = cursor.getInt(3);
                s.fatigueWarningCount = cursor.getInt(4);
                s.fatigueCriticalCount = cursor.getInt(5);
                s.blinkCount = cursor.getInt(6);
                sessions.add(s);
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        return sessions;
    }

    // ─── SPEED ALERT METHODS ─────────────────────────────────────────────────

    /** Log a speed limit violation. */
    public void addSpeedAlert(float speedKmh, float limitKmh) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("SPEED_KMH", speedKmh);
        values.put("LIMIT_KMH", limitKmh);
        db.insert("SpeedAlerts", null, values);
    }

    /** Returns recent speed alerts (up to 50), newest first. */
    public List<SpeedAlert> getSpeedAlerts() {
        List<SpeedAlert> alerts = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(
                    "SELECT TIME, SPEED_KMH, LIMIT_KMH FROM SpeedAlerts " +
                    "ORDER BY ID DESC LIMIT 50", null);
            while (cursor.moveToNext()) {
                SpeedAlert a = new SpeedAlert();
                a.time = cursor.getString(0);
                a.speedKmh = cursor.getFloat(1);
                a.limitKmh = cursor.getFloat(2);
                alerts.add(a);
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        return alerts;
    }

    // ─── UTILITY ─────────────────────────────────────────────────────────────

    public void clearAll() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete("Sessions", null, null);
        db.delete("SpeedAlerts", null, null);
    }

    private String currentTimestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date());
    }

    // ─── DATA CLASSES ────────────────────────────────────────────────────────

    public static class Session {
        public long id;
        public String startTime;   // "yyyy-MM-dd HH:mm:ss"
        public String endTime;
        public int durationSeconds;
        public int fatigueWarningCount;
        public int fatigueCriticalCount;
        public int blinkCount;

        /** Human-readable duration, e.g. "12 min 5 sec" */
        public String formattedDuration() {
            if (durationSeconds < 60)
                return durationSeconds + " sec";
            int m = durationSeconds / 60;
            int s = durationSeconds % 60;
            if (m < 60)
                return m + " min" + (s > 0 ? " " + s + " sec" : "");
            int h = m / 60;
            int rem = m % 60;
            return h + " hr" + (rem > 0 ? " " + rem + " min" : "");
        }

        /** Human-readable date+time from the SQLite timestamp. */
        public String formattedStartTime() {
            return formatSqliteDateTime(startTime);
        }

        /** Verdict label + colour for the session summary. */
        public String verdict() {
            if (fatigueCriticalCount > 0) return "Severe drowsiness detected";
            if (fatigueWarningCount > 0)  return "Mild drowsiness detected";
            return "Attentive — great drive!";
        }

        public int verdictColor() {
            if (fatigueCriticalCount > 0) return 0xFFFF4444;
            if (fatigueWarningCount > 0)  return 0xFFFFAA00;
            return 0xFF00FF99;
        }
    }

    public static class SpeedAlert {
        public String time;
        public float speedKmh;
        public float limitKmh;

        public String formattedTime() {
            return formatSqliteDateTime(time);
        }
    }

    private static String formatSqliteDateTime(String sqlTs) {
        if (sqlTs == null) return "";
        try {
            SimpleDateFormat in  = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            SimpleDateFormat out = new SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault());
            Date d = in.parse(sqlTs);
            return d != null ? out.format(d) : sqlTs;
        } catch (ParseException e) {
            return sqlTs;
        }
    }
}
