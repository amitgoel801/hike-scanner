package com.hikestore.scan;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Connect this device": no username, password or Authenticator. The admin shows a QR code in
 * Return scanning > Settings > Worker devices; the worker scans it with the handheld's scanner
 * (or the phone camera, then "Open in the Hike Scan app"), gives the device a name, done.
 */
public class LoginActivity extends Activity {
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private EditText code, name, server;
    private TextView msg;
    private Button go;
    private ProgressBar spin;

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String t = MainActivity.readScan(i);
            if (t != null) takeScan(t);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Api.init(this);
        Ui.init(this);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);
        LinearLayout outer = Ui.col(this);
        outer.setGravity(Gravity.CENTER);
        outer.setPadding(Ui.dp(14), Ui.dp(20), Ui.dp(14), Ui.dp(20));
        sv.addView(outer);
        LinearLayout card = Ui.col(this);
        card.setBackground(Ui.box(Ui.WHITE, Ui.LINE, 14));
        Ui.pad(card, 18);
        outer.addView(card, new LinearLayout.LayoutParams(-1, -2));

        card.addView(Ui.text(this, "📦  Hike Scan", 22, Ui.INK, true));
        TextView sub = Ui.text(this, "Connect this device - no ID or password needed", 13, Ui.INK2, false);
        sub.setPadding(0, Ui.dp(2), 0, Ui.dp(12));
        card.addView(sub);

        TextView how = Ui.text(this,
                "1. Ask the admin to open Return scanning → Settings → Worker devices → Add device (QR code).\n" +
                "2. Point this scanner at the QR code and press the trigger.\n" +
                "   Phone without a scanner: open the phone camera, scan the QR code, then tap \"Open in the Hike Scan app\".\n" +
                "   Or type the 8-letter code under the QR code.", 13, Ui.INK, false);
        how.setBackground(Ui.box(0xFFF4F5F7, 0, 10));
        Ui.pad(how, 10);
        card.addView(how);

        card.addView(label("Code from the QR"));
        code = new EditText(this);
        code.setSingleLine(true);
        code.setTextSize(22);
        code.setLetterSpacing(0.15f);
        code.setHint("ABCD-2345");
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        card.addView(code, new LinearLayout.LayoutParams(-1, -2));
        card.addView(label("Name for this device (e.g. Ramesh or Scanner 2)"));
        name = new EditText(this);
        name.setSingleLine(true);
        name.setTextSize(17);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        card.addView(name, new LinearLayout.LayoutParams(-1, -2));
        go = Ui.button(this, "Connect this device", true);
        card.addView(go, Ui.lpm(-1, Ui.dp(48), 0, 12, 0, 0));
        spin = new ProgressBar(this);
        spin.setVisibility(View.GONE);
        card.addView(spin, Ui.lpm(-2, -2, 0, 10, 0, 0));
        msg = Ui.text(this, "", 14, Ui.ERR, true);
        msg.setPadding(0, Ui.dp(10), 0, 0);
        card.addView(msg);

        final TextView adv = Ui.text(this, "Server: " + Api.server(), 12, Ui.INK2, false);
        adv.setPadding(0, Ui.dp(14), 0, 0);
        card.addView(adv);
        server = new EditText(this);
        server.setSingleLine(true);
        server.setText(Api.server());
        server.setVisibility(View.GONE);
        card.addView(server, new LinearLayout.LayoutParams(-1, -2));
        adv.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { server.setVisibility(View.VISIBLE); } });

        TextView ver = Ui.text(this, "Version " + AppInfo.VERSION_NAME, 11, Ui.INK2, false);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, Ui.dp(12), 0, 0);
        outer.addView(ver);
        setContentView(sv);

        go.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { connect(); } });
        code.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void afterTextChanged(Editable s) {
                String v = s.toString();
                if (v.contains("://") || v.indexOf('\n') >= 0) takeScan(v);      // the scanner typed the QR text
            }
        });
        String why = getIntent().getStringExtra("why");
        if (why != null) msg.setText(why);
        String last = getSharedPreferences("api", MODE_PRIVATE).getString("last_name", "");
        name.setText(last);
        handleLink(getIntent());
        code.requestFocus();
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handleLink(i); }

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

    /** opened from the connect page in the phone browser: hikescan://pair?c=CODE&s=SERVER */
    private void handleLink(Intent i) {
        Uri u = i == null ? null : i.getData();
        if (u != null) takeScan(u.toString());
    }

    private TextView label(String s) {
        TextView l = Ui.text(this, s, 13, Ui.INK2, true);
        l.setPadding(0, Ui.dp(12), 0, Ui.dp(2));
        return l;
    }

    private void takeScan(String text) {
        String[] p = Api.parsePairText(text);
        if (p[1] != null && p[1].startsWith("http")) { Api.setServer(p[1]); server.setText(Api.server()); }
        code.setText(p[0].length() == 8 ? p[0].substring(0, 4) + "-" + p[0].substring(4) : p[0]);
        code.setSelection(code.getText().length());
        msg.setTextColor(Ui.OK);
        msg.setText("QR code read ✔" + (name.getText().toString().trim().isEmpty() ? " - now type a name for this device" : ""));
        if (name.getText().toString().trim().isEmpty()) name.requestFocus();
    }

    private void connect() {
        final String c = Api.parsePairText(code.getText().toString())[0];
        final String n = name.getText().toString().trim();
        msg.setTextColor(Ui.ERR);
        if (c.length() < 6) { msg.setText("Scan the QR code or type the code."); return; }
        if (n.isEmpty()) { msg.setText("Type a name for this device."); return; }
        if (server.getVisibility() == View.VISIBLE) Api.setServer(server.getText().toString());
        spin.setVisibility(View.VISIBLE);
        go.setEnabled(false);
        msg.setText("");
        getSharedPreferences("api", MODE_PRIVATE).edit().putString("last_name", n).apply();
        bg.execute(new Runnable() {
            @Override public void run() {
                String err;
                try { err = Api.pair(c, n, Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE + " · app " + AppInfo.VERSION_NAME); }
                catch (Exception e) { err = "No connection to " + Api.server() + " (" + e.getMessage() + ")"; }
                final String e2 = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        spin.setVisibility(View.GONE);
                        go.setEnabled(true);
                        if (e2 != null) { msg.setTextColor(Ui.ERR); msg.setText(e2); return; }
                        startActivity(new Intent(LoginActivity.this, HomeActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                        finish();
                    }
                });
            }
        });
    }
}
