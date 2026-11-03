package com.hikestore.scan;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes down WHY the app closed or froze, so it can be fixed: a crash (with the exact line) or the
 * screen thread being stuck for 8+ seconds (with what it was doing). Saved on the phone and sent to
 * HikeOne (Return scanning > Settings > Worker devices > App problems) the next time the app opens.
 */
final class Crash {
    private static File file;
    private static boolean on;
    private static Context app;
    private static volatile long pong;

    private Crash() { }

    static synchronized void install(Context c) {
        if (on) return;
        on = true;
        app = c.getApplicationContext();
        file = new File(app.getFilesDir(), "problem.txt");
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try { save("The app closed (crash) - thread " + t.getName(), Log.getStackTraceString(e)); } catch (Throwable ignored) { }
                try { app.getSharedPreferences("boot", Context.MODE_PRIVATE).edit().putBoolean("crashed", true).commit(); } catch (Throwable ignored) { }
                if (prev != null) prev.uncaughtException(t, e);
                else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10); }
            }
        });
        watchdog();
    }

    /** checks every 2 s that the screen thread answers; if it is stuck 8 s, writes down what it is doing */
    private static void watchdog() {
        final Handler main = new Handler(Looper.getMainLooper());
        final Runnable ping = new Runnable() { @Override public void run() { pong = SystemClock.uptimeMillis(); } };
        Thread w = new Thread(new Runnable() {
            @Override public void run() {
                long reported = -600000;
                while (true) {
                    pong = 0;
                    main.post(ping);
                    if (!nap(2000)) return;
                    if (pong != 0) continue;
                    int waited = 2000;
                    while (pong == 0 && waited < 8000) { if (!nap(500)) return; waited += 500; }
                    if (pong == 0 && SystemClock.uptimeMillis() - reported > 600000) {
                        reported = SystemClock.uptimeMillis();
                        StringBuilder sb = new StringBuilder();
                        for (StackTraceElement el : Looper.getMainLooper().getThread().getStackTrace()) sb.append("  at ").append(el).append('\n');
                        save("The screen froze for 8+ seconds", sb.toString());
                    }
                    while (pong == 0) if (!nap(500)) return;
                }
            }
        }, "hike-watchdog");
        w.setDaemon(true);
        w.start();
    }

    private static boolean nap(long ms) {
        try { Thread.sleep(ms); return true; } catch (InterruptedException e) { return false; }
    }

    static synchronized void save(String what, String detail) {
        if (file == null) return;
        try {
            Runtime rt = Runtime.getRuntime();
            String head = new SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.US).format(new Date()) + " · app " + AppInfo.VERSION_NAME +
                    " · " + Build.MANUFACTURER + " " + Build.MODEL + " Android " + Build.VERSION.RELEASE +
                    " · memory " + (rt.totalMemory() - rt.freeMemory()) / 1048576 + " of " + rt.maxMemory() / 1048576 + " MB\n";
            String text = head + what + "\n" + detail + "\n\n";
            String old = read();
            if (old != null && old.length() > 12000) old = old.substring(0, 12000);
            FileOutputStream o = new FileOutputStream(file);
            o.write(((old == null ? "" : old) + text).getBytes("UTF-8"));
            o.close();
        } catch (Throwable ignored) { }
    }

    /** the last report that was already sent (shown on the home screen until "OK") */
    static synchronized String lastSent() {
        try {
            if (file == null) return null;
            File f = new File(file.getParentFile(), "problem-sent.txt");
            if (!f.exists() || f.length() == 0) return null;
            FileInputStream in = new FileInputStream(f);
            String s = Api.readAll(in);
            in.close();
            return s;
        } catch (Throwable e) { return null; }
    }

    static synchronized void dismissSent() {
        if (file != null) new File(file.getParentFile(), "problem-sent.txt").delete();
    }

    static synchronized String read() {
        try {
            if (file == null || !file.exists() || file.length() == 0) return null;
            FileInputStream in = new FileInputStream(file);
            String s = Api.readAll(in);
            in.close();
            return s;
        } catch (Throwable e) { return null; }
    }

    static String pending() { return read(); }

    /** first line that says what happened (for the home screen) */
    static String headline() {
        String t = read();
        if (t == null) return null;
        String last = null;
        for (String ln : t.split("\n")) if (ln.startsWith("The app closed") || ln.startsWith("The screen froze") || ln.startsWith("Safe start")) last = ln;
        return last;
    }

    static synchronized void clear() { if (file != null) file.delete(); }

    /** background: send what was written down; deleted once HikeOne has it */
    static void sendPending() {
        final String text = read();
        if (text == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject a = new JSONObject();
                    a.put("text", text);
                    a.put("version", AppInfo.VERSION_NAME);
                    a.put("device", Build.MANUFACTURER + " " + Build.MODEL);
                    JSONObject r = Api.post("/api/app_log", a);
                    if (r.optBoolean("ok")) synchronized (Crash.class) {
                        if (text.equals(read())) {
                            File sent = new File(file.getParentFile(), "problem-sent.txt");
                            sent.delete();
                            file.renameTo(sent);
                        }
                    }
                } catch (Throwable ignored) { }
            }
        }, "hike-report").start();
    }
}
