package com.example.drivesafe; // Make sure this matches your package

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DatabaseHelper extends SQLiteOpenHelper {

    // Database Name and Version
    private static final String DATABASE_NAME = "SafeDriveLogs.db";
    private static final int DATABASE_VERSION = 1;

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // Create table to store fatigue and speed alerts
        db.execSQL("CREATE TABLE Logs (ID INTEGER PRIMARY KEY AUTOINCREMENT, TYPE TEXT, VALUE TEXT, TIME DATETIME DEFAULT CURRENT_TIMESTAMP)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS Logs");
        onCreate(db);
    }

    // Method to add a new log entry
    public void addLog(String type, String value) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("TYPE", type);
        values.put("VALUE", value);
        db.insert("Logs", null, values);
        db.close();
    }
}