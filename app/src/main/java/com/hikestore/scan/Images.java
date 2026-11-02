package com.hikestore.scan;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Product photos from Flipkart's image server, small size, kept in memory. */
public final class Images {
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService pool = Executors.newFixedThreadPool(3);
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static final Pattern PATH = Pattern.compile("/image/(.+)$");

    private Images() {}

    /** Flipkart's resizer: rukminim2.flixcart.com/image/<w>/<h>/<path>?q=80 */
    static String sized(String url, int px) {
        Matcher m = PATH.matcher(url);
        if (!m.find() || m.group(1).matches("^\\d+/\\d+/.*")) return url;
        return "https://rukminim2.flixcart.com/image/" + px + "/" + px + "/" + m.group(1) + "?q=80";
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
                Bitmap bm = fetch(sized(url, px), px);
                if (bm == null) bm = fetch(url, px);
                if (bm == null) return;
                cache.put(key, bm);
                final Bitmap f = bm;
                ui.post(new Runnable() {
                    @Override public void run() { if (url.equals(v.getTag())) v.setImageBitmap(f); }
                });
            }
        });
    }

    private static Bitmap fetch(String u, int px) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            if (c.getResponseCode() != 200) { c.disconnect(); return null; }
            InputStream in = c.getInputStream();
            byte[] data = Api.readBytes(in);
            c.disconnect();
            return decode(data, px);
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
