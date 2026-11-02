package com.hikestore.scan;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Log in with the same HikeOne user as the website: password, then Authenticator or email code. */
public class LoginActivity extends Activity {
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private EditText user, pass, code, server;
    private TextView msg, codeLabel;
    private LinearLayout step1, step2, methods;
    private Button go, verify, sendMail;
    private ProgressBar spin;
    private String method = "totp";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Api.init(this);
        Ui.init(this);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);
        LinearLayout outer = Ui.col(this);
        outer.setGravity(Gravity.CENTER);
        outer.setPadding(Ui.dp(16), Ui.dp(24), Ui.dp(16), Ui.dp(24));
        sv.addView(outer);
        LinearLayout card = Ui.col(this);
        card.setBackground(Ui.box(Ui.WHITE, Ui.LINE, 14));
        Ui.pad(card, 20);
        outer.addView(card, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView logo = Ui.text(this, "📦  Hike Scan", 22, Ui.INK, true);
        card.addView(logo);
        TextView sub = Ui.text(this, "Return scanning · log in with your HikeOne user", 13, Ui.INK2, false);
        sub.setPadding(0, Ui.dp(2), 0, Ui.dp(14));
        card.addView(sub);

        step1 = Ui.col(this);
        card.addView(step1);
        user = field("Username", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, step1);
        pass = field("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, step1);
        pass.setImeOptions(EditorInfo.IME_ACTION_GO);
        go = Ui.button(this, "Continue", true);
        step1.addView(go, Ui.lpm(-1, Ui.dp(46), 0, 8, 0, 0));
        final TextView adv = Ui.text(this, "Server: " + Api.server(), 12, Ui.INK2, false);
        adv.setPadding(0, Ui.dp(14), 0, 0);
        step1.addView(adv);
        server = field("Server address", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, step1);
        server.setText(Api.server());
        ((View) server.getTag()).setVisibility(View.GONE);
        server.setVisibility(View.GONE);
        adv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                server.setVisibility(View.VISIBLE);
                ((View) server.getTag()).setVisibility(View.VISIBLE);
            }
        });

        step2 = Ui.col(this);
        step2.setVisibility(View.GONE);
        card.addView(step2);
        TextView t2 = Ui.text(this, "One more step - enter your login code", 15, Ui.INK, true);
        step2.addView(t2);
        methods = Ui.row(this);
        methods.setPadding(0, Ui.dp(10), 0, Ui.dp(6));
        step2.addView(methods);
        sendMail = Ui.button(this, "✉  Send code to my email", false);
        step2.addView(sendMail, Ui.lpm(-1, Ui.dp(44), 0, 4, 0, 4));
        codeLabel = Ui.text(this, "Code from Google Authenticator", 13, Ui.INK2, true);
        step2.addView(codeLabel);
        code = new EditText(this);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setTextSize(26);
        code.setGravity(Gravity.CENTER);
        code.setLetterSpacing(0.3f);
        code.setImeOptions(EditorInfo.IME_ACTION_DONE);
        step2.addView(code, Ui.lpm(-1, -2, 0, 4, 0, 4));
        verify = Ui.button(this, "Log in", true);
        step2.addView(verify, Ui.lpm(-1, Ui.dp(46), 0, 6, 0, 0));
        Button back = Ui.button(this, "← Start again", false);
        step2.addView(back, Ui.lpm(-1, Ui.dp(40), 0, 10, 0, 0));

        spin = new ProgressBar(this);
        spin.setVisibility(View.GONE);
        card.addView(spin, Ui.lpm(-2, -2, 0, 10, 0, 0));
        msg = Ui.text(this, "", 14, Ui.ERR, true);
        msg.setPadding(0, Ui.dp(10), 0, 0);
        card.addView(msg);

        TextView ver = Ui.text(this, "Version " + AppInfo.VERSION_NAME, 11, Ui.INK2, false);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, Ui.dp(12), 0, 0);
        outer.addView(ver);
        setContentView(sv);

        go.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { doPassword(); } });
        pass.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, android.view.KeyEvent e) { doPassword(); return true; }
        });
        verify.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { doCode(); } });
        code.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, android.view.KeyEvent e) { doCode(); return true; }
        });
        sendMail.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { doSendMail(); } });
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { step2.setVisibility(View.GONE); step1.setVisibility(View.VISIBLE); msg.setText(""); }
        });
        String last = getSharedPreferences("api", MODE_PRIVATE).getString("last_user", "");
        user.setText(last);
        if (!last.isEmpty()) pass.requestFocus();
        String why = getIntent().getStringExtra("why");
        if (why != null) msg.setText(why);
    }

    private EditText field(String label, int type, LinearLayout parent) {
        TextView l = Ui.text(this, label, 13, Ui.INK2, true);
        l.setPadding(0, Ui.dp(8), 0, Ui.dp(2));
        parent.addView(l);
        EditText e = new EditText(this);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextSize(17);
        e.setTag(l);
        parent.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    private void busy(boolean on) {
        spin.setVisibility(on ? View.VISIBLE : View.GONE);
        go.setEnabled(!on);
        verify.setEnabled(!on);
        sendMail.setEnabled(!on);
    }

    private void doPassword() {
        final String u = user.getText().toString().trim(), p = pass.getText().toString();
        if (u.isEmpty() || p.isEmpty()) { msg.setText("Enter username and password."); return; }
        if (server.getVisibility() == View.VISIBLE) Api.setServer(server.getText().toString());
        msg.setText("");
        busy(true);
        bg.execute(new Runnable() {
            @Override public void run() {
                Api.LoginStep st;
                try { st = Api.loginPassword(u, p); }
                catch (Exception e) { st = new Api.LoginStep(); st.error = "No connection to " + Api.server() + " (" + e.getMessage() + ")"; }
                final Api.LoginStep s = st;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        busy(false);
                        if (s.error != null) { msg.setText(s.error); return; }
                        getSharedPreferences("api", MODE_PRIVATE).edit().putString("last_user", u).apply();
                        showMethods(s.methods);
                    }
                });
            }
        });
    }

    private void showMethods(List<String[]> list) {
        step1.setVisibility(View.GONE);
        step2.setVisibility(View.VISIBLE);
        methods.removeAllViews();
        boolean hasTotp = false, hasMail = false;
        for (String[] m : list) { if ("totp".equals(m[0])) hasTotp = true; if ("email".equals(m[0])) hasMail = true; }
        method = hasTotp ? "totp" : (hasMail ? "email" : list.get(0)[0]);
        for (final String[] m : list) {
            final Button b = Ui.button(this, m[1], m[0].equals(method));
            b.setTextSize(13);
            LinearLayout.LayoutParams p = Ui.lp(0, Ui.dp(40), 1);
            p.setMargins(0, 0, Ui.dp(6), 0);
            methods.addView(b, p);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { method = m[0]; showMethods(listCopy()); }
            });
        }
        lastList = list;
        sendMail.setVisibility("email".equals(method) ? View.VISIBLE : View.GONE);
        codeLabel.setText("email".equals(method) ? "Code from the email" : "Code from Google Authenticator");
        methods.setVisibility(list.size() > 1 ? View.VISIBLE : View.GONE);
        code.requestFocus();
    }

    private List<String[]> lastList;
    private List<String[]> listCopy() { return lastList; }

    private void doSendMail() {
        busy(true);
        bg.execute(new Runnable() {
            @Override public void run() {
                String r;
                try { r = Api.sendEmailCode(); } catch (Exception e) { r = "Could not send: " + e.getMessage(); }
                final String f = r;
                runOnUiThread(new Runnable() {
                    @Override public void run() { busy(false); msg.setTextColor(Ui.OK); msg.setText(f); }
                });
            }
        });
    }

    private void doCode() {
        final String c = code.getText().toString().trim();
        if (c.length() < 6) { msg.setTextColor(Ui.ERR); msg.setText("Enter the 6-digit code."); return; }
        busy(true);
        bg.execute(new Runnable() {
            @Override public void run() {
                Api.LoginStep st;
                try { st = Api.loginCode(method, c); }
                catch (Exception e) { st = new Api.LoginStep(); st.error = "No connection (" + e.getMessage() + ")"; }
                final Api.LoginStep s = st;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        busy(false);
                        if (!s.done) { msg.setTextColor(Ui.ERR); msg.setText(s.error); return; }
                        startActivity(new Intent(LoginActivity.this, MainActivity.class));
                        finish();
                    }
                });
            }
        });
    }
}
