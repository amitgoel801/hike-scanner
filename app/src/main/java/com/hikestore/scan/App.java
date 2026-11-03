package com.hikestore.scan;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

/**
 * Starts before any screen:
 *  1. the crash / freeze recorder;
 *  2. SELF-REPAIR: if the app closed during the last start (crash, or killed before it ran 20 s),
 *     the list kept on the phone is thrown away and downloaded again - scans and login are kept.
 *     (Uninstall + install "fixed" it before for the same reason, but lost unsent scans.)
 *  3. sends the problem report to HikeOne straight away.
 */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Crash.install(this);
        final SharedPreferences sp = getSharedPreferences("boot", MODE_PRIVATE);
        boolean bad = sp.getBoolean("crashed", false) || sp.getBoolean("starting", false);
        if (bad) {
            try { Store.get(this).clearLists(); } catch (Throwable ignored) { }
            Crash.save("Safe start", "The app closed during the last start: the list saved on the phone was cleared and is " +
                    "downloaded again. Scans and login are kept.");
        }
        sp.edit().putBoolean("crashed", false).putBoolean("starting", true).commit();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() { sp.edit().putBoolean("starting", false).apply(); }   // ran fine for 20 s
        }, 20000);
        try { Api.init(this); Crash.sendPending(); } catch (Throwable ignored) { }
    }
}
