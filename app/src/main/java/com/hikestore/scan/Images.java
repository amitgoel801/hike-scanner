package com.hikestore.scan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Product photos from Flipkart's image server.
 * Every photo is kept ON THE PHONE (one small 400 px copy per photo), so photos show
 * instantly and also with no internet. After each list refresh, prefetch() downloads the
 * photos of the whole list in the background (about 10-20 KB each, ~25 MB for 2,000 packets).
 * Photos not used for 15 days are removed.
 */
public final class Images {
    private static final int DISK_PX = 400;
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService pool = Executors.newFixedThreadPool(3);
    private static final ExecutorService pre = Executors.newSingleThreadExecutor();
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static final Pattern PATH = Pattern.compile("/image/(.+)$");
    private static File dir;
    private static volatile int preTotal = 0, preDone = 0;
    private static volatile long preGen = 0;

    private Images() {}

    public static synchronized void init(Context c) {
        if (dir != null) return;
        dir = new File(c.getFilesDir(), "photos");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        pre.execute(new Runnable() {
            @Override public void run() {
                File[] fs = dir.listFiles();
                long old = System.currentTimeMillis() - 15L * 86400000L;
                if (fs != null) for (File f : fs) if (f.lastModified() < old) //noinspection ResultOfMethodCallIgnored
                    f.delete();
            }
        });
    }

    /** "1,234 of 2,097 photos on this phone" while the background download runs; null when done. */
    public static String progress() {
        if (preTotal == 0 || preDone >= preTotal) return null;
        return preDone + " of " + preTotal + " photos saved on phone";
    }

    /** Flipkart's resizer: rukminim2.flixcart.com/image/<w>/<h>/<path>?q=80 */
    static String sized(String url, int px) {
        Matcher m = PATH.matcher(url);
        if (!m.find() || m.group(1).matches("^\\d+/\\d+/.*")) return url;
        return "https://rukminim2.flixcart.com/image/" + px + "/" + px + "/" + m.group(1) + "?q=80";
    }

    private static File fileFor(String url) {
        if (dir == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return new File(dir, sb.toString() + ".jpg");
        } catch (Exception e) { return null; }
    }

    public static void load(final ImageView v, final String url, final int px) {
        v.setTag(url);
        if (url == null || url.isEmpty()) { v.setImageResource(R.drawable.ic_shoe); return; }
        final String key = url + "@" + px;
        Bitmap b = cache.get(key);
        if (b != null) { v.setImageBitmap(b); return; }
        v.setImageResource(R.drawable.ic_shoe);
        pool.execute(new Runnable() {
            @Override public void run() {
                byte[] data = bytes(url, true);
                Bitmap bm = data == null ? null : decode(data, px);
                if (bm == null) return;
                cache.put(key, bm);
                final Bitmap f = bm;
                ui.post(new Runnable() {
                    @Override public void run() { if (url.equals(v.getTag())) v.setImageBitmap(f); }
                });
            }
        });
    }

    /** The photo from the phone; if missing, download it once and keep it. */
    private static byte[] bytes(String url, boolean download) {
        File f = fileFor(url);
        if (f != null && f.length() > 0) {
            try {
                RandomAccessFile r = new RandomAccessFile(f, "r");
                byte[] d = new byte[(int) r.length()];
                r.readFully(d);
                r.close();
                //noinspection ResultOfMethodCallIgnored
                f.setLastModified(System.currentTimeMillis());
                return d;
            } catch (Exception ignored) { }
        }
        if (!download) return null;
        byte[] d = fetch(sized(url, DISK_PX));
        if (d == null) d = fetch(url);
        if (d != null && f != null) {
            try {
                File tmp = new File(f.getPath() + ".part");
                FileOutputStream o = new FileOutputStream(tmp);
                o.write(d);
                o.close();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(f);
            } catch (Exception ignored) { }
        }
        return d;
    }

    /** Download every photo of the list to the phone (skips ones already saved). */
    public static void prefetch(Collection<String> urls) {
        if (dir == null) return;
        final List<String> list = new ArrayList<>(new LinkedHashSet<>(urls));
        final long gen = ++preGen;
        pre.execute(new Runnable() {
            @Override public void run() {
                if (gen != preGen) return;
                List<String> todo = new ArrayList<>();
                for (String u : list) {
                    if (u == null || u.isEmpty()) continue;
                    File f = fileFor(u);
                    if (f != null && f.length() == 0) todo.add(u);
                }
                preTotal = todo.size();
                preDone = 0;
                int fails = 0;
                for (String u : todo) {
                    if (gen != preGen) return;             // a newer list came in
                    if (bytes(u, true) != null) fails = 0;
                    else if (++fails >= 15) {               // no internet: stop, the next refresh tries again
                        preTotal = 0;
                        return;
                    }
                    preDone++;
                }
                preTotal = 0;
            }
        });
    }

    private static byte[] fetch(String u) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            if (c.getResponseCode() != 200) { c.disconnect(); return null; }
            InputStream in = c.getInputStream();
            byte[] data = Api.readBytes(in);
            c.disconnect();
            return data.length > 0 ? data : null;
        } catch (Exception e) {
            return null;
        }
    }

    static Bitmap decode(byte[] data, int px) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int s = 1;
        while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
    }
}
