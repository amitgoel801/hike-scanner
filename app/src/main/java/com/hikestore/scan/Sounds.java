package com.hikestore.scan;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Vibrator;

import java.util.HashMap;
import java.util.Map;

/**
 * Scan sounds made inside the app (no sound files, works offline), the same tones as
 * the HikeOne web page: beep, double, error, warn, rescan.
 */
public final class Sounds {
    private static final int RATE = 22050;
    private final Map<String, short[]> pcm = new HashMap<>();
    private final Vibrator vib;
    private float volume = 0.9f;
    public boolean mute = false;
    public boolean vibrate = true;

    public Sounds(Context ctx) {
        vib = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        pcm.put("beep", build(new double[][]{{2300, 0, .09, 1, .35}}));
        pcm.put("double", build(new double[][]{{2300, 0, .07, 1, .35}, {2300, .11, .07, 1, .35}}));
        pcm.put("error", build(new double[][]{{170, 0, .22, 1, .8}, {170, .30, .22, 1, .8}, {140, .60, .35, 1, .8}}));
        pcm.put("warn", build(new double[][]{{880, 0, .5, 2, .55, 300}, {880, .6, .5, 2, .55, 300}}));
        pcm.put("multi", build(new double[][]{{1500, 0, .12, 1, .4}, {2000, .16, .12, 1, .4}, {2600, .32, .18, 1, .4}}));
        pcm.put("rescan", build(new double[][]{{700, 0, .07, 1, .5}, {700, .12, .07, 1, .5}, {700, .24, .07, 1, .5}}));
    }

    public void setVolume(double v) { volume = (float) Math.max(0, Math.min(1, v)); }

    /** tones: {freq, start s, duration s, wave 0=sine 1=square 2=saw, volume, [end freq]} */
    private static short[] build(double[][] tones) {
        double end = 0;
        for (double[] t : tones) end = Math.max(end, t[1] + t[2]);
        short[] out = new short[(int) ((end + 0.05) * RATE)];
        for (double[] t : tones) {
            int s0 = (int) (t[1] * RATE), n = (int) (t[2] * RATE);
            double f0 = t[0], f1 = t.length > 5 ? t[5] : t[0], phase = 0;
            for (int i = 0; i < n; i++) {
                double f = f0 + (f1 - f0) * i / n;
                phase += f / RATE;
                double x = phase - Math.floor(phase), w;
                if (t[3] == 1) w = x < .5 ? 1 : -1;
                else if (t[3] == 2) w = 2 * x - 1;
                else w = Math.sin(2 * Math.PI * x);
                double env = Math.min(1, Math.min(i / (0.008 * RATE), (n - i) / (0.02 * RATE)));
                int v = out[s0 + i] + (int) (w * env * t[4] * 26000);
                out[s0 + i] = (short) Math.max(-32767, Math.min(32767, v));
            }
        }
        return out;
    }

    /** name = beep / double / error / warn / rescan / none */
    public void play(String name) {
        if (mute || name == null || "none".equals(name)) return;
        final short[] data = pcm.containsKey(name) ? pcm.get(name) : pcm.get("beep");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    @SuppressWarnings("deprecation")
                    AudioTrack at = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, data.length * 2, AudioTrack.MODE_STATIC);
                    at.write(data, 0, data.length);
                    if (Build.VERSION.SDK_INT >= 21) at.setVolume(volume); else setStereo(at);
                    at.play();
                    Thread.sleep((long) (data.length * 1000L / RATE) + 80);
                    at.release();
                } catch (Exception ignored) { }
            }
        }, "sound").start();
    }

    @SuppressWarnings("deprecation")
    private void setStereo(AudioTrack at) { at.setStereoVolume(volume, volume); }

    @SuppressWarnings("deprecation")
    public void buzz(String result) {
        if (!vibrate || vib == null) return;
        long[] p;
        switch (result) {
            case "duplicate": p = new long[]{0, 250, 90, 250, 90, 250}; break;
            case "not_found": p = new long[]{0, 500, 120, 500}; break;
            case "rescan": p = new long[]{0, 80, 60, 80, 60, 80}; break;
            case "multi": p = new long[]{0, 60, 60, 60, 60, 200}; break;
            case "bag": p = new long[]{0, 120, 80, 120}; break;
            default: p = new long[]{0, 40};
        }
        try { vib.vibrate(p, -1); } catch (Exception ignored) { }
    }
}
