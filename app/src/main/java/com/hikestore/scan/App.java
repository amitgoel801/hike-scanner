package com.hikestore.scan;

import android.app.Application;

/** starts the crash / freeze recorder before any screen opens */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Crash.install(this);
    }
}
