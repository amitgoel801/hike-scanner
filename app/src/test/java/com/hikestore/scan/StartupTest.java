package com.hikestore.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Opens the app on a simulated Android phone the way the scanner phones are used:
 * already connected (logged in), with today's 2,400+ packet list and thousands of scans saved on
 * the phone - including the oversized saved copy that app 1.6 left behind.
 */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class StartupTest {
    private static final String LOC = "LOC07bff30bef5a4d82ae4b8cbe1968bceb";
    private Context ctx;
    static final java.util.List<String> CRASHES = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());

    @org.junit.After public void noCrash() throws Exception {
        waitIdle(300);
        assertTrue("app crashed: " + CRASHES, CRASHES.isEmpty());
    }
    private String day;

    private static void resetStatic(Class<?> c, String name, Object v) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, v);
    }

    @Before public void setUp() throws Exception {
        ctx = RuntimeEnvironment.getApplication();
        // every test is a fresh phone: forget what the previous test's app kept in memory
        resetStatic(Api.class, "prefs", null);
        resetStatic(Store.class, "inst", null);
        resetStatic(Images.class, "dir", null);
        resetStatic(Crash.class, "on", false);
        Crash.install(ctx);
        // a crash in any thread: print it in full and fail the test (instead of the app's handler ending the test run)
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                System.out.println("APP CRASH in thread '" + t.getName() + "':");
                e.printStackTrace(System.out);
                CRASHES.add(t.getName() + ": " + e);
            }
        });
        CRASHES.clear();
        JSONObject me = new JSONObject();
        me.put("user", "📱 Test phone");
        me.put("default_location", LOC);
        JSONArray locs = new JSONArray();
        locs.put(new JSONObject().put("id", LOC).put("name", "Hike Pasina"));
        me.put("locations", locs);
        me.put("ui", new JSONObject());
        ctx.getSharedPreferences("api", Context.MODE_PRIVATE).edit()
                .putString("server", "https://127.0.0.1:9")          // no server: every call fails fast
                .putString("device_token", "test-token").putString("device_name", "Test phone")
                .putString("csrf", "x").putString("me", me.toString()).commit();
        Api.init(ctx);
        day = Ui.today();
    }

    static JSONObject bigState(int packets, int scans) throws Exception {
        JSONArray p = new JSONArray();
        for (int k = 0; k < packets; k++) {
            JSONObject o = new JSONObject();
            o.put("t", String.format("FMPR%010d", k)).put("r", "1026525985018347243835" + k).put("o", "OD33898907694221" + k)
                    .put("ty", k % 3 == 0 ? "customer_return" : "courier_return").put("d", "2026-11-03").put("bg", "EDELPNI-6623" + (k / 30));
            JSONArray its = new JSONArray();
            its.put(new JSONObject().put("oi", "3389890769422" + k).put("sku", "RTHULKBPINKKBRV-" + (k % 9))
                    .put("ti", "RED TAPE Boys & Girls Sling Back Clogs size " + k)
                    .put("im", "https://rukminim2.flixcart.com/image/128/128/xif0q/sandal/a/b/c/" + k + "-rtstylerkbrv-6-red-tape-light-grey-original-imahqehrzdnqyntt.jpeg")
                    .put("q", 1).put("re", "order_cancelled").put("sr", "mind_changed").put("pr", 736.0).put("fsn", "SNDHFMY7TVSHAMER"));
            o.put("i", its);
            p.put(o);
        }
        JSONArray sc = new JSONArray();
        for (int k = 0; k < scans; k++)
            sc.put(new JSONObject().put("id", k + 1).put("code", String.format("FMPR%010d", k)).put("t", String.format("FMPR%010d", k))
                    .put("res", "ok").put("at", 1793690000.0 + k).put("by", "📱 Scanner 2").put("bid", 1).put("cid", "dev-" + k).put("note", ""));
        JSONObject st = new JSONObject();
        st.put("ok", true).put("psig", "sig1").put("packets", p).put("scans", sc)
                .put("batches", new JSONArray().put(new JSONObject().put("id", 1).put("name", "Task 1")))
                .put("batch", new JSONObject().put("id", 1).put("name", "Task 1"));
        return st;
    }

    private void waitIdle(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(50);
        }
        shadowOf(Looper.getMainLooper()).idle();
    }

    @SuppressWarnings("unchecked")
    private static int packetsOf(MainActivity a) throws Exception {
        Field f = MainActivity.class.getDeclaredField("packets");
        f.setAccessible(true);
        return ((Map<String, Object>) f.get(a)).size();
    }

    private static boolean readyOf(MainActivity a) throws Exception {
        Field f = MainActivity.class.getDeclaredField("ready");
        f.setAccessible(true);
        return f.getBoolean(a);
    }

    private void addScans(int n) {
        Store s = Store.get(ctx);
        for (int k = 0; k < n; k++) {
            Store.Scan x = new Store.Scan();
            x.cid = "local-" + k; x.code = String.format("FMPR%010d", k); x.raw = x.code; x.loc = LOC; x.day = day;
            x.at = 1793690000.0 + k; x.localRes = "ok"; x.tracking = x.code; x.user = "📱 Test phone"; x.sent = false;
            s.add(x);
        }
    }

    /** exactly what a phone that ran app 1.6 has: the whole day (3+ MB) as ONE database value */
    @Test public void reopenWithOversizedListSavedByOldApp() throws Exception {
        String big = bigState(2600, 4000).toString();
        System.out.println("old saved copy: " + big.length() / 1024 + " KB");
        SQLiteDatabase db = Store.get(ctx).getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("k", "state:" + LOC + ":" + day); cv.put("v", big); cv.put("at", System.currentTimeMillis() / 1000.0);
        db.insertWithOnConflict("cache", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        // does this simulated phone refuse a >2 MB value like real phones do? (information only)
        try {
            Cursor c = db.rawQuery("SELECT v FROM cache WHERE k=?", new String[]{"state:" + LOC + ":" + day});
            c.moveToFirst(); c.getString(0); c.close();
            System.out.println("this simulated phone CAN read a >2 MB value (real phones cannot)");
        } catch (Throwable e) {
            System.out.println("this simulated phone refuses a >2 MB value like real phones: " + e);
        }
        addScans(3000);
        ActivityController<MainActivity> ac = Robolectric.buildActivity(MainActivity.class).setup();
        waitIdle(4000);
        MainActivity a = ac.get();
        assertFalse("scan screen closed itself", a.isFinishing());
        assertTrue("list loading finished", readyOf(a));
        System.out.println("packets loaded from the old copy: " + packetsOf(a));
        // minimise + come back, twice
        ac.pause().stop(); waitIdle(300); ac.restart().start().resume(); waitIdle(1500);
        ac.pause().stop(); waitIdle(300); ac.restart().start().resume(); waitIdle(1500);
        assertFalse(a.isFinishing());
        ac.pause().stop().destroy();
        waitIdle(500);
    }

    /** app 1.7+: the list is kept in a file, and loads every time */
    @Test public void reopenWithNewSavedList() throws Exception {
        JSONObject st = bigState(2600, 4000);
        Store s = Store.get(ctx);
        JSONObject pk = new JSONObject().put("psig", "sig1").put("packets", st.getJSONArray("packets"));
        s.put("packets:" + LOC + ":" + day, pk.toString());
        st.remove("packets");
        s.put("state:" + LOC + ":" + day, st.toString());
        String back = s.getCache("packets:" + LOC + ":" + day);
        assertNotNull(back);
        assertEquals(pk.toString().length(), back.length());
        addScans(3000);
        for (int round = 0; round < 3; round++) {          // open, close, open again (like after the phone freed the app)
            ActivityController<MainActivity> ac = Robolectric.buildActivity(MainActivity.class).setup();
            waitIdle(3000);
            MainActivity a = ac.get();
            assertFalse(a.isFinishing());
            assertTrue(readyOf(a));
            assertEquals(2600, packetsOf(a));
            a.submit("FMPR0000002599", "keyboard");              // a scan right after opening
            waitIdle(300);
            ac.pause().stop().destroy();
            waitIdle(300);
        }
        System.out.println("new saved list: 3 reopen rounds OK, 2600 packets each time");
    }

    /** scans made in the first second (list still loading) wait and are checked afterwards */
    @Test public void scanWhileLoading() throws Exception {
        JSONObject st = bigState(2600, 10);
        Store s = Store.get(ctx);
        s.put("packets:" + LOC + ":" + day, new JSONObject().put("psig", "sig1").put("packets", st.getJSONArray("packets")).toString());
        ActivityController<MainActivity> ac = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = ac.get();
        a.submit("FMPR0000000005", "keyboard");             // before the background load finished
        waitIdle(3000);
        assertTrue(readyOf(a));
        int ok = 0;
        for (Store.Scan x : s.forDay(LOC, day)) if ("FMPR0000000005".equals(x.code) && "ok".equals(x.localRes)) ok++;
        assertEquals("the early scan was checked against the list (OK, not 'not in list')", 1, ok);
        ac.pause().stop().destroy();
    }

    /** the self-repair after a crash: saved lists cleared, scans and login kept */
    @Test public void selfRepairKeepsScansAndLogin() throws Exception {
        Store s = Store.get(ctx);
        s.put("packets:" + LOC + ":" + day, bigState(2600, 0).toString());
        addScans(50);
        s.clearLists();
        assertEquals(null, s.getCache("packets:" + LOC + ":" + day));
        assertEquals(50, s.forDay(LOC, day).size());
        assertTrue(Api.loggedIn());
    }

    private static String resOf(MainActivity a, String code) throws Exception {
        Field f = MainActivity.class.getDeclaredField("scans");
        f.setAccessible(true);
        String r = null;
        for (Object it : (java.util.List<?>) f.get(a)) {
            Field c = it.getClass().getDeclaredField("code"); c.setAccessible(true);
            Field rr = it.getClass().getDeclaredField("res"); rr.setAccessible(true);
            if (code.equals(c.get(it))) r = (String) rr.get(it);
        }
        return r;
    }

    /** 12 Nov: two returns of ONE order item are two real packets; the same return re-labelled is one packet */
    @Test public void sameOrderItemOtherReturnIsAnotherPacket() throws Exception {
        JSONObject st = bigState(20, 0);
        JSONArray p = st.getJSONArray("packets");
        JSONObject a = p.getJSONObject(0), b = p.getJSONObject(1), c = p.getJSONObject(2);
        String oi = a.getJSONArray("i").getJSONObject(0).getString("oi");
        a.put("r", "RET-A");
        b.put("r", "RET-B"); b.getJSONArray("i").getJSONObject(0).put("oi", oi);
        c.put("r", "RET-A"); c.getJSONArray("i").getJSONObject(0).put("oi", oi);
        Store.get(ctx).put("packets:" + LOC + ":" + day, new JSONObject().put("psig", "s").put("packets", p).toString());
        ActivityController<MainActivity> ac = Robolectric.buildActivity(MainActivity.class).setup();
        waitIdle(2500);
        MainActivity m = ac.get();
        m.submit(a.getString("t"), "keyboard"); waitIdle(200);
        m.submit(b.getString("t"), "keyboard"); waitIdle(200);
        m.submit(c.getString("t"), "keyboard"); waitIdle(200);
        m.submit("82299030505", "keyboard"); waitIdle(200);
        Field ls = MainActivity.class.getDeclaredField("lastSubmitAt"); ls.setAccessible(true);
        ls.setLong(m, ls.getLong(m) - 3000);           // scanned again 3 s later (within 2 s it is the same read)
        m.submit("82299030505", "keyboard"); waitIdle(400);
        assertEquals("ok", resOf(m, a.getString("t")));
        assertEquals("other return of the same order item = another packet", "ok", resOf(m, b.getString("t")));
        assertEquals("same return under another number = duplicate", "duplicate", resOf(m, c.getString("t")));
        assertEquals("unknown label scanned again", "duplicate", resOf(m, "82299030505"));
        assertFalse(m.isFinishing());
        ac.pause().stop().destroy();
    }

    private static int countCode(MainActivity a, String code) throws Exception {
        Field f = MainActivity.class.getDeclaredField("scans");
        f.setAccessible(true);
        int n = 0;
        for (Object it : (java.util.List<?>) f.get(a)) {
            Field c = it.getClass().getDeclaredField("code"); c.setAccessible(true);
            if (code.equals(c.get(it))) n++;
        }
        return n;
    }

    /** a handheld sending every scan twice (as keys AND as a broadcast): ONE scan, shown OK - never "already scanned" */
    @Test public void doubleReadIsOneScan() throws Exception {
        JSONObject st = bigState(20, 0);
        Store.get(ctx).put("packets:" + LOC + ":" + day, new JSONObject().put("psig", "s").put("packets", st.getJSONArray("packets")).toString());
        ActivityController<MainActivity> ac = Robolectric.buildActivity(MainActivity.class).setup();
        waitIdle(2500);
        MainActivity m = ac.get();
        String t = st.getJSONArray("packets").getJSONObject(3).getString("t");
        m.submit(t, "keyboard");
        m.submit(t, "broadcast");                      // the same read, a moment later
        waitIdle(300);
        assertEquals(1, countCode(m, t));
        assertEquals("ok", resOf(m, t));
        // the camera switched on and off again does not break anything
        ac.get().getSharedPreferences("device", Context.MODE_PRIVATE).edit().putBoolean("camera", false).commit();
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.CAMERA);
        Field tb = MainActivity.class.getDeclaredField("camBtn"); tb.setAccessible(true);
        ((android.view.View) tb.get(m)).performClick(); waitIdle(800);
        Field cs = MainActivity.class.getDeclaredField("camScan"); cs.setAccessible(true);
        assertNotNull("camera panel shown", cs.get(m));
        ac.pause(); waitIdle(300); ac.resume(); waitIdle(500);
        ((android.view.View) tb.get(m)).performClick(); waitIdle(500);
        assertEquals("camera panel gone", null, cs.get(m));
        assertFalse(m.isFinishing());
        ac.pause().stop().destroy();
    }

    @Test public void homeScreenOpens() throws Exception {
        Crash.save("The app closed (crash) - thread main", "java.lang.IllegalStateException: test");
        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        waitIdle(500);
        assertFalse(ac.get().isFinishing());
        ac.pause().stop().destroy();
    }
}
