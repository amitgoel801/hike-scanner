package com.hikestore.scan;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen of Hike Scan: one app for every marketplace.
 *   Home      -> Flipkart / Myntra / Amazon / Others (all mix)
 *   Flipkart  -> Return scanning / Request OBD
 *   Others    -> Ajio / FirstCry / Snapdeal / Meesho (coming soon)
 * Big square tiles; works with touch AND with keypad phones (arrow keys + OK, or number keys 1-4).
 * Tiles use each marketplace's colour and initial (not the official logos).
 */
public class HomeActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private static final ExecutorService net = Executors.newSingleThreadExecutor();
    private String level = "home";
    private LinearLayout grid;
    private TextView title, sub, status;
    private final List<View> tiles = new ArrayList<>();

    private static class Tile {
        final String key, name, sub, badge;
        final int bg, fg;
        Tile(String key, String name, String sub, String badge, int bg, int fg) {
            this.key = key; this.name = name; this.sub = sub; this.badge = badge; this.bg = bg; this.fg = fg;
        }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Api.init(this);
        Images.init(this);
        Ui.init(this);
        if (!Api.loggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        if (b != null) level = b.getString("level", "home");
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(0xFF0F1420);
        LinearLayout top = Ui.row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(6));
        LinearLayout tt = Ui.col(this);
        title = Ui.text(this, "", 20, Ui.WHITE, true);
        sub = Ui.text(this, "", 12, 0xFF9AA6BB, false);
        tt.addView(title);
        tt.addView(sub);
        top.addView(tt, Ui.lp(0, -2, 1));
        root.addView(top);
        status = Ui.text(this, "", 12, 0xFFB8C0CC, false);
        status.setPadding(Ui.dp(12), 0, Ui.dp(12), Ui.dp(6));
        root.addView(status);
        ScrollView sv = new ScrollView(this);
        grid = Ui.col(this);
        grid.setPadding(Ui.dp(8), Ui.dp(4), Ui.dp(8), Ui.dp(12));
        sv.addView(grid);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView foot = Ui.text(this, "Keypad: ▲▼◀▶ to move, OK to open, or press 1-4 · Back = previous screen", 11, 0xFF6B7588, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(Ui.dp(8), Ui.dp(4), Ui.dp(8), Ui.dp(8));
        root.addView(foot);
        setContentView(root);
        show(level);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("level", level);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!Api.loggedIn()) return;
        renderStatus();
        sendPending(this, new Runnable() { @Override public void run() { renderStatus(); } });
    }

    private void renderStatus() {
        int n = Store.get(this).unsentCount();
        String dev = Api.deviceName();
        status.setText((dev.isEmpty() ? "" : "📱 " + dev + " · ") + "app " + AppInfo.VERSION_NAME + " · " +
                (n > 0 ? "⏳ " + n + " scans waiting to send" : "✔ all scans saved"));
    }

    private List<Tile> tilesFor(String lv) {
        List<Tile> t = new ArrayList<>();
        switch (lv) {
            case "flipkart":
                t.add(new Tile("returns", "Return scanning", "Scan return packets · tasks", "📦", 0xFF2874F0, 0xFFFFE11B));
                t.add(new Tile("obd", "Request OBD", "Open box delivery", "📭", 0xFF1E5BC6, 0xFFFFE11B));
                break;
            case "others":
                t.add(new Tile("ajio", "Ajio", "Coming soon", "A", 0xFF2C4152, 0xFFE8D7B0));
                t.add(new Tile("firstcry", "FirstCry", "Coming soon", "FC", 0xFFFF7043, Ui.WHITE));
                t.add(new Tile("snapdeal", "Snapdeal", "Coming soon", "S", 0xFFE40046, Ui.WHITE));
                t.add(new Tile("meesho", "Meesho", "Coming soon", "M", 0xFF9F2089, Ui.WHITE));
                break;
            case "myntra":
            case "amazon":
                t.add(new Tile("soon", "Coming soon", "Returns for this marketplace will be added here", "⏳",
                        "myntra".equals(lv) ? 0xFFFF3F6C : 0xFF232F3E, Ui.WHITE));
                break;
            default:
                t.add(new Tile("flipkart", "Flipkart", "Returns · OBD", "F", 0xFF2874F0, 0xFFFFE11B));
                t.add(new Tile("myntra", "Myntra", "Coming soon", "M", 0xFFFF3F6C, Ui.WHITE));
                t.add(new Tile("amazon", "Amazon", "Coming soon", "a", 0xFF232F3E, 0xFFFF9900));
                t.add(new Tile("others", "Others", "All mix · Ajio · FirstCry · Snapdeal · Meesho", "+", 0xFF6D28D9, Ui.WHITE));
        }
        return t;
    }

    private void show(String lv) {
        level = lv;
        switch (lv) {
            case "flipkart": title.setText("← Flipkart"); sub.setText("Choose what to do"); break;
            case "others": title.setText("← Others (all mix)"); sub.setText("Ajio · FirstCry · Snapdeal · Meesho"); break;
            case "myntra": title.setText("← Myntra"); sub.setText(""); break;
            case "amazon": title.setText("← Amazon"); sub.setText(""); break;
            default: title.setText("📦 Hike Scan"); sub.setText("Choose a marketplace");
        }
        title.setOnClickListener("home".equals(lv) ? null : new View.OnClickListener() { @Override public void onClick(View v) { show("home"); } });
        grid.removeAllViews();
        tiles.clear();
        List<Tile> list = tilesFor(lv);
        int w = getResources().getDisplayMetrics().widthPixels - Ui.dp(16);
        int side = Math.min(w / 2 - Ui.dp(8), Ui.dp(220));
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % 2 == 0) {
                row = Ui.row(this);
                row.setGravity(Gravity.CENTER_HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            View v = tile(list.get(i), i + 1, list.size() == 1 ? side * 2 + Ui.dp(8) : side, side);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(list.size() == 1 ? side * 2 + Ui.dp(8) : side, side);
            lp.setMargins(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
            row.addView(v, lp);
            tiles.add(v);
        }
        if (!tiles.isEmpty()) tiles.get(0).requestFocus();
    }

    private View tile(final Tile t, int no, int width, int height) {
        FrameLayout f = new FrameLayout(this);
        final GradientDrawable bg = Ui.box(t.bg, t.bg, 18);
        f.setBackground(bg);
        f.setFocusable(true);
        f.setFocusableInTouchMode(false);
        f.setClickable(true);
        LinearLayout c = Ui.col(this);
        c.setGravity(Gravity.CENTER);
        c.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), Ui.dp(8));
        TextView badge = Ui.text(this, t.badge, t.badge.length() > 1 && !t.badge.matches(".*[^A-Za-z].*") ? 26 : 34, t.fg, true);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x33FFFFFF);
        badge.setBackground(circle);
        int bs = Math.min(height / 2 - Ui.dp(6), Ui.dp(84));
        c.addView(badge, new LinearLayout.LayoutParams(bs, bs));
        TextView name = Ui.text(this, t.name, 19, Ui.WHITE, true);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, Ui.dp(8), 0, 0);
        c.addView(name);
        TextView s = Ui.text(this, t.sub, 11, 0xDDFFFFFF, false);
        s.setGravity(Gravity.CENTER);
        s.setMaxLines(2);
        c.addView(s);
        f.addView(c, new FrameLayout.LayoutParams(-1, -1));
        TextView num = Ui.text(this, String.valueOf(no), 12, 0xCCFFFFFF, true);
        num.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(6));
        f.addView(num, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
        f.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                bg.setStroke(Ui.dp(has ? 5 : 0), has ? 0xFFFFE11B : t.bg);
                v.setScaleX(has ? 1.03f : 1f);
                v.setScaleY(has ? 1.03f : 1f);
            }
        });
        f.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { open(t.key); } });
        return f;
    }

    private void open(String key) {
        switch (key) {
            case "flipkart": case "others": case "myntra": case "amazon": show(key); break;
            case "returns": startActivity(new Intent(this, MainActivity.class)); break;
            case "obd": openObd(); break;
            default: Toast.makeText(this, "Coming soon - this marketplace will be added in an update.", Toast.LENGTH_SHORT).show();
        }
    }

    private void openObd() {
        JSONObject me = Api.cachedMe();
        SharedPreferences dev = getSharedPreferences("device", MODE_PRIVATE);
        String loc = dev.getString("loc", me.optString("default_location", ""));
        String name = loc;
        JSONArray ls = me.optJSONArray("locations");
        if (ls != null) for (int i = 0; i < ls.length(); i++)
            if (loc.equals(ls.optJSONObject(i).optString("id"))) name = ls.optJSONObject(i).optString("name");
        if (loc.isEmpty()) { startActivity(new Intent(this, MainActivity.class)); return; }
        ObdActivity.start(this, loc, Ui.today(), name);
    }

    @Override public void onBackPressed() {
        if (!"home".equals(level)) show("home");
        else super.onBackPressed();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int kc = e.getKeyCode();
            if (kc >= KeyEvent.KEYCODE_1 && kc <= KeyEvent.KEYCODE_9) {
                int i = kc - KeyEvent.KEYCODE_1;
                if (i < tiles.size()) { tiles.get(i).requestFocus(); tiles.get(i).performClick(); return true; }
            }
            if (kc == KeyEvent.KEYCODE_0 || kc == KeyEvent.KEYCODE_DEL) { if (!"home".equals(level)) { show("home"); return true; } }
        }
        return super.dispatchKeyEvent(e);
    }

    // ------------------------------------------------------------------ send scans still on the phone
    /** Sends scans that are saved on the phone but not yet on the server (the scan screen may be closed). */
    static void sendPending(final Activity a, final Runnable done) {
        final Store store = Store.get(a);
        if (store.unsentCount() == 0) return;
        net.execute(new Runnable() {
            @Override public void run() {
                try {
                    for (Store.Scan s : store.unsent(100)) {
                        JSONObject x = new JSONObject();
                        x.put("loc", s.loc); x.put("day", s.day); x.put("code", s.raw); x.put("client_id", s.cid);
                        x.put("at", s.at); x.put("device", Build.MANUFACTURER + " " + Build.MODEL + " app" + AppInfo.VERSION_NAME);
                        x.put("local", s.localRes);
                        if (s.manual) x.put("manual", true);
                        if (s.batchId > 0) x.put("batch_id", s.batchId);
                        JSONObject r = Api.post("/api/scan", x);
                        if (!r.optBoolean("ok")) { store.markError(s.cid, r.optString("error")); continue; }
                        JSONObject sc = r.optJSONObject("scan");
                        store.markSent(s.cid, sc.optString("res"), sc.optLong("id"), sc.optString("t"));
                    }
                } catch (Exception ignored) { }
                if (done != null) a.runOnUiThread(done);
            }
        });
    }
}
