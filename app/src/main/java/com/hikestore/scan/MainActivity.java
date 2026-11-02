package com.hikestore.scan;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The scanning screen. The scanner device types the code (keyboard mode) or sends it as a
 * broadcast (Zebra, Honeywell, Urovo, Newland, iData, Sunmi, Chainway ...). Each scan is
 * saved on the phone first, decided at once from the list kept on the phone (beep / error /
 * warning / scan again), then sent to HikeOne in the background.
 */
public class MainActivity extends Activity {
    // ------------------------------------------------------------------ state
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private Store store;
    private Sounds sounds;
    private SharedPreferences dev;
    private JSONObject me = new JSONObject(), ui_cfg = new JSONObject();
    private String loc = "", day = "", psig = "";
    private final Map<String, JSONObject> packets = new LinkedHashMap<>();
    private final Map<String, String> index = new HashMap<>();
    private final Map<String, Integer> bags = new HashMap<>();     // courier bag label -> packets inside
    private JSONArray serverScans = new JSONArray(), batches = new JSONArray();
    private JSONObject batch = null, issues = new JSONObject(), lastSync = null, syncRow = null, syncProg = null;
    private boolean syncing = false;
    private List<Item> scans = new ArrayList<>();          // merged: server + this phone, oldest first
    private Item last;
    private boolean sending = false, online = true, loginNeeded = false;
    private final StringBuilder keyBuf = new StringBuilder();
    private long lastKeyAt = 0;

    /** one scan as shown on screen */
    static class Item {
        String cid = "", code = "", tracking = "", res = "", by = "", note = "";
        double at;
        long id, bid;
        boolean pending;
    }

    // ------------------------------------------------------------------ views
    private TextView locText, dayText, syncLine, cExp, cGot, cLeft, cExtra, cDup, taskText, saveChip, histBtn, banner;
    private EditText input;
    private LinearLayout prevStrip, card, pausedBox, taskBar;
    private Button pauseBtn, finishBtn;

    private static final Pattern CODE_OK = Pattern.compile("^[A-Z0-9\\-_/.]+$");
    static final String[][] SCANNER_INTENTS = {
            {"com.symbol.datawedge.api.RESULT_ACTION", "com.symbol.datawedge.data_string"},
            {"com.hikestore.scan.SCAN", "com.symbol.datawedge.data_string"},
            {"com.honeywell.decode.intent.action.EDIT_DATA", "data"},
            {"com.honeywell.scan.broadcast", "data"},
            {"android.intent.ACTION_DECODE_DATA", "barcode_string"},
            {"urovo.rcv.message", "barocode"},
            {"nlscan.action.SCANNER_RESULT", "SCAN_BARCODE1"},
            {"android.intent.action.SCANRESULT", "value"},
            {"com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED", "data"},
            {"com.scanner.broadcast", "data"},
            {"com.android.server.scannerservice.broadcast", "scannerdata"},
            {"scan.rcv.message", "barocode"},
            {"com.xcheng.scanner.action.BARCODE_DECODING_BROADCAST", "EXTRA_BARCODE_DECODING_DATA"},
            {"com.android.serial.BARCODEPORT_RECEIVEDDATA_ACTION", "DATA"},
    };

    /** the code a scanner sent as a broadcast (Zebra, Honeywell, Urovo, Newland, iData, Sunmi ...) */
    static String readScan(Intent i) {
            String code = null;
            for (String[] s : SCANNER_INTENTS) {
                if (s[0].equals(i.getAction())) {
                    code = i.getStringExtra(s[1]);
                    if (code == null) {
                        byte[] b = i.getByteArrayExtra(s[1]);
                        if (b != null) code = new String(b);
                    }
                }
            }
            if (code == null && i.getExtras() != null) {
                for (String k : new String[]{"data", "barcode", "barcode_string", "scannerdata", "value", "SCAN_BARCODE1", "decode_data"}) {
                    Object o = i.getExtras().get(k);
                    if (o instanceof String) { code = (String) o; break; }
                    if (o instanceof byte[]) { code = new String((byte[]) o); break; }
                }
            }
            return code;
    }

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String code = readScan(i);
            if (code != null) submit(code, "broadcast");
        }
    };

    // ================================================================== lifecycle
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Api.init(this);
        Images.init(this);
        Ui.init(this);
        if (!Api.loggedIn()) { toLogin(null); return; }
        store = Store.get(this);
        sounds = new Sounds(this);
        dev = getSharedPreferences("device", MODE_PRIVATE);
        if (!dev.contains("id")) dev.edit().putString("id", UUID.randomUUID().toString().substring(0, 6)).apply();
        sounds.mute = dev.getBoolean("mute", false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        me = Api.cachedMe();
        applyMe();
        day = Ui.today();
        loc = dev.getString("loc", me.optString("default_location", ""));
        if (!hasLoc(loc)) loc = me.optString("default_location", "");
        buildUi();
        loadCachedList();
        rebuildScans();
        renderAll();
        store.cleanOld(System.currentTimeMillis() / 1000.0 - 15 * 86400);
        // fresh settings + list
        bg.execute(new Runnable() {
            @Override public void run() {
                try { final JSONObject m = Api.loadMe(); if (m != null) ui.post(new Runnable() { @Override public void run() { me = m; applyMe(); renderTop(); checkUpdate(); } }); }
                catch (Api.LoginRequired e) { needLogin(); }
                catch (Exception ignored) { }
            }
        });
        refresh(true);
        ui.postDelayed(ticker, 30000);
        ui.postDelayed(sender, 1500);
        ui.postDelayed(photoTick, 3000);
    }

    /** keeps "1,200 of 2,097 photos saved on phone" moving while photos download */
    private boolean photoLine = false;
    private final Runnable photoTick = new Runnable() {
        @Override public void run() {
            boolean now = Images.progress() != null;
            if (now || photoLine) renderTop();
            photoLine = now;
            ui.postDelayed(this, 3000);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        if (store == null) return;
        IntentFilter f = new IntentFilter();
        for (String[] s : SCANNER_INTENTS) f.addAction(s[0]);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(scanReceiver, f, 2 /* RECEIVER_EXPORTED */);
        else registerReceiver(scanReceiver, f);
        focusInput();
        kickSender();
    }

    @Override protected void onPause() {
        super.onPause();
        if (store == null) return;
        try { unregisterReceiver(scanReceiver); } catch (Exception ignored) { }
    }

    @Override public void onBackPressed() {
        new AlertDialog.Builder(this).setTitle("Leave scanning?")
                .setMessage("All scans are saved on this phone" + (store.unsentCount() > 0 ? " and " + store.unsentCount() + " will be sent when you open the app again." : "."))
                .setPositiveButton("Leave", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { finish(); }
                })
                .setNegativeButton("Keep scanning", null).show();
    }

    private void toLogin(String why) {
        Intent i = new Intent(this, LoginActivity.class);
        if (why != null) i.putExtra("why", why);
        startActivity(i);
        finish();
    }

    private void needLogin() {
        loginNeeded = true;
        ui.post(new Runnable() {
            @Override public void run() {
                banner.setVisibility(View.VISIBLE);
                banner.setBackground(Ui.box(Ui.ERRBG, 0, 8));
                banner.setTextColor(Ui.ERR);
                banner.setText("This device was removed by the admin - tap here and scan a new QR code. Scans keep being saved on this phone.");
                banner.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Api.logout();
                        toLogin("Scan a new QR code - your scans are safe on this phone and will be sent after connecting.");
                    }
                });
            }
        });
    }

    private void applyMe() {
        ui_cfg = me.optJSONObject("ui");
        if (ui_cfg == null) ui_cfg = new JSONObject();
        if (sounds != null) {
            sounds.setVolume(ui_cfg.optDouble("volume", 0.9));
            sounds.vibrate = ui_cfg.optBoolean("vibrate", true);
        }
    }

    private boolean hasLoc(String id) {
        JSONArray l = me.optJSONArray("locations");
        if (l == null) return false;
        for (int i = 0; i < l.length(); i++) if (l.optJSONObject(i).optString("id").equals(id)) return true;
        return false;
    }

    private String locName() {
        JSONArray l = me.optJSONArray("locations");
        if (l != null) for (int i = 0; i < l.length(); i++) if (l.optJSONObject(i).optString("id").equals(loc)) return l.optJSONObject(i).optString("name");
        return "Location";
    }

    // ================================================================== screen
    private void buildUi() {
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);

        // top bar
        LinearLayout top = Ui.col(this);
        top.setBackgroundColor(Ui.DARK);
        top.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(6), Ui.dp(6));
        LinearLayout tr = Ui.row(this);
        locText = Ui.text(this, "", 16, Ui.WHITE, true);
        locText.setSingleLine(true);
        locText.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { chooseLocation(); } });
        tr.addView(locText, Ui.lp(0, -2, 1));
        dayText = Ui.text(this, "", 13, 0xFFC9D1DF, true);
        dayText.setPadding(Ui.dp(8), Ui.dp(6), Ui.dp(8), Ui.dp(6));
        dayText.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { chooseDay(); } });
        tr.addView(dayText);
        TextView refreshBtn = topIcon("⟳");
        refreshBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { syncFlipkart(); } });
        tr.addView(refreshBtn);
        TextView menuBtn = topIcon("☰");
        menuBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { menu(); } });
        tr.addView(menuBtn);
        top.addView(tr);
        syncLine = Ui.text(this, "", 11, 0xFF9AA6BB, false);
        syncLine.setSingleLine(true);
        top.addView(syncLine);
        root.addView(top);

        LinearLayout body = Ui.col(this);
        body.setPadding(Ui.dp(8), Ui.dp(6), Ui.dp(8), 0);
        root.addView(body, Ui.lp(-1, 0, 1));

        banner = Ui.text(this, "", 13, Ui.WARN, true);
        banner.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(6));
        banner.setVisibility(View.GONE);
        body.addView(banner, Ui.lpm(-1, -2, 0, 0, 0, 6));

        // counters
        LinearLayout cr = Ui.row(this);
        cExp = counter(cr, "EXP.", Ui.INK);
        cGot = counter(cr, "GOT", Ui.OK);
        cLeft = counter(cr, "LEFT", Ui.INK);
        cExtra = counter(cr, "EXTRA", Ui.WARN);
        cDup = counter(cr, "DUP.", Ui.ERR);
        body.addView(cr);

        // scan box + history
        LinearLayout sr = Ui.row(this);
        sr.setPadding(0, Ui.dp(6), 0, 0);
        input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(Ui.WHITE);
        input.setHintTextColor(0xFF8B95A8);
        input.setHint("Ready - scan a packet");
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextSize(16);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setBackground(Ui.box(0xFF111827, Ui.ACCENT, 10));
        input.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), Ui.dp(8));
        if (Build.VERSION.SDK_INT >= 21) input.setShowSoftInputOnFocus(false);
        sr.addView(input, Ui.lp(0, Ui.dp(46), 1));
        histBtn = Ui.text(this, "🕘", 16, Ui.INK, true);
        histBtn.setGravity(Gravity.CENTER);
        histBtn.setBackground(Ui.box(Ui.WHITE, Ui.LINE, 10));
        histBtn.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
        histBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { history("task", "all"); } });
        LinearLayout.LayoutParams hp = Ui.lp(-2, Ui.dp(46), 0);
        hp.setMargins(Ui.dp(6), 0, 0, 0);
        sr.addView(histBtn, hp);
        body.addView(sr);

        // last scans strip
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        prevStrip = Ui.row(this);
        hs.addView(prevStrip);
        body.addView(hs, Ui.lpm(-1, -2, 0, 6, 0, 0));

        // task bar
        taskBar = Ui.row(this);
        taskBar.setPadding(0, Ui.dp(6), 0, Ui.dp(2));
        taskText = Ui.text(this, "", 12, Ui.INK2, false);
        taskText.setSingleLine(true);
        taskBar.addView(taskText, Ui.lp(0, -2, 1));
        saveChip = Ui.text(this, "", 11, Ui.OK, true);
        saveChip.setPadding(Ui.dp(8), Ui.dp(3), Ui.dp(8), Ui.dp(3));
        taskBar.addView(saveChip);
        pauseBtn = small("⏸ Pause");
        pauseBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pauseResume(); } });
        taskBar.addView(pauseBtn);
        finishBtn = small("■ Finish");
        finishBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finishTask(); } });
        taskBar.addView(finishBtn);
        body.addView(taskBar);

        // paused box
        pausedBox = Ui.col(this);
        pausedBox.setBackground(Ui.box(Ui.PAUSEBG, 0xFFF0B429, 12));
        Ui.pad(pausedBox, 12);
        TextView pt = Ui.text(this, "⏸ Task paused", 17, Ui.PAUSE, true);
        pausedBox.addView(pt);
        TextView pd = Ui.text(this, "Scans are not taken while paused. Everything scanned so far is saved.", 13, Ui.INK, false);
        pausedBox.addView(pd);
        Button rb = Ui.button(this, "▶ Resume scanning", true);
        rb.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pauseResume(); } });
        pausedBox.addView(rb, Ui.lpm(-1, Ui.dp(46), 0, 8, 0, 0));
        pausedBox.setVisibility(View.GONE);
        body.addView(pausedBox, Ui.lpm(-1, -2, 0, 6, 0, 0));

        // result card
        ScrollView sv = new ScrollView(this);
        card = Ui.col(this);
        sv.addView(card);
        body.addView(sv, Ui.lpm(-1, 0, 0, 6, 0, 0));
        ((LinearLayout.LayoutParams) sv.getLayoutParams()).weight = 1;
        showEmpty();
        setContentView(root);

        // ---- scanner as keyboard
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                if (e != null && e.getAction() != KeyEvent.ACTION_DOWN) return true;
                submit(input.getText().toString(), "keyboard");
                return true;
            }
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                String v = s.toString();
                if (v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
                    input.setText("");
                    for (String part : v.split("[\\r\\n]+")) if (!part.trim().isEmpty()) submit(part, "keyboard");
                } else if (dev.getInt("auto_ms", 0) > 0 && v.length() > 0) {
                    ui.removeCallbacks(autoSubmit);
                    ui.postDelayed(autoSubmit, dev.getInt("auto_ms", 0));
                }
            }
        });
        input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                if (has) hideKeyboard();
                else ui.postDelayed(new Runnable() { @Override public void run() { focusInput(); } }, 300);
            }
        });
    }

    private final Runnable autoSubmit = new Runnable() {
        @Override public void run() { if (input.getText().length() > 0) submit(input.getText().toString(), "keyboard"); }
    };

    /** keys from a scanner while focus is not in the box (or a scanner sending raw key events) */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (store == null || input == null) return super.dispatchKeyEvent(e);
        int kc = e.getKeyCode();
        boolean enter = kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER || (kc == KeyEvent.KEYCODE_TAB && dev.getBoolean("tab", false));
        if (getCurrentFocus() == input) {
            if (enter && e.getAction() == KeyEvent.ACTION_DOWN) { submit(input.getText().toString(), "keyboard"); return true; }
            if (kc == KeyEvent.KEYCODE_TAB && e.getAction() == KeyEvent.ACTION_DOWN && dev.getBoolean("tab", false)) return true;
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (enter && keyBuf.length() > 0) { String c = keyBuf.toString(); keyBuf.setLength(0); submit(c, "keyboard"); return true; }
            int ch = e.getUnicodeChar();
            if (ch > 32 && kc != KeyEvent.KEYCODE_BACK) {
                if (System.currentTimeMillis() - lastKeyAt > 1500) keyBuf.setLength(0);
                lastKeyAt = System.currentTimeMillis();
                keyBuf.append((char) ch);
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void focusInput() {
        if (input == null) return;
        if (getCurrentFocus() != input) input.requestFocus();
        hideKeyboard();
    }

    private void hideKeyboard() {
        if (dev.getBoolean("keyboard", false)) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private TextView topIcon(String s) {
        TextView t = Ui.text(this, s, 18, Ui.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(12), Ui.dp(4), Ui.dp(12), Ui.dp(4));
        return t;
    }

    private TextView counter(LinearLayout row, String label, int color) {
        LinearLayout box = Ui.col(this);
        box.setGravity(Gravity.CENTER);
        box.setBackground(Ui.box(Ui.WHITE, Ui.LINE, 10));
        box.setPadding(0, Ui.dp(4), 0, Ui.dp(4));
        TextView n = Ui.text(this, "0", 19, color, true);
        n.setGravity(Gravity.CENTER);
        box.addView(n);
        TextView l = Ui.text(this, label, 9, Ui.INK2, true);
        l.setGravity(Gravity.CENTER);
        box.addView(l);
        LinearLayout.LayoutParams p = Ui.lp(0, -2, 1);
        p.setMargins(Ui.dp(2), 0, Ui.dp(2), 0);
        row.addView(box, p);
        return n;
    }

    private Button small(String s) {
        Button b = Ui.button(this, s, false);
        b.setTextSize(12);
        b.setMinHeight(Ui.dp(32));
        b.setMinimumHeight(Ui.dp(32));
        b.setPadding(Ui.dp(8), 0, Ui.dp(8), 0);
        LinearLayout.LayoutParams p = Ui.lp(-2, Ui.dp(32), 0);
        p.setMargins(Ui.dp(4), 0, 0, 0);
        b.setLayoutParams(p);
        return b;
    }

    // ================================================================== list from Flipkart (kept on the phone)
    private void loadCachedList() {
        String s = store.getCache("state:" + loc + ":" + day);
        if (s != null) try { applyState(new JSONObject(s), false); } catch (Exception ignored) { }
    }

    private void applyState(JSONObject st, boolean save) {
        JSONArray p = st.optJSONArray("packets");
        if (p != null) {
            packets.clear();
            index.clear();
            bags.clear();
            List<String> photos = new ArrayList<>();
            for (int i = 0; i < p.length(); i++) {
                JSONObject o = p.optJSONObject(i);
                packets.put(o.optString("t"), o);
                String bg = o.optString("bg").toUpperCase();
                if (!bg.isEmpty()) bags.put(bg, (bags.containsKey(bg) ? bags.get(bg) : 0) + 1);
                JSONArray its = o.optJSONArray("i");
                if (its != null) for (int k = 0; k < its.length(); k++) photos.add(its.optJSONObject(k).optString("im"));
                addKey(o.optString("t"), o.optString("t"));
                addKey(o.optString("r"), o.optString("t"));
                addKey(o.optString("o"), o.optString("t"));
                JSONArray al = o.optJSONArray("al");          // other barcodes of the same label
                if (al != null) for (int k = 0; k < al.length(); k++) addKey(al.optString(k), o.optString("t"));
                JSONArray it = o.optJSONArray("i");
                if (it != null) for (int k = 0; k < it.length(); k++) {
                    String oi = it.optJSONObject(k).optString("oi");
                    addKey(oi, o.optString("t"));
                    addKey("OD" + oi, o.optString("t"));
                }
            }
            psig = st.optString("psig", "");
            if (save) Images.prefetch(photos);           // keep every photo on the phone (works offline)
        }
        JSONArray trash = st.optJSONArray("trash_cids");   // scans an admin cleared / undid
        if (trash != null) for (int i = 0; i < trash.length(); i++) store.markUndone(trash.optString(i));
        serverScans = st.optJSONArray("scans") != null ? st.optJSONArray("scans") : new JSONArray();
        batches = st.optJSONArray("batches") != null ? st.optJSONArray("batches") : new JSONArray();
        batch = st.optJSONObject("batch");
        issues = st.optJSONObject("issues") != null ? st.optJSONObject("issues") : new JSONObject();
        lastSync = st.optJSONObject("last_ok_sync");
        syncRow = st.optJSONObject("sync");
        syncing = st.optBoolean("syncing");
        syncProg = st.optJSONObject("sync_progress");
        if (save) {
            try {
                JSONObject copy = new JSONObject(st.toString());
                if (p == null) {
                    String old = store.getCache("state:" + loc + ":" + day);
                    if (old != null) copy.put("packets", new JSONObject(old).optJSONArray("packets"));
                }
                store.put("state:" + loc + ":" + day, copy.toString());
            } catch (Exception ignored) { }
        }
    }

    private void addKey(String k, String t) {
        if (k == null || k.isEmpty()) return;
        String K = k.toUpperCase();
        if (!index.containsKey(K)) index.put(K, t);
    }

    private void refresh(final boolean full) {
        final String l = loc, d = day;
        bg.execute(new Runnable() {
            @Override public void run() {
                try {
                    final JSONObject st = Api.get("/api/state?loc=" + Api.encode(l) + "&day=" + d + (full ? "" : "&psig=" + Api.encode(psig)));
                    if (!st.optBoolean("ok")) return;
                    online = true;
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (!l.equals(loc) || !d.equals(day)) return;
                            applyState(st, true);
                            rebuildScans();
                            renderAll();
                        }
                    });
                } catch (Api.LoginRequired e) { needLogin(); }
                catch (Exception e) { online = false; ui.post(new Runnable() { @Override public void run() { renderTask(); } }); }
            }
        });
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refresh(false);
            ui.postDelayed(this, 30000);
        }
    };

    private void syncFlipkart() {
        toast("Getting the list from Flipkart…");
        final String l = loc, d = day;
        bg.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject a = new JSONObject();
                    a.put("loc", l); a.put("day", d); a.put("background", true);
                    final JSONObject r = Api.post("/api/sync", a);
                    if (!r.optBoolean("ok")) { toastBg("Flipkart: " + r.optString("error")); return; }
                    // 2,000+ packets take a few minutes: the server works in the background, we watch the progress
                    double t0 = System.currentTimeMillis() / 1000.0 - 2;
                    boolean seen = false;
                    for (int i = 0; i < 400; i++) {
                        Thread.sleep(i < 3 ? 1500 : 3000);
                        final JSONObject st = Api.get("/api/state?loc=" + Api.encode(l) + "&day=" + d + "&psig=" + Api.encode(psig));
                        if (!st.optBoolean("ok")) continue;
                        ui.post(new Runnable() {
                            @Override public void run() {
                                if (!l.equals(loc) || !d.equals(day)) return;
                                syncing = st.optBoolean("syncing");
                                syncProg = st.optJSONObject("sync_progress");
                                renderTop();
                            }
                        });
                        if (st.optBoolean("syncing")) seen = true;
                        JSONObject sr = st.optJSONObject("sync");
                        if (!st.optBoolean("syncing") && (seen || (sr != null && sr.optDouble("at") >= t0) || i > 4)) {
                            final JSONObject fin = sr;
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    if (fin != null && fin.optInt("ok") == 0) toast("Flipkart: " + fin.optString("message"));
                                    else toast("List updated" + (fin != null ? ": " + fin.optInt("packets") + " packets" : ""));
                                    refresh(true);
                                }
                            });
                            break;
                        }
                    }
                } catch (Api.LoginRequired e) { needLogin(); }
                catch (Exception e) { toastBg("No network - try again"); }
            }
        });
    }

    // ================================================================== merged scans
    private void rebuildScans() {
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < serverScans.length(); i++) {
            JSONObject s = serverScans.optJSONObject(i);
            Item it = new Item();
            it.id = s.optLong("id"); it.code = s.optString("code"); it.tracking = s.optString("t"); it.res = s.optString("res");
            it.at = s.optDouble("at"); it.by = s.optString("by"); it.bid = s.optLong("bid"); it.cid = s.optString("cid"); it.note = s.optString("note");
            out.add(it);
            if (!it.cid.isEmpty()) seen.add(it.cid);
            seen.add("id" + it.id);
        }
        long cur = batch != null ? batch.optLong("id") : 0;
        for (Store.Scan s : store.forDay(loc, day)) {
            if (seen.contains(s.cid) || (s.serverId > 0 && seen.contains("id" + s.serverId))) continue;
            if ("undone".equals(s.serverRes)) continue;
            Item it = new Item();
            it.cid = s.cid; it.code = s.code; it.tracking = s.tracking == null ? "" : s.tracking; it.res = s.res(); it.at = s.at;
            it.by = s.user == null ? "" : s.user; it.bid = s.batchId > 0 ? s.batchId : cur; it.id = s.serverId; it.pending = !s.sent;
            out.add(it);
        }
        Collections.sort(out, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) { return Double.compare(a.at, b.at); }
        });
        scans = out;
    }

    private List<Item> taskScans() {
        long cur = batch != null ? batch.optLong("id") : 0;
        List<Item> l = new ArrayList<>();
        for (Item i : scans) if (cur == 0 ? i.bid == 0 : i.bid == cur) l.add(i);
        return l;
    }

    private Item okFor(String tracking) {
        for (Item i : scans) if ("ok".equals(i.res) && tracking.equals(i.tracking)) return i;
        return null;
    }

    /** already scanned OK - by tracking number, or by order item (other barcode of the same label) */
    private Item okForPacket(JSONObject p) {
        Item f = okFor(p.optString("t"));
        if (f != null) return f;
        Set<String> ois = new HashSet<>();
        JSONArray a = p.optJSONArray("i");
        if (a != null) for (int k = 0; k < a.length(); k++) { String oi = a.optJSONObject(k).optString("oi"); if (!oi.isEmpty()) ois.add(oi); }
        if (ois.isEmpty()) return null;
        for (Item i : scans) {
            if (!"ok".equals(i.res) || i.tracking.isEmpty()) continue;
            JSONObject q = packets.get(i.tracking);
            JSONArray b = q != null ? q.optJSONArray("i") : null;
            if (b != null) for (int k = 0; k < b.length(); k++) if (ois.contains(b.optJSONObject(k).optString("oi"))) return i;
        }
        return null;
    }

    // ================================================================== scanning
    private String normalize(String raw) {
        String c = raw == null ? "" : raw.trim();
        for (String p : ui_cfg.optString("strip_text", "").split(",")) if (!p.trim().isEmpty()) c = c.replace(p.trim(), "");
        return c.replaceAll("\\s+", "").toUpperCase();
    }

    private JSONObject localMatch(String code) {
        String t = index.get(code);
        if (t == null && ui_cfg.optBoolean("smart_match", true)) {
            for (String k : packets.keySet()) {
                if (k.length() >= 8 && (code.contains(k) || (code.length() >= 10 && k.contains(code)))) { t = k; break; }
            }
        }
        return t == null ? null : packets.get(t);
    }

    private boolean paused() { return batch != null && batch.optDouble("paused_at", 0) > 0; }

    void submit(String raw, String via) {
        if (input != null) input.setText("");
        final String code = normalize(raw);
        if (code.isEmpty()) return;
        if (paused()) {
            sounds.play("rescan"); sounds.buzz("rescan");
            toast("Task is paused - press Resume to scan");
            return;
        }
        String res;
        JSONObject p = null;
        Item first = null;
        int min = ui_cfg.optInt("min_code_len", 6);
        if (code.length() < min || !CODE_OK.matcher(code).matches()) res = "rescan";
        else {
            p = localMatch(code);
            if (p != null) { first = okForPacket(p); res = first != null ? "duplicate" : "ok"; }
            else {
                boolean seenNf = false;
                for (Item i : scans) if ("not_found".equals(i.res) && code.equals(i.code)) { seenNf = true; break; }
                if (bags.containsKey(code)) res = "bag";
                else res = seenNf ? "duplicate" : "not_found";
            }
        }
        feedback("ok".equals(res) && pieces(p) > 1 ? "multi" : res);
        // 1) save on the phone first
        Store.Scan s = new Store.Scan();
        s.cid = dev.getString("id", "x") + "-" + Long.toString(System.currentTimeMillis(), 36) + "-" + UUID.randomUUID().toString().substring(0, 5);
        s.code = code; s.raw = raw; s.loc = loc; s.day = day; s.at = System.currentTimeMillis() / 1000.0;
        s.batchId = batch != null ? batch.optLong("id") : 0; s.localRes = res; s.tracking = p != null ? p.optString("t") : "";
        s.user = me.optString("user", "");
        store.add(s);
        // 2) show it
        Item it = new Item();
        it.cid = s.cid; it.code = code; it.tracking = s.tracking; it.res = res; it.at = s.at; it.by = s.user;
        it.bid = s.batchId; it.pending = true;
        scans.add(it);
        last = it;
        showResult(it, p, first);
        renderCounts(); renderPrev(); renderTask();
        // 3) send in the background
        kickSender();
    }

    private void feedback(String res) {
        JSONObject sn = ui_cfg.optJSONObject("sounds");
        String name = sn != null ? sn.optString(res, defaultSound(res)) : defaultSound(res);
        sounds.play(name);
        sounds.buzz(res);
    }

    /** pieces inside one packet: 2 items, or quantity 2+ */
    static int pieces(JSONObject p) {
        if (p == null) return 0;
        JSONArray a = p.optJSONArray("i");
        if (a == null) return 0;
        int n = 0;
        for (int k = 0; k < a.length(); k++) n += Math.max(1, a.optJSONObject(k).optInt("q", 1));
        return n;
    }

    private static String defaultSound(String res) {
        switch (res) {
            case "multi": return "multi";
            case "bag": return "double";
            case "duplicate": return "error";
            case "not_found": return "warn";
            case "rescan": return "rescan";
            default: return "beep";
        }
    }

    // ================================================================== sending (offline-safe)
    private final Runnable sender = new Runnable() {
        @Override public void run() { kickSender(); ui.postDelayed(this, 4000); }
    };

    private void kickSender() {
        if (sending || loginNeeded || store == null) return;
        if (store.unsentCount() == 0) return;
        sending = true;
        net.execute(new Runnable() {
            @Override public void run() {
                boolean any = false;
                try {
                    for (Store.Scan s : store.unsent(50)) {
                        JSONObject a = new JSONObject();
                        a.put("loc", s.loc); a.put("day", s.day); a.put("code", s.raw); a.put("client_id", s.cid);
                        a.put("at", s.at); a.put("device", Build.MANUFACTURER + " " + Build.MODEL + " app" + AppInfo.VERSION_NAME);
                        a.put("local", s.localRes);
                        if (s.batchId > 0) a.put("batch_id", s.batchId);
                        JSONObject r = Api.post("/api/scan", a);
                        if (!r.optBoolean("ok")) { store.markError(s.cid, r.optString("error")); continue; }
                        JSONObject sc = r.optJSONObject("scan");
                        store.markSent(s.cid, sc.optString("res"), sc.optLong("id"), sc.optString("t"));
                        any = true;
                        final String cid = s.cid, sres = sc.optString("res"), local = s.localRes;
                        final JSONObject pk = r.optJSONObject("packet"), first = r.optJSONObject("first");
                        final long sid = sc.optLong("id");
                        ui.post(new Runnable() {
                            @Override public void run() { confirmed(cid, sid, sres, local, pk, first); }
                        });
                    }
                    online = true;
                } catch (Api.LoginRequired e) { needLogin(); }
                catch (Exception e) { online = false; }
                sending = false;
                final boolean refreshNow = any;
                ui.post(new Runnable() {
                    @Override public void run() {
                        renderTask();
                        if (refreshNow) { ui.removeCallbacks(lightRefresh); ui.postDelayed(lightRefresh, 2000); }
                    }
                });
            }
        });
    }

    private final Runnable lightRefresh = new Runnable() { @Override public void run() { refresh(false); } };

    private void confirmed(String cid, long sid, String sres, String local, JSONObject pk, JSONObject first) {
        if ("undone".equals(sres)) {              // the admin cleared this scan: drop it here too
            store.markUndone(cid);
            for (int k = scans.size() - 1; k >= 0; k--) if (cid.equals(scans.get(k).cid)) scans.remove(k);
            renderCounts(); renderPrev(); renderTask();
            return;
        }
        for (Item i : scans) if (cid.equals(i.cid)) { i.pending = false; i.id = sid; i.res = sres; }
        if (!sres.equals(local) && last != null && cid.equals(last.cid)) {
            JSONObject sp = pk != null ? fromServerPacket(pk) : null;
            feedback("ok".equals(sres) && pieces(sp) > 1 ? "multi" : sres);   // another phone scanned it first, or the list changed
            Item f = null;
            if (first != null) { f = new Item(); f.at = first.optDouble("at"); f.by = first.optString("by"); }
            showResult(last, pk != null ? fromServerPacket(pk) : null, f);
        }
        renderCounts(); renderPrev(); renderTask();
    }

    private JSONObject fromServerPacket(JSONObject p) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", p.optString("tracking_id")); o.put("r", p.optString("return_id")); o.put("o", p.optString("order_id"));
            o.put("ty", p.optString("return_type")); o.put("d", p.optString("ofd_date"));
            JSONArray items = new JSONArray(), src = p.optJSONArray("items");
            if (src != null) for (int i = 0; i < src.length(); i++) {
                JSONObject s = src.optJSONObject(i), t = new JSONObject();
                t.put("oi", s.optString("order_item_id")); t.put("sku", s.optString("sku")); t.put("ti", s.optString("title"));
                t.put("im", s.optString("image")); t.put("q", s.optInt("qty")); t.put("re", s.optString("reason"));
                t.put("sr", s.optString("sub_reason")); t.put("pr", s.optDouble("price")); t.put("fsn", s.optString("fsn"));
                items.put(t);
            }
            o.put("i", items);
            return o;
        } catch (Exception e) { return null; }
    }

    // ================================================================== render
    private void renderAll() { renderTop(); renderCounts(); renderPrev(); renderTask(); }

    private void renderTop() {
        if (locText == null) return;
        locText.setText("📦 " + locName() + " ▾");
        dayText.setText(day.equals(Ui.today()) ? "Today ▾" : Ui.day(day) + " ▾");
        String line;
        int expN = 0;
        for (JSONObject p : packets.values()) if (day.equals(p.optString("d")) && p.optInt("x") != 1) expN++;
        if (lastSync != null) line = expN + " packets to scan · list " + Ui.time(lastSync.optDouble("at"));
        else line = "List not fetched from Flipkart yet - tap ⟳";
        if (syncRow != null && syncRow.optInt("ok", 1) == 0) line = "⚠ Last Flipkart refresh failed: " + syncRow.optString("message");
        if (syncing) line = "⟳ Getting the list from Flipkart" + (syncProg != null && syncProg.optInt("total") > 0
                ? " - " + syncProg.optInt("done") + " of " + syncProg.optInt("total") : "…");
        String ph = Images.progress();
        if (ph != null) line += " · 📷 " + ph;
        syncLine.setText(line);
    }

    private void renderCounts() {
        if (cExp == null) return;
        int exp = 0, got = 0, older = 0, dup = 0;
        Set<String> gotSet = new HashSet<>(), nf = new HashSet<>();
        int gotX = 0;
        // "x" = Flipkart took the packet off today's list: not expected any more (still OK if scanned)
        for (JSONObject p : packets.values()) if (day.equals(p.optString("d")) && p.optInt("x") != 1) exp++;
        for (Item i : scans) {
            if ("ok".equals(i.res) && !i.tracking.isEmpty() && gotSet.add(i.tracking)) {
                JSONObject p = packets.get(i.tracking);
                if (p != null && !day.equals(p.optString("d"))) older++;
                else { got++; if (p != null && p.optInt("x") == 1) gotX++; }
            }
            if ("not_found".equals(i.res)) nf.add(i.code);
            if ("duplicate".equals(i.res)) dup++;
        }
        cExp.setText(String.valueOf(exp));
        cGot.setText(older > 0 ? got + "+" + older : String.valueOf(got));
        cLeft.setText(String.valueOf(Math.max(0, exp - (got - gotX))));
        cExtra.setText(String.valueOf(nf.size()));
        cDup.setText(String.valueOf(dup));
    }

    private void renderPrev() {
        if (prevStrip == null) return;
        prevStrip.removeAllViews();
        List<Item> t = taskScans();
        histBtn.setText("🕘 " + t.size());
        for (int k = t.size() - 1, n = 0; k >= 0 && n < 4; k--, n++) {
            final Item i = t.get(k);
            JSONObject p = i.tracking.isEmpty() ? null : packets.get(i.tracking);
            LinearLayout chip = Ui.row(this);
            chip.setBackground(Ui.box(Ui.WHITE, colorOf(i.res), 8));
            chip.setPadding(Ui.dp(4), Ui.dp(3), Ui.dp(8), Ui.dp(3));
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            chip.addView(iv, new LinearLayout.LayoutParams(Ui.dp(30), Ui.dp(30)));
            JSONObject it = firstItem(p);
            Images.load(iv, it != null ? it.optString("im") : "", 96);
            LinearLayout tx = Ui.col(this);
            tx.setPadding(Ui.dp(6), 0, 0, 0);
            tx.addView(Ui.mono(this, "#" + (k + 1) + " " + i.code, 12, Ui.INK, true));
            tx.addView(Ui.text(this, (it != null ? it.optString("sku") : label(i.res)) + " · " + Ui.time(i.at) + (i.pending ? " ⏳" : ""), 10, Ui.INK2, false));
            chip.addView(tx);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, Ui.dp(6), 0);
            prevStrip.addView(chip, lp);
            chip.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { packetDetails(i.tracking, i.code); } });
        }
    }

    private void renderTask() {
        if (taskText == null) return;
        int waiting = store.unsentCount();
        if (waiting > 0) {
            saveChip.setText(online ? "⏳ " + waiting + " sending" : "📶 " + waiting + " saved on phone");
            saveChip.setTextColor(Ui.WARN);
            saveChip.setBackground(Ui.box(Ui.WARNBG, 0, 10));
        } else {
            saveChip.setText("✔ all saved");
            saveChip.setTextColor(Ui.OK);
            saveChip.setBackground(Ui.box(Ui.OKBG, 0, 10));
        }
        if (batch == null) {
            taskText.setText("New task starts with the first scan");
            pauseBtn.setVisibility(View.GONE);
            finishBtn.setVisibility(View.GONE);
        } else {
            taskText.setText(batch.optString("name") + " · " + Ui.plural(taskScans().size(), "scan"));
            pauseBtn.setVisibility(View.VISIBLE);
            finishBtn.setVisibility(View.VISIBLE);
            pauseBtn.setText(paused() ? "▶ Resume" : "⏸ Pause");
        }
        pausedBox.setVisibility(paused() ? View.VISIBLE : View.GONE);
        input.setHint(paused() ? "Paused - press Resume" : "Ready - scan a packet");
        if (!loginNeeded) {
            if (!day.equals(Ui.today())) {
                banner.setVisibility(View.VISIBLE);
                banner.setBackground(Ui.box(Ui.PAUSEBG, 0, 8));
                banner.setTextColor(Ui.PAUSE);
                banner.setText("You are working on " + Ui.day(day) + ", not today.");
            } else banner.setVisibility(View.GONE);
        }
    }

    static int colorOf(String res) {
        switch (res == null ? "" : res) {
            case "ok": return Ui.OK;
            case "duplicate": return Ui.ERR;
            case "not_found": return Ui.WARN;
            case "bag": return 0xFF2563EB;
            default: return Ui.GREY;
        }
    }

    static String label(String res) {
        switch (res == null ? "" : res) {
            case "ok": return "OK";
            case "duplicate": return "ALREADY SCANNED";
            case "not_found": return "NOT IN FLIPKART LIST";
            case "rescan": return "SCAN AGAIN";
            case "undone": return "UNDONE";
            case "bag": return "BAG LABEL";
            default: return res;
        }
    }

    static String typeLabel(String t) {
        if ("courier_return".equals(t)) return "Courier return (RTO)";
        if ("customer_return".equals(t)) return "Customer return";
        return Ui.nice(t);
    }

    private static JSONObject firstItem(JSONObject p) {
        if (p == null) return null;
        JSONArray a = p.optJSONArray("i");
        return a != null && a.length() > 0 ? a.optJSONObject(0) : null;
    }

    private void showEmpty() {
        card.removeAllViews();
        LinearLayout e = Ui.col(this);
        e.setGravity(Gravity.CENTER);
        e.setBackground(Ui.box(Ui.WHITE, 0xFFD5DAE2, 14));
        Ui.pad(e, 22);
        e.addView(Ui.text(this, "Point the scanner at a packet and press the trigger.", 15, Ui.INK, true));
        TextView h = Ui.text(this, "1 beep = OK · buzz = already scanned · warning = not in Flipkart's list · 3 beeps = scan again", 12, Ui.INK2, false);
        h.setPadding(0, Ui.dp(6), 0, 0);
        e.addView(h);
        card.addView(e, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showResult(final Item it, final JSONObject p, Item first) {
        card.removeAllViews();
        int col = colorOf(it.res);
        LinearLayout c = Ui.col(this);
        c.setBackground(Ui.box(Ui.WHITE, col, 12));
        ((android.graphics.drawable.GradientDrawable) c.getBackground()).setStroke(Ui.dp(3), col);
        String head = label(it.res);
        if ("not_found".equals(it.res)) head = "⚠ NOT IN FLIPKART LIST - keep aside";
        if ("ok".equals(it.res)) head = "✔ OK";
        if ("duplicate".equals(it.res)) head = "✖ ALREADY SCANNED";
        if ("rescan".equals(it.res)) head = "↻ SCAN AGAIN";
        if ("bag".equals(it.res)) head = "🛍 BAG LABEL - open the bag";
        LinearLayout hr = Ui.row(this);
        hr.setBackgroundColor(col);
        hr.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), Ui.dp(8));
        TextView ht = Ui.text(this, head, 16, Ui.WHITE, true);
        hr.addView(ht, Ui.lp(0, -2, 1));
        String right = Ui.time(it.at);
        if (first != null) right = "first " + Ui.time(first.at) + (first.by.isEmpty() ? "" : " by " + first.by);
        hr.addView(Ui.text(this, right, 12, Ui.WHITE, true));
        c.addView(hr);
        int pc = "ok".equals(it.res) ? pieces(p) : 0;
        if (pc > 1) {
            TextView mb = Ui.text(this, "⚠ " + pc + " PIECES IN THIS PACKET - check all " + pc + " are inside", 15, Ui.WHITE, true);
            mb.setBackgroundColor(0xFF7C3AED);
            mb.setPadding(Ui.dp(12), Ui.dp(7), Ui.dp(12), Ui.dp(7));
            c.addView(mb);
        }
        int no = taskScans().indexOf(it) + 1;
        LinearLayout sl = Ui.row(this);
        sl.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), 0);
        sl.addView(Ui.text(this, "SCANNED ", 11, Ui.INK2, true));
        TextView sc = Ui.mono(this, it.code, 18, Ui.INK, true);
        sl.addView(sc, Ui.lp(0, -2, 1));
        if (no > 0) sl.addView(Ui.text(this, "#" + no, 13, Ui.INK2, true));
        c.addView(sl);
        if (p != null) {
            JSONObject i0 = firstItem(p);
            String photo = dev.getString("photo", "normal");
            LinearLayout br = Ui.row(this);
            br.setGravity(Gravity.TOP);
            br.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), Ui.dp(8));
            if (!"none".equals(photo)) {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setBackground(Ui.box(0xFFF4F5F7, 0, 10));
                int size = "small".equals(photo) ? Ui.dp(80) : (int) (getResources().getDisplayMetrics().widthPixels * 0.36);
                br.addView(iv, new LinearLayout.LayoutParams(size, size));
                Images.load(iv, i0 != null ? i0.optString("im") : "", 400);
            }
            LinearLayout info = Ui.col(this);
            info.setPadding(Ui.dp(10), 0, 0, 0);
            info.addView(Ui.mono(this, i0 != null ? i0.optString("sku") : "-", 17, Ui.INK, true));
            if (i0 != null) info.addView(Ui.text(this, i0.optString("ti"), 12, Ui.INK2, false));
            String ty = p.optString("ty");
            TextView tag = Ui.text(this, typeLabel(ty), 12, "customer_return".equals(ty) ? Ui.CUST : Ui.COUR, true);
            tag.setBackground(Ui.box("customer_return".equals(ty) ? Ui.CUSTBG : Ui.COURBG, 0, 10));
            tag.setPadding(Ui.dp(8), Ui.dp(2), Ui.dp(8), Ui.dp(2));
            info.addView(tag, Ui.lpm(-2, -2, 0, 4, 0, 2));
            if (!day.equals(p.optString("d"))) info.addView(Ui.text(this, "Expected " + Ui.day(p.optString("d")), 12, Ui.WARN, true));
            JSONArray items = p.optJSONArray("i");
            if (items != null && items.length() > 1) info.addView(Ui.text(this, items.length() + " items in this packet", 12, Ui.WARN, true));
            if (i0 != null && !i0.optString("re").isEmpty()) info.addView(Ui.text(this, i0.optString("re") + (i0.optString("sr").isEmpty() ? "" : " · " + i0.optString("sr")), 12, Ui.INK2, false));
            JSONObject prob = issues.optJSONObject(p.optString("t"));
            if (prob != null) info.addView(Ui.text(this, "Problem: " + prob.optString("type"), 12, Ui.ERR, true));
            StringBuilder kv = new StringBuilder();
            StringBuilder ois = new StringBuilder();
            if (items != null) for (int k = 0; k < items.length(); k++) { if (k > 0) ois.append(", "); ois.append(items.optJSONObject(k).optString("oi")); }
            kv.append("Order item  ").append(ois).append('\n').append("Order       ").append(p.optString("o")).append('\n')
              .append("Tracking    ").append(p.optString("t")).append('\n').append("Return      ").append(p.optString("r"));
            if (i0 != null && i0.optDouble("pr", 0) > 0) kv.append('\n').append("Price       ₹").append((long) i0.optDouble("pr"));
            TextView kvt = Ui.mono(this, kv.toString(), 11, Ui.INK, false);
            kvt.setPadding(0, Ui.dp(6), 0, 0);
            info.addView(kvt);
            br.addView(info, Ui.lp(0, -2, 1));
            c.addView(br);
            if (items != null) for (int k = 1; k < items.length(); k++) {
                JSONObject o = items.optJSONObject(k);
                LinearLayout ir = Ui.row(this);
                ir.setPadding(Ui.dp(12), Ui.dp(4), Ui.dp(12), Ui.dp(4));
                ImageView iv = new ImageView(this);
                ir.addView(iv, new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));
                Images.load(iv, o.optString("im"), 128);
                TextView t = Ui.mono(this, "  " + o.optString("sku") + "\n  " + o.optString("oi"), 12, Ui.INK, false);
                ir.addView(t);
                c.addView(ir);
            }
        } else if ("bag".equals(it.res)) {
            Integer n = bags.get(it.code);
            TextView big = Ui.text(this, n != null ? n + (n == 1 ? " packet inside" : " packets inside") : "Courier bag", 26, 0xFF2563EB, true);
            big.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), 0);
            c.addView(big);
            TextView m = Ui.text(this, "This is the label of a courier bag, not a packet. Open the bag and scan each packet inside. This scan is not counted.", 13, Ui.INK, false);
            m.setPadding(Ui.dp(12), Ui.dp(4), Ui.dp(12), Ui.dp(8));
            c.addView(m);
        } else {
            boolean square = "rescan".equals(it.res) && it.code.split("\\|").length > 3;   // Ekart's square (Data Matrix) code
            TextView m = Ui.text(this, square ? "That was the square code on the label. It only has the courier's routing details, not the tracking number. Scan the long barcode at the bottom of the label (FMPP… / FMPC… / FMPR…) or the AWB barcode."
                    : "rescan".equals(it.res) ? "The code could not be read properly. Scan the label again."
                    : "This packet is not in Flipkart's list for this location. Keep it aside - it is saved and checked again when the list refreshes.", 13, Ui.INK2, false);
            m.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), Ui.dp(8));
            c.addView(m);
        }
        LinearLayout ar = Ui.row(this);
        ar.setPadding(Ui.dp(10), Ui.dp(4), Ui.dp(10), Ui.dp(10));
        Button pb = small("⚠ Problem");
        pb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Problems.start(MainActivity.this, loc, day, p != null ? p.optString("t") : it.code, it.code, me); }
        });
        ar.addView(pb);
        Button ub = small("↶ Undo");
        ub.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { undo(it); } });
        ar.addView(ub);
        if (p != null) {
            Button db = small("ℹ Details");
            db.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { packetDetails(p.optString("t"), it.code); } });
            ar.addView(db);
        }
        c.addView(ar);
        card.addView(c, new LinearLayout.LayoutParams(-1, -2));
    }

    // ================================================================== actions
    private void undo(final Item it) {
        new AlertDialog.Builder(this).setTitle("Undo scan?").setMessage("Undo the scan of " + it.code + "? (It is kept in the trash, never deleted.)")
                .setPositiveButton("Undo", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (it.id <= 0) { toast("Still being sent - try again in a few seconds"); return; }
                        bg.execute(new Runnable() {
                            @Override public void run() {
                                try {
                                    JSONObject a = new JSONObject(); a.put("id", it.id);
                                    final JSONObject r = Api.post("/api/scan/delete", a);
                                    if (!r.optBoolean("ok")) { toastBg(r.optString("error")); return; }
                                    if (!it.cid.isEmpty()) store.markUndone(it.cid);
                                    ui.post(new Runnable() { @Override public void run() { toast("Scan removed"); showEmpty(); refresh(false); } });
                                } catch (Api.LoginRequired e) { needLogin(); }
                                catch (Exception e) { toastBg("No network - try again"); }
                            }
                        });
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void pauseResume() {
        if (batch == null) return;
        final boolean pause = !paused();
        final long id = batch.optLong("id");
        bg.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject a = new JSONObject(); a.put("id", id); a.put("pause", pause);
                    JSONObject r = Api.post("/api/batch/pause", a);
                    if (!r.optBoolean("ok")) { toastBg(r.optString("error")); return; }
                    toastBg(pause ? "Task paused - all scans are saved" : "Resumed - keep scanning");
                    refresh(false);
                } catch (Api.LoginRequired e) { needLogin(); }
                catch (Exception e) { toastBg("Needs network to pause / resume - try again"); }
            }
        });
    }

    private void finishTask() {
        if (batch == null) return;
        final long id = batch.optLong("id");
        int ok = 0, nf = 0;
        for (Item i : taskScans()) { if ("ok".equals(i.res)) ok++; if ("not_found".equals(i.res)) nf++; }
        final EditText count = new EditText(this);
        count.setInputType(InputType.TYPE_CLASS_NUMBER);
        count.setHint("Packets counted by hand");
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 16);
        box.addView(Ui.text(this, "Scanned in this task: " + (ok + nf) + " packets (" + ok + " in list, " + nf + " not in list).\nFinishing does not delete anything - you can reopen it.", 13, Ui.INK2, false));
        box.addView(count);
        new AlertDialog.Builder(this).setTitle("Finish " + batch.optString("name")).setView(box)
                .setPositiveButton("Finish task", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        final String pc = count.getText().toString().trim();
                        bg.execute(new Runnable() {
                            @Override public void run() {
                                try {
                                    JSONObject a = new JSONObject(); a.put("id", id); a.put("physical_count", pc);
                                    JSONObject r = Api.post("/api/batch/close", a);
                                    toastBg(r.optBoolean("ok") ? "Task finished" : r.optString("error"));
                                    refresh(false);
                                } catch (Api.LoginRequired e) { needLogin(); }
                                catch (Exception e) { toastBg("Needs network - try again"); }
                            }
                        });
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void newTask() {
        final EditText name = new EditText(this);
        name.setHint("Name (e.g. Ekart morning)");
        final EditText courier = new EditText(this);
        courier.setHint("Courier / person (optional)");
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 16);
        box.addView(name);
        box.addView(courier);
        new AlertDialog.Builder(this).setTitle("Start a new task").setView(box)
                .setPositiveButton("Start", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        final String n = name.getText().toString(), c = courier.getText().toString();
                        bg.execute(new Runnable() {
                            @Override public void run() {
                                try {
                                    JSONObject a = new JSONObject(); a.put("loc", loc); a.put("day", day); a.put("name", n); a.put("courier", c);
                                    JSONObject r = Api.post("/api/batch/start", a);
                                    toastBg(r.optBoolean("ok") ? "New task started" : r.optString("error"));
                                    refresh(false);
                                } catch (Api.LoginRequired e) { needLogin(); }
                                catch (Exception e) { toastBg("Needs network - try again"); }
                            }
                        });
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void reopenTask() {
        final List<JSONObject> closed = new ArrayList<>();
        for (int i = 0; i < batches.length(); i++) if (batches.optJSONObject(i).optDouble("closed_at", 0) > 0) closed.add(batches.optJSONObject(i));
        if (closed.isEmpty()) { toast("No finished task on this day"); return; }
        String[] names = new String[closed.size()];
        for (int i = 0; i < names.length; i++) names[i] = closed.get(i).optString("name") + " · " + closed.get(i).optInt("ok_n") + " OK";
        new AlertDialog.Builder(this).setTitle("Reopen which task?").setItems(names, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, final int w) {
                bg.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            JSONObject a = new JSONObject(); a.put("id", closed.get(w).optLong("id"));
                            JSONObject r = Api.post("/api/batch/reopen", a);
                            toastBg(r.optBoolean("ok") ? "Task reopened" : r.optString("error"));
                            refresh(false);
                        } catch (Api.LoginRequired e) { needLogin(); }
                        catch (Exception e) { toastBg("Needs network - try again"); }
                    }
                });
            }
        }).show();
    }

    private void chooseLocation() {
        final JSONArray l = me.optJSONArray("locations");
        if (l == null || l.length() == 0) return;
        String[] names = new String[l.length()];
        for (int i = 0; i < l.length(); i++) names[i] = l.optJSONObject(i).optString("name");
        new AlertDialog.Builder(this).setTitle("Location").setItems(names, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                loc = l.optJSONObject(w).optString("id");
                dev.edit().putString("loc", loc).apply();
                switchView();
            }
        }).show();
    }

    private void chooseDay() {
        Calendar c = Calendar.getInstance(Ui.IST);
        try { String[] p = day.split("-"); c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2])); } catch (Exception ignored) { }
        new DatePickerDialog(this, new DatePickerDialog.OnDateSetListener() {
            @Override public void onDateSet(DatePicker v, int y, int m, int d) {
                day = String.format(java.util.Locale.US, "%04d-%02d-%02d", y, m + 1, d);
                switchView();
            }
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void switchView() {
        packets.clear(); index.clear(); psig = ""; batch = null; batches = new JSONArray(); serverScans = new JSONArray();
        issues = new JSONObject(); lastSync = null; syncRow = null; last = null;
        loadCachedList();
        rebuildScans();
        showEmpty();
        renderAll();
        refresh(true);
    }

    private void menu() {
        final String[] items = {"⟳ Refresh list from Flipkart", "＋ Start new task", "↺ Reopen a finished task", "🕘 History",
                "📋 Not received yet", "⚠ Report a problem packet", "📱 This device", "⇪ Send this phone's scans again",
                "Disconnect this device"};
        new AlertDialog.Builder(this).setTitle("Hike Scan " + AppInfo.VERSION_NAME + " · 📱 " + Api.deviceName())
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        switch (w) {
                            case 0: syncFlipkart(); break;
                            case 1: newTask(); break;
                            case 2: reopenTask(); break;
                            case 3: history("task", "all"); break;
                            case 4: history("day", "missing"); break;
                            case 5: Problems.start(MainActivity.this, loc, day, "", "", me); break;
                            case 6: deviceSettings(); break;
                            case 7: resendAll(); break;
                            case 8: logout(); break;
                        }
                    }
                }).show();
    }

    private void resendAll() {
        int n = store.resendDay(loc, day);
        toast(n + " scans will be sent again - the server skips ones it already has");
        kickSender();
    }

    private void logout() {
        int waiting = store.unsentCount();
        new AlertDialog.Builder(this).setTitle("Disconnect this device?")
                .setMessage((waiting > 0 ? waiting + " scans are not sent yet. They stay on this phone and are sent after connecting again.\n\n" : "") +
                        "To use it again, scan a new QR code from the admin.")
                .setPositiveButton("Disconnect", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { Api.logout(); toLogin(null); }
                }).setNegativeButton("Cancel", null).show();
    }

    private void deviceSettings() {
        final String[] photo = {"normal", "small", "none"};
        final String[] suffix = {"enter", "tab", "none"};
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 16);
        box.addView(Ui.text(this, "Only for this phone / scanner", 12, Ui.INK2, false));
        final android.widget.CheckBox mute = new android.widget.CheckBox(this);
        mute.setText("Mute sounds");
        mute.setChecked(dev.getBoolean("mute", false));
        box.addView(mute);
        final android.widget.CheckBox kb = new android.widget.CheckBox(this);
        kb.setText("Show the on-screen keyboard (type codes by hand)");
        kb.setChecked(dev.getBoolean("keyboard", false));
        box.addView(kb);
        box.addView(Ui.text(this, "Product photo", 13, Ui.INK, true));
        final android.widget.RadioGroup pg = new android.widget.RadioGroup(this);
        String[] pl = {"Normal", "Small", "Hide"};
        for (int i = 0; i < 3; i++) {
            android.widget.RadioButton r = new android.widget.RadioButton(this);
            r.setText(pl[i]); r.setId(100 + i);
            pg.addView(r);
            if (photo[i].equals(dev.getString("photo", "normal"))) r.setChecked(true);
        }
        box.addView(pg);
        box.addView(Ui.text(this, "The scanner ends each code with", 13, Ui.INK, true));
        final android.widget.RadioGroup sg = new android.widget.RadioGroup(this);
        String[] sl = {"Enter (most scanners)", "Tab", "Nothing - send after a short pause"};
        String cur = dev.getBoolean("tab", false) ? "tab" : (dev.getInt("auto_ms", 0) > 0 ? "none" : "enter");
        for (int i = 0; i < 3; i++) {
            android.widget.RadioButton r = new android.widget.RadioButton(this);
            r.setText(sl[i]); r.setId(200 + i);
            sg.addView(r);
            if (suffix[i].equals(cur)) r.setChecked(true);
        }
        box.addView(sg);
        TextView info = Ui.text(this, "Scanners that send a broadcast (Zebra DataWedge, Honeywell, Urovo, Newland, iData, Sunmi, Chainway) work without setup. " +
                "Device " + dev.getString("id", "") + " · " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE, 11, Ui.INK2, false);
        info.setPadding(0, Ui.dp(8), 0, 0);
        box.addView(info);
        Button test = Ui.button(this, "▶ Test sounds", false);
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String[] r = {"ok", "multi", "duplicate", "not_found", "rescan"};
                boolean m = sounds.mute; sounds.mute = false;
                for (int i = 0; i < r.length; i++) {
                    final int k = i;
                    ui.postDelayed(new Runnable() { @Override public void run() { boolean mm = sounds.mute; sounds.mute = false; feedback(r[k]); sounds.mute = mm; } }, i * 1300L);
                }
                sounds.mute = m;
            }
        });
        box.addView(test, Ui.lpm(-2, Ui.dp(40), 0, 8, 0, 0));
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle("This device").setView(sv)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        int pi = pg.getCheckedRadioButtonId() - 100, si = sg.getCheckedRadioButtonId() - 200;
                        dev.edit().putBoolean("mute", mute.isChecked()).putBoolean("keyboard", kb.isChecked())
                                .putString("photo", pi >= 0 && pi < 3 ? photo[pi] : "normal")
                                .putBoolean("tab", si == 1).putInt("auto_ms", si == 2 ? 250 : 0).apply();
                        sounds.mute = mute.isChecked();
                        if (Build.VERSION.SDK_INT >= 21) input.setShowSoftInputOnFocus(kb.isChecked());
                        toast("Saved for this device");
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void checkUpdate() {
        JSONObject app = me.optJSONObject("app");
        if (app == null || app.optInt("version_code") <= AppInfo.VERSION_CODE) return;
        final String url = app.optString("url");
        new AlertDialog.Builder(this).setTitle("New app version " + app.optString("version_name"))
                .setMessage(app.optString("notes", "A newer version of Hike Scan is ready.") + "\n\nYour scans are kept during the update.")
                .setPositiveButton("Download", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { startActivity(new Intent(Intent.ACTION_VIEW, Api.webUrl(url))); }
                }).setNegativeButton("Later", null).show();
    }

    // ================================================================== history & details
    private void history(final String scope, final String filter) {
        final List<Item> list = "task".equals(scope) ? taskScans() : scans;
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 10);
        LinearLayout chips = Ui.row(this);
        final String[][] fl = {{"all", "All"}, {"ok", "OK"}, {"duplicate", "Dup."}, {"not_found", "Not in list"}, {"rescan", "Again"}, {"missing", "Not received"}};
        final AlertDialog[] dlg = new AlertDialog[1];
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(chips);
        box.addView(hs);
        for (final String[] f : fl) {
            int n = 0;
            if ("missing".equals(f[0])) n = missing().size();
            else for (Item i : list) if ("all".equals(f[0]) || f[0].equals(i.res)) n++;
            TextView c = Ui.text(this, f[1] + " " + n, 12, f[0].equals(filter) ? Ui.WHITE : Ui.INK, true);
            c.setBackground(Ui.box(f[0].equals(filter) ? Ui.INK : Ui.WHITE, Ui.LINE, 14));
            c.setPadding(Ui.dp(10), Ui.dp(5), Ui.dp(10), Ui.dp(5));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, Ui.dp(5), 0);
            chips.addView(c, lp);
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (dlg[0] != null) dlg[0].dismiss(); history(scope, f[0]); }
            });
        }
        TextView scopeBtn = Ui.text(this, "task".equals(scope) ? "Showing: this task · tap for the whole day" : "Showing: whole day · tap for this task", 12, Ui.ACCENT, true);
        scopeBtn.setPadding(0, Ui.dp(8), 0, Ui.dp(4));
        scopeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (dlg[0] != null) dlg[0].dismiss(); history("task".equals(scope) ? "day" : "task", filter); }
        });
        box.addView(scopeBtn);
        LinearLayout rows = Ui.col(this);
        if ("missing".equals(filter)) {
            List<JSONObject> m = missing();
            for (JSONObject p : m) rows.addView(historyRow("", p.optString("t"), p, "Not received yet", Ui.GREY, ""));
            if (m.isEmpty()) rows.addView(Ui.text(this, "Everything in today's list has been received.", 13, Ui.INK2, false));
        } else {
            int shown = 0;
            for (int k = list.size() - 1; k >= 0 && shown < 400; k--) {
                Item i = list.get(k);
                if (!"all".equals(filter) && !filter.equals(i.res)) continue;
                JSONObject p = i.tracking.isEmpty() ? null : packets.get(i.tracking);
                rows.addView(historyRow("#" + (k + 1), i.code, p, label(i.res), colorOf(i.res), Ui.time(i.at) + " " + i.by + (i.pending ? " ⏳" : "")));
                shown++;
            }
            if (shown == 0) rows.addView(Ui.text(this, "Nothing here yet.", 13, Ui.INK2, false));
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(rows);
        box.addView(sv, new LinearLayout.LayoutParams(-1, (int) (getResources().getDisplayMetrics().heightPixels * 0.62)));
        dlg[0] = new AlertDialog.Builder(this).setTitle("🕘 Scan history").setView(box).setPositiveButton("Close", null).show();
        dlg[0].setOnDismissListener(new DialogInterface.OnDismissListener() { @Override public void onDismiss(DialogInterface d) { focusInput(); } });
    }

    private List<JSONObject> missing() {
        Set<String> got = new HashSet<>();
        for (Item i : scans) if ("ok".equals(i.res)) got.add(i.tracking);
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject p : packets.values()) if (day.equals(p.optString("d")) && !got.contains(p.optString("t"))) out.add(p);
        return out;
    }

    private View historyRow(String no, final String code, final JSONObject p, String res, int color, String right) {
        LinearLayout r = Ui.row(this);
        r.setPadding(0, Ui.dp(6), 0, Ui.dp(6));
        TextView n = Ui.text(this, no, 12, Ui.INK2, true);
        n.setMinWidth(Ui.dp(34));
        r.addView(n);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        r.addView(iv, new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(40)));
        JSONObject it = firstItem(p);
        Images.load(iv, it != null ? it.optString("im") : "", 128);
        LinearLayout t = Ui.col(this);
        t.setPadding(Ui.dp(8), 0, 0, 0);
        t.addView(Ui.mono(this, code, 13, Ui.INK, true));
        if (it != null) t.addView(Ui.text(this, it.optString("sku") + " · " + typeLabel(p.optString("ty")), 11, Ui.INK2, false));
        TextView rt = Ui.text(this, res + (right.isEmpty() ? "" : "  ·  " + right), 11, color, true);
        t.addView(rt);
        r.addView(t, Ui.lp(0, -2, 1));
        r.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (p != null) packetDetails(p.optString("t"), code); }
        });
        return r;
    }

    private void packetDetails(String tracking, final String code) {
        final JSONObject p = tracking == null || tracking.isEmpty() ? null : packets.get(tracking);
        if (p == null) {
            new AlertDialog.Builder(this).setTitle(code).setMessage("Not in Flipkart's list for this location.")
                    .setPositiveButton("Report problem", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { Problems.start(MainActivity.this, loc, day, code, code, me); }
                    }).setNegativeButton("Close", null).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        Item got = okFor(tracking);
        sb.append("Tracking ID: ").append(p.optString("t")).append("\nReturn ID: ").append(p.optString("r"))
          .append("\nOrder ID: ").append(p.optString("o")).append("\nType: ").append(typeLabel(p.optString("ty")))
          .append("\nExpected: ").append(Ui.day(p.optString("d")))
          .append("\nReceived: ").append(got != null ? Ui.dateTime(got.at) + " by " + got.by : "not yet");
        JSONArray items = p.optJSONArray("i");
        if (items != null) for (int k = 0; k < items.length(); k++) {
            JSONObject o = items.optJSONObject(k);
            sb.append("\n\n").append(o.optString("sku")).append(" × ").append(o.optInt("q", 1)).append("\n").append(o.optString("ti"))
              .append("\nOrder item: ").append(o.optString("oi")).append("\nFSN: ").append(o.optString("fsn"))
              .append("\nPrice: ₹").append((long) o.optDouble("pr", 0)).append("\nReason: ").append(o.optString("re"))
              .append(o.optString("sr").isEmpty() ? "" : " · " + o.optString("sr"));
        }
        new AlertDialog.Builder(this).setTitle(firstItem(p) != null ? firstItem(p).optString("sku") : tracking).setMessage(sb.toString())
                .setPositiveButton("Report problem", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { Problems.start(MainActivity.this, loc, day, p.optString("t"), code, me); }
                }).setNegativeButton("Close", null).show();
    }

    // ================================================================== problems: photos / videos
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        Problems.onResult(this, req, res, data);
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        Problems.onPermission(this, req, grants);
    }

    // ================================================================== misc
    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    void toastBg(final String s) { ui.post(new Runnable() { @Override public void run() { toast(s); } }); }

    void problemSaved(String tracking, JSONObject issue) {
        try { issues.put(tracking, issue); } catch (Exception ignored) { }
        if (last != null) refresh(false);
    }
}
