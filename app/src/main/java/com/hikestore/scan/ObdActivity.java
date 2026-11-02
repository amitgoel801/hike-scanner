package com.hikestore.scan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Request OBD (open box delivery): scan a packet the courier has already scanned on Flipkart's
 * handover screen -> the server finds which courier has it and asks Flipkart for OBD in his task.
 *  ✔ OBD requested  = keep the packet aside (single beep)
 *  ✖ not allowed / already requested / limit used = error sound, reason on screen
 *  ⚠ courier has not scanned it yet = warning sound
 * Needs the internet (it is a live request to Flipkart while the courier is here).
 */
public class ObdActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private Sounds sounds;
    private JSONObject soundCfg = new JSONObject();
    private String loc = "", day = "", locName = "";
    private TextView liveText, prompt;
    private LinearLayout card, list;
    private final StringBuilder keyBuf = new StringBuilder();
    private long lastKeyAt = 0;
    private boolean busy = false;

    public static void start(Activity a, String loc, String day, String locName) {
        Intent i = new Intent(a, ObdActivity.class);
        i.putExtra("loc", loc).putExtra("day", day).putExtra("loc_name", locName);
        a.startActivity(i);
    }

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String t = MainActivity.readScan(i);
            if (t != null) submit(t);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Api.init(this);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        loc = getIntent().getStringExtra("loc");
        day = getIntent().getStringExtra("day");
        locName = getIntent().getStringExtra("loc_name");
        sounds = new Sounds(this);
        JSONObject me = Api.cachedMe();
        JSONObject u = me != null ? me.optJSONObject("ui") : null;
        if (u != null) {
            sounds.setVolume(u.optDouble("volume", 0.9));
            sounds.vibrate = u.optBoolean("vibrate", true);
            if (u.optJSONObject("sounds") != null) soundCfg = u.optJSONObject("sounds");
        }

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout top = Ui.row(this);
        top.setBackgroundColor(Ui.DARK);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(10), Ui.dp(6));
        Button back = Ui.button(this, "←", false);
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        top.addView(back, new LinearLayout.LayoutParams(Ui.dp(48), Ui.dp(40)));
        LinearLayout tt = Ui.col(this);
        tt.setPadding(Ui.dp(8), 0, 0, 0);
        tt.addView(Ui.text(this, "📭 Request OBD", 17, Ui.WHITE, true));
        tt.addView(Ui.text(this, locName + " · " + Ui.day(day), 11, 0xFFB8C0CC, false));
        top.addView(tt, Ui.lp(0, -2, 1));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = Ui.col(this);
        Ui.pad(body, 8);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        liveText = Ui.text(this, "Checking Flipkart…", 12, Ui.INK, false);
        liveText.setBackground(Ui.box(0xFFEEF2FF, 0xFFC7D2FE, 10));
        Ui.pad(liveText, 8);
        body.addView(liveText, new LinearLayout.LayoutParams(-1, -2));

        prompt = Ui.mono(this, "Scan a packet to Request OBD", 15, Ui.WHITE, true);
        prompt.setBackground(Ui.box(Ui.DARK, Ui.ACCENT, 12));
        prompt.setGravity(Gravity.CENTER);
        Ui.pad(prompt, 14);
        body.addView(prompt, Ui.lpm(-1, -2, 0, 8, 0, 0));

        LinearLayout btns = Ui.row(this);
        Button type = Ui.button(this, "⌨ Type code", false);
        type.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { typeCode(); } });
        btns.addView(type, Ui.lp(0, Ui.dp(40), 1));
        Button st = Ui.button(this, "⟳ Check status", false);
        st.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { load(true); } });
        btns.addView(st, Ui.lp(0, Ui.dp(40), 1));
        body.addView(btns, Ui.lpm(-1, -2, 0, 6, 0, 0));

        card = Ui.col(this);
        body.addView(card, Ui.lpm(-1, -2, 0, 8, 0, 0));
        TextView help = Ui.text(this, "✔ = keep the packet aside for the courier to open and check.  ✖ = not possible (error sound).  " +
                "The courier must have scanned the packet on Flipkart's handover screen first.", 11, Ui.INK2, false);
        help.setPadding(0, Ui.dp(6), 0, Ui.dp(4));
        body.addView(help);
        body.addView(Ui.text(this, "OBD requests today", 14, Ui.INK, true), Ui.lpm(-2, -2, 0, 8, 0, 4));
        list = Ui.col(this);
        body.addView(list, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        load(false);
    }

    @Override protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        for (String[] s : MainActivity.SCANNER_INTENTS) f.addAction(s[0]);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(scanReceiver, f, 2 /* RECEIVER_EXPORTED */);
        else registerReceiver(scanReceiver, f);
    }

    @Override protected void onPause() {
        super.onPause();
        try { unregisterReceiver(scanReceiver); } catch (Exception ignored) { }
    }

    /** a scanner that types like a keyboard: collect the keys, Enter = done */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (getCurrentFocus() instanceof EditText) return super.dispatchKeyEvent(e);
        int kc = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if ((kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER || kc == KeyEvent.KEYCODE_TAB) && keyBuf.length() > 0) {
                String c = keyBuf.toString();
                keyBuf.setLength(0);
                submit(c);
                return true;
            }
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

    private void typeCode() {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        new AlertDialog.Builder(this).setTitle("Tracking ID").setView(et)
                .setPositiveButton("Request OBD", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { submit(et.getText().toString()); }
                }).setNegativeButton("Cancel", null).show();
    }

    private void play(String res) {
        String key = "requested".equals(res) ? "ok" : "rescan".equals(res) ? "rescan"
                : ("not_scanned".equals(res) || "no_courier".equals(res)) ? "not_found" : "duplicate";
        String def = "ok".equals(key) ? "beep" : "rescan".equals(key) ? "rescan" : "not_found".equals(key) ? "warn" : "error";
        sounds.play(soundCfg.optString(key, def));
        sounds.buzz(key);
    }

    private void submit(final String raw) {
        final String code = raw == null ? "" : raw.trim().replaceAll("\\s+", "").toUpperCase();
        if (code.isEmpty()) return;
        if (busy) { sounds.play("rescan"); return; }
        busy = true;
        prompt.setText("⏳ Asking Flipkart… " + code);
        net.execute(new Runnable() {
            @Override public void run() {
                JSONObject r;
                try {
                    JSONObject a = new JSONObject();
                    a.put("loc", loc); a.put("day", day); a.put("code", code);
                    a.put("client_id", "obd-" + UUID.randomUUID().toString().substring(0, 12));
                    a.put("device", Build.MANUFACTURER + " " + Build.MODEL + " app" + AppInfo.VERSION_NAME);
                    r = Api.post("/api/obd/request", a);
                } catch (Exception e) {
                    r = new JSONObject();
                    try { r.put("ok", false); r.put("error", "No internet - Request OBD needs the network. Try again."); } catch (Exception ignored) { }
                }
                final JSONObject res = r;
                ui.post(new Runnable() {
                    @Override public void run() {
                        busy = false;
                        prompt.setText("Scan a packet to Request OBD");
                        if (!res.optBoolean("ok")) {
                            play("error");
                            showError(code, res.optString("error", "Failed"));
                            return;
                        }
                        JSONObject x = res.optJSONObject("req");
                        play(x.optString("res"));
                        show(x);
                        load(false);
                    }
                });
            }
        });
    }

    private static int colorOf(String res) {
        if ("requested".equals(res)) return Ui.OK;
        if ("not_scanned".equals(res) || "no_courier".equals(res)) return Ui.WARN;
        if ("rescan".equals(res)) return Ui.GREY;
        return Ui.ERR;
    }

    private static String head(String res, String text) {
        switch (res) {
            case "requested": return "✔ OBD REQUESTED - KEEP ASIDE";
            case "already": return "✖ OBD ALREADY REQUESTED";
            case "not_eligible": return "✖ OBD NOT ALLOWED";
            case "not_scanned": return "⚠ COURIER HAS NOT SCANNED IT";
            case "no_courier": return "⚠ NO COURIER ON HANDOVER";
            case "quota": return "✖ OBD LIMIT USED UP";
            case "rescan": return "↻ SCAN AGAIN";
            default: return "✖ " + (text == null || text.isEmpty() ? "ERROR" : text.toUpperCase());
        }
    }

    private void showError(String code, String msg) {
        card.removeAllViews();
        LinearLayout c = Ui.col(this);
        c.setBackground(Ui.box(Ui.WHITE, Ui.ERR, 12));
        TextView h = Ui.text(this, "✖ " + msg, 15, Ui.WHITE, true);
        h.setBackgroundColor(Ui.ERR);
        Ui.pad(h, 10);
        c.addView(h);
        TextView t = Ui.mono(this, code, 14, Ui.INK, true);
        Ui.pad(t, 10);
        c.addView(t);
        card.addView(c, new LinearLayout.LayoutParams(-1, -2));
    }

    private void show(JSONObject x) {
        card.removeAllViews();
        String res = x.optString("res");
        int col = colorOf(res);
        LinearLayout c = Ui.col(this);
        c.setBackground(Ui.box(Ui.WHITE, col, 12));
        ((android.graphics.drawable.GradientDrawable) c.getBackground()).setStroke(Ui.dp(3), col);
        TextView h = Ui.text(this, head(res, x.optString("text")), 16, Ui.WHITE, true);
        h.setBackgroundColor(col);
        Ui.pad(h, 10);
        c.addView(h);
        LinearLayout r = Ui.row(this);
        r.setPadding(Ui.dp(10), Ui.dp(8), Ui.dp(10), Ui.dp(8));
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int size = (int) (getResources().getDisplayMetrics().widthPixels * 0.28);
        r.addView(iv, new LinearLayout.LayoutParams(size, size));
        Images.load(iv, x.optString("im"), 400);
        LinearLayout info = Ui.col(this);
        info.setPadding(Ui.dp(10), 0, 0, 0);
        info.addView(Ui.mono(this, x.optString("sku").isEmpty() ? x.optString("code") : x.optString("sku"), 16, Ui.INK, true));
        if (!x.optString("fe").isEmpty()) info.addView(Ui.text(this, "🛵 " + x.optString("fe"), 13, Ui.INK, true));
        info.addView(Ui.text(this, x.optString("msg"), 12, Ui.INK2, false));
        info.addView(Ui.mono(this, x.optString("t"), 11, Ui.INK2, false));
        r.addView(info, Ui.lp(0, -2, 1));
        c.addView(r);
        card.addView(c, new LinearLayout.LayoutParams(-1, -2));
    }

    private void load(final boolean status) {
        net.execute(new Runnable() {
            @Override public void run() {
                try {
                    final JSONObject r = Api.get("/api/obd?loc=" + Api.encode(loc) + "&day=" + day + (status ? "&status=1&force=1" : ""));
                    ui.post(new Runnable() { @Override public void run() { render(r); } });
                } catch (final Exception e) {
                    ui.post(new Runnable() { @Override public void run() { liveText.setText("No connection: " + e.getMessage()); } });
                }
            }
        });
    }

    private void render(JSONObject r) {
        JSONObject live = r.optJSONObject("live");
        StringBuilder sb = new StringBuilder();
        if (live == null) sb.append("Flipkart not reachable: ").append(r.optString("error"));
        else {
            sb.append("OBD left today: ").append(live.isNull("remaining") ? "?" : live.optString("remaining"));
            if (live.optInt("quota") > 0) sb.append(" of ").append(live.optInt("quota"));
            JSONArray cs = live.optJSONArray("couriers");
            if (cs == null || cs.length() == 0) sb.append("\nNo courier on Flipkart's handover screen right now.");
            else for (int i = 0; i < cs.length(); i++) {
                JSONObject f = cs.optJSONObject(i);
                sb.append("\n🛵 ").append(f.optString("fe_name")).append(" · ").append(f.optString("status"))
                  .append(" · scanned ").append(f.optInt("scanned"));
                if (f.optInt("obd_requested") > 0) sb.append(" · OBD ").append(f.optInt("obd_requested"));
            }
        }
        liveText.setText(sb.toString());
        list.removeAllViews();
        JSONArray reqs = r.optJSONArray("requests");
        if (reqs == null || reqs.length() == 0) { list.addView(Ui.text(this, "No OBD requests yet.", 12, Ui.INK2, false)); return; }
        for (int i = 0; i < reqs.length(); i++) {
            JSONObject x = reqs.optJSONObject(i);
            LinearLayout row = Ui.row(this);
            row.setBackground(Ui.box(Ui.WHITE, Ui.LINE, 8));
            row.setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6));
            View bar = new View(this);
            bar.setBackgroundColor(colorOf(x.optString("res")));
            row.addView(bar, new LinearLayout.LayoutParams(Ui.dp(4), -1));
            LinearLayout t = Ui.col(this);
            t.setPadding(Ui.dp(8), 0, 0, 0);
            t.addView(Ui.mono(this, (x.optString("sku").isEmpty() ? x.optString("code") : x.optString("sku")), 13, Ui.INK, true));
            String line = x.optString("text") + (x.optString("fe").isEmpty() ? "" : " · " + x.optString("fe")) + " · " + Ui.time(x.optDouble("at"));
            t.addView(Ui.text(this, line, 11, Ui.INK2, false));
            if (!x.optString("fs").isEmpty())
                t.addView(Ui.text(this, "Flipkart: " + x.optString("fs") + (x.optString("remark").isEmpty() ? "" : " · " + x.optString("remark")), 11, Ui.INK, true));
            row.addView(t, Ui.lp(0, -2, 1));
            list.addView(row, Ui.lpm(-1, -2, 0, 0, 0, 4));
        }
    }
}
