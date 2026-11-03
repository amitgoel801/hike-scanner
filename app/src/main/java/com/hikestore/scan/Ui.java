package com.hikestore.scan;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Small helpers to build the screens in code (works the same on every Android version). */
public final class Ui {
    public static final int BG = 0xFFF4F5F7, INK = 0xFF151A23, INK2 = 0xFF5B6475, LINE = 0xFFE3E6EB, DARK = 0xFF121826,
            ACCENT = 0xFFE8590C, OK = 0xFF15803D, OKBG = 0xFFE8F7EE, ERR = 0xFFC81E1E, ERRBG = 0xFFFDECEC,
            WARN = 0xFFC2570C, WARNBG = 0xFFFFF1E3, GREY = 0xFF5B6475, GREYBG = 0xFFEEF0F4, WHITE = 0xFFFFFFFF,
            PAUSEBG = 0xFFFFF7E0, PAUSE = 0xFF8A5A00, CUST = 0xFF1D4ED8, CUSTBG = 0xFFE8F0FF, COUR = 0xFF7E22CE, COURBG = 0xFFF3E8FF;
    public static final TimeZone IST = TimeZone.getTimeZone("Asia/Kolkata");
    private static float density = 2f;

    private Ui() {}

    public static void init(Context c) { density = c.getResources().getDisplayMetrics().density; }

    public static int dp(float v) { return Math.round(v * density); }

    public static GradientDrawable box(int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static TextView mono(Context c, String s, float sp, int color, boolean bold) {
        TextView t = text(c, s, sp, color, bold);
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    public static Button button(Context c, String s, boolean primary) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? WHITE : INK);
        b.setBackground(box(primary ? ACCENT : WHITE, primary ? 0 : 0xFFCDD2DA, 8));
        b.setMinHeight(dp(40));
        b.setMinimumHeight(dp(40));
        b.setPadding(dp(12), 0, dp(12), 0);
        return b;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    public static LinearLayout.LayoutParams lpm(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    public static void pad(View v, int dp) { v.setPadding(dp(dp), dp(dp), dp(dp), dp(dp)); }

    public static int parse(String s) { return Color.parseColor(s); }

    // ---------------------------------------------------------- dates, DD Mon YYYY
    public static String today() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(IST);
        return f.format(new Date());
    }

    /** "2026-11-03" moved by n days */
    public static String shift(String iso, int n) {
        try {
            java.util.Calendar c = java.util.Calendar.getInstance(IST);
            String[] p = iso.split("-");
            c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]), 12, 0, 0);
            c.add(java.util.Calendar.DAY_OF_MONTH, n);
            return String.format(java.util.Locale.US, "%04d-%02d-%02d", c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
                    c.get(java.util.Calendar.DAY_OF_MONTH));
        } catch (Exception e) { return iso; }
    }

    public static String day(String iso) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            SimpleDateFormat out = new SimpleDateFormat("dd MMM yyyy", Locale.US);
            return out.format(in.parse(iso));
        } catch (Exception e) { return iso; }
    }

    public static String time(double epoch) {
        if (epoch <= 0) return "-";
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.US);
        f.setTimeZone(IST);
        return f.format(new Date((long) (epoch * 1000)));
    }

    public static String dateTime(double epoch) {
        if (epoch <= 0) return "-";
        SimpleDateFormat f = new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US);
        f.setTimeZone(IST);
        return f.format(new Date((long) (epoch * 1000)));
    }

    public static String plural(int n, String w) { return n + " " + w + (n == 1 ? "" : "s"); }

    public static String nice(String s) {
        if (s == null || s.isEmpty()) return "";
        s = s.replace('_', ' ').trim();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
