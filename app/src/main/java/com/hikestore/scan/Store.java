package com.hikestore.scan;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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

    private final File dir;

    private Store(Context c) {
        super(c, "hikescan.db", null, 2);
        dir = new File(c.getFilesDir(), "lists");
        dir.mkdirs();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE scans (cid TEXT PRIMARY KEY, code TEXT NOT NULL, raw TEXT NOT NULL, loc TEXT NOT NULL," +
                " day TEXT NOT NULL, at REAL NOT NULL, batch_id INTEGER, local_res TEXT NOT NULL, tracking TEXT," +
                " server_res TEXT, server_id INTEGER, sent INTEGER NOT NULL DEFAULT 0, tries INTEGER NOT NULL DEFAULT 0," +
                " err TEXT, username TEXT, manual INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX scans_day ON scans(loc, day)");
        db.execSQL("CREATE INDEX scans_sent ON scans(sent)");
        db.execSQL("CREATE TABLE cache (k TEXT PRIMARY KEY, v TEXT NOT NULL, at REAL NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {
        if (o < 2) db.execSQL("ALTER TABLE scans ADD COLUMN manual INTEGER NOT NULL DEFAULT 0");   // 1.4: "received by hand"
    }

    public static class Scan {
        public String cid, code, raw, loc, day, localRes, tracking, serverRes, err, user;
        public double at;
        public long batchId, serverId;
        public boolean sent, manual;
        public String res() { return serverRes != null && !serverRes.isEmpty() ? serverRes : localRes; }
    }

    public synchronized void add(Scan s) {
        ContentValues v = new ContentValues();
        v.put("cid", s.cid); v.put("code", s.code); v.put("raw", s.raw); v.put("loc", s.loc); v.put("day", s.day);
        v.put("at", s.at); if (s.batchId > 0) v.put("batch_id", s.batchId); v.put("local_res", s.localRes);
        v.put("tracking", s.tracking); v.put("sent", 0); v.put("username", s.user); v.put("manual", s.manual ? 1 : 0);
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
                s.manual = c.getInt(c.getColumnIndexOrThrow("manual")) == 1;
                out.add(s);
            }
        } finally { c.close(); }
        return out;
    }

    // ------------------------------------------------------------------ cache (list of packets etc.)
    // Big values (the day's 2,000+ packets, the day's scans) go to a FILE, not into the database:
    // Android cannot read back a database value bigger than ~2 MB ("Row too big to fit into
    // CursorWindow") - app 1.6 crashed on every reopen once the saved list grew past that.
    private static final String FILE_MARK = "@file:";

    private File fileFor(String k) {
        StringBuilder b = new StringBuilder();
        for (char ch : k.toCharArray()) b.append(Character.isLetterOrDigit(ch) || ch == '-' ? ch : '_');
        return new File(dir, b.toString() + ".json");
    }

    public synchronized void put(String k, String v) {
        String stored = v;
        if (v != null && v.length() > 100000) {
            File f = fileFor(k), tmp = new File(f.getPath() + ".tmp");
            try {
                FileOutputStream o = new FileOutputStream(tmp);
                o.write(v.getBytes("UTF-8"));
                o.close();
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
                stored = FILE_MARK + f.getName();
            } catch (Exception e) { tmp.delete(); return; }      // not saved: the server sends it again
        } else {
            fileFor(k).delete();
        }
        ContentValues cv = new ContentValues();
        cv.put("k", k); cv.put("v", stored); cv.put("at", System.currentTimeMillis() / 1000.0);
        getWritableDatabase().insertWithOnConflict("cache", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized String getCache(String k) {
        String v;
        try {
            Cursor c = getReadableDatabase().rawQuery("SELECT v FROM cache WHERE k=?", new String[]{k});
            try { v = c.moveToFirst() ? c.getString(0) : null; } finally { c.close(); }
        } catch (Throwable e) {            // a too-big value saved by app 1.6: drop it, the server sends the list again
            try { getWritableDatabase().delete("cache", "k=?", new String[]{k}); } catch (Throwable ignored) { }
            return null;
        }
        if (v == null || !v.startsWith(FILE_MARK)) return v;
        File f = new File(dir, v.substring(FILE_MARK.length()));
        try {
            FileInputStream in = new FileInputStream(f);
            try { return Api.readAll(in); } finally { in.close(); }
        } catch (Throwable e) { return null; }
    }

    /** the saved lists of days older than 15 days are removed (scans themselves are kept by cleanOld) */
    public synchronized void cleanCache(double beforeEpoch) {
        try {
            Cursor c = getReadableDatabase().rawQuery("SELECT k, v FROM cache WHERE at<?", new String[]{String.valueOf(beforeEpoch)});
            try {
                while (c.moveToNext()) {
                    String v = null;
                    try { v = c.getString(1); } catch (Throwable ignored) { }
                    if (v != null && v.startsWith(FILE_MARK)) new File(dir, v.substring(FILE_MARK.length())).delete();
                }
            } finally { c.close(); }
        } catch (Throwable ignored) { }
        try { getWritableDatabase().delete("cache", "at<?", new String[]{String.valueOf(beforeEpoch)}); } catch (Throwable ignored) { }
    }
}
