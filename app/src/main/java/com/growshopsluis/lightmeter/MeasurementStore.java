package com.growshopsluis.lightmeter;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public final class MeasurementStore extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "light_measurements.db";
    private static final int DATABASE_VERSION = 1;

    public MeasurementStore(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE measurements ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "measured_at INTEGER NOT NULL,"
                + "lamp_name TEXT NOT NULL,"
                + "camera_name TEXT NOT NULL,"
                + "light_source TEXT NOT NULL,"
                + "preset TEXT NOT NULL,"
                + "lux REAL NOT NULL,"
                + "ppfd REAL NOT NULL,"
                + "lumens REAL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 has no migrations.
    }

    public void add(Measurement measurement) {
        ContentValues values = new ContentValues();
        values.put("measured_at", measurement.measuredAt);
        values.put("lamp_name", measurement.lampName);
        values.put("camera_name", measurement.cameraName);
        values.put("light_source", measurement.lightSource);
        values.put("preset", measurement.preset);
        values.put("lux", measurement.lux);
        values.put("ppfd", measurement.ppfd);
        if (measurement.lumens == null) values.putNull("lumens");
        else values.put("lumens", measurement.lumens);
        getWritableDatabase().insertOrThrow("measurements", null, values);
    }

    public List<Measurement> recent(int limit) {
        List<Measurement> measurements = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "measurements", null, null, null, null, null,
                "measured_at DESC", Integer.toString(limit))) {
            int measuredAt = cursor.getColumnIndexOrThrow("measured_at");
            int lampName = cursor.getColumnIndexOrThrow("lamp_name");
            int cameraName = cursor.getColumnIndexOrThrow("camera_name");
            int lightSource = cursor.getColumnIndexOrThrow("light_source");
            int preset = cursor.getColumnIndexOrThrow("preset");
            int lux = cursor.getColumnIndexOrThrow("lux");
            int ppfd = cursor.getColumnIndexOrThrow("ppfd");
            int lumens = cursor.getColumnIndexOrThrow("lumens");
            while (cursor.moveToNext()) {
                measurements.add(new Measurement(
                        cursor.getLong(measuredAt), cursor.getString(lampName),
                        cursor.getString(cameraName), cursor.getString(lightSource),
                        cursor.getString(preset), cursor.getDouble(lux), cursor.getDouble(ppfd),
                        cursor.isNull(lumens) ? null : cursor.getDouble(lumens)));
            }
        }
        return measurements;
    }

    public void clear() {
        getWritableDatabase().delete("measurements", null, null);
    }

    public static final class Measurement {
        public final long measuredAt;
        public final String lampName;
        public final String cameraName;
        public final String lightSource;
        public final String preset;
        public final double lux;
        public final double ppfd;
        public final Double lumens;

        public Measurement(long measuredAt, String lampName, String cameraName,
                String lightSource, String preset, double lux, double ppfd, Double lumens) {
            this.measuredAt = measuredAt;
            this.lampName = lampName;
            this.cameraName = cameraName;
            this.lightSource = lightSource;
            this.preset = preset;
            this.lux = lux;
            this.ppfd = ppfd;
            this.lumens = lumens;
        }
    }
}
