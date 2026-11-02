package com.hikestore.scan;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * On-phone database. Every scan is written here FIRST (before any sound or network),
 * so nothing is lost if the app is closed, the phone switches off or the network drops.
 * A scan stays "unsent" until the server confirmed it; the server ignores a scan it
 * already has (same client id), so sending again is always safe.
 */
public final class Store extends SQLiteOpenHelper {
    private static Store inst;

    public static synchronized Store get(Context c) {
        if (inst == null) inst = new Store(c.getApplicationContext());
        return inst;
    }

    private Store(Context c) { super(c, "hikescan.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE scans (cid TEXT PRIMARY KEY, code TEXT NOT NULL, raw TEXT NOT NULL, loc TEXT NOT NULL," +
                " day TEXT NOT NULL, at REAL NOT NULL, batch_id INTEGER, local_res TEXT NOT NULL, tracking TEXT," +
                " server_res TEXT, server_id INTEGER, sent INTEGER NOT NULL DEFAULT 0, tries INTEGER NOT NULL DEFAULT 0," +
                " err TEXT, username TEXT)");
        db.execSQL("CREATE INDEX scans_day ON scans(loc, day)");
        db.execSQL("CREATE INDEX scans_sent ON scans(sent)");
        db.execSQL("CREATE TABLE cache (k TEXT PRIMARY KEY, v TEXT NOT NULL, at REAL NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {}

    public static class Scan {
        public String cid, code, raw, loc, day, localRes, tracking, serverRes, err, user;
        public double at;
        public long batchId, serverId;
        public boolean sent;
        public String res() { return serverRes != null && !serverRes.isEmpty() ? serverRes : localRes; }
    }

    public synchronized void add(Scan s) {
        ContentValues v = new ContentValues();
        v.put("cid", s.cid); v.put("code", s.code); v.put("raw", s.raw); v.put("loc", s.loc); v.put("day", s.day);
        v.put("at", s.at); if (s.batchId > 0) v.put("batch_id", s.batchId); v.put("local_res", s.localRes);
        v.put("tracking", s.tracking); v.put("sent", 0); v.put("username", s.user);
        getWritableDatabase().insertOrThrow("scans", null, v);
    }

    public synchronized void markSent(String cid, String res, long serverId, String tracking) {
        ContentValues v = new ContentValues();
        v.put("sent", 1); v.put("server_res", res); v.put("server_id", serverId); v.put("err", (String) null);
        if (tracking != null && !tracking.isEmpty()) v.put("tracking", tracking);
        getWritableDatabase().update("scans", v, "cid=?", new String[]{cid});
    }

    public synchronized void markError(String cid, String err) {
        getWritableDatabase().execSQL("UPDATE scans SET tries=tries+1, err=? WHERE cid=?", new Object[]{err, cid});
    }

    /** Undo: the server moved it to its trash; here it is kept but flagged. */
    public synchronized void markUndone(String cid) {
        ContentValues v = new ContentValues();
        v.put("server_res", "undone");
        v.put("sent", 1);
        getWritableDatabase().update("scans", v, "cid=?", new String[]{cid});
    }

    public synchronized List<Scan> unsent(int limit) {
        return query("sent=0", null, "at ASC", limit);
    }

    public synchronized int unsentCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT count(*) FROM scans WHERE sent=0", null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    public synchronized List<Scan> forDay(String loc, String day) {
        return query("loc=? AND day=?", new String[]{loc, day}, "at ASC", 20000);
    }

    /** All scans of a day that are not marked as sent - also used for "send again". */
    public synchronized int resendDay(String loc, String day) {
        ContentValues v = new ContentValues();
        v.put("sent", 0);
        return getWritableDatabase().update("scans", v, "loc=? AND day=? AND (server_res IS NULL OR server_res<>'undone')",
                new String[]{loc, day});
    }

    public synchronized void cleanOld(double beforeEpoch) {
        getWritableDatabase().delete("scans", "sent=1 AND at<?", new String[]{String.valueOf(beforeEpoch)});
    }

    private List<Scan> query(String where, String[] args, String order, int limit) {
        List<Scan> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("scans", null, where, args, null, null, order, String.valueOf(limit));
        try {
            while (c.moveToNext()) {
                Scan s = new Scan();
                s.cid = c.getString(c.getColumnIndexOrThrow("cid"));
                s.code = c.getString(c.getColumnIndexOrThrow("code"));
                s.raw = c.getString(c.getColumnIndexOrThrow("raw"));
                s.loc = c.getString(c.getColumnIndexOrThrow("loc"));
                s.day = c.getString(c.getColumnIndexOrThrow("day"));
                s.at = c.getDouble(c.getColumnIndexOrThrow("at"));
                s.batchId = c.isNull(c.getColumnIndexOrThrow("batch_id")) ? 0 : c.getLong(c.getColumnIndexOrThrow("batch_id"));
                s.localRes = c.getString(c.getColumnIndexOrThrow("local_res"));
                s.tracking = c.getString(c.getColumnIndexOrThrow("tracking"));
                s.serverRes = c.getString(c.getColumnIndexOrThrow("server_res"));
                s.serverId = c.isNull(c.getColumnIndexOrThrow("server_id")) ? 0 : c.getLong(c.getColumnIndexOrThrow("server_id"));
                s.sent = c.getInt(c.getColumnIndexOrThrow("sent")) == 1;
                s.err = c.getString(c.getColumnIndexOrThrow("err"));
                s.user = c.getString(c.getColumnIndexOrThrow("username"));
                out.add(s);
            }
        } finally { c.close(); }
        return out;
    }

    // ------------------------------------------------------------------ small cache (list of packets etc.)
    public synchronized void put(String k, String v) {
        ContentValues cv = new ContentValues();
        cv.put("k", k); cv.put("v", v); cv.put("at", System.currentTimeMillis() / 1000.0);
        getWritableDatabase().insertWithOnConflict("cache", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized String getCache(String k) {
        Cursor c = getReadableDatabase().rawQuery("SELECT v FROM cache WHERE k=?", new String[]{k});
        try { return c.moveToFirst() ? c.getString(0) : null; } finally { c.close(); }
    }
}
