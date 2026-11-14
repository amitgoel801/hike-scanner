package com.hikestore.scan;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Camera mode: the phone's own camera reads the long barcode held inside the box.
 * - only the part of the picture inside the box is read (other barcodes on the label are ignored)
 * - a code is taken only when it is WHOLE: it is in today's list, or it looks like a full tracking / AWB /
 *   order number AND the same number was read twice in a row (a half-read number is never taken)
 * - the box stays RED until a code is taken, then turns GREEN; the same label is not taken again for 4 s
 * Uses the old camera API (works on Android 5 and newer, also without Google services).
 */
@SuppressWarnings("deprecation")
final class CameraScan implements TextureView.SurfaceTextureListener {

    interface Listener {
        /** is this code a packet / bag the phone knows (then one read is enough) */
        boolean known(String code);

        /** a whole code was read - scan it */
        void onCode(String code);
    }

    // the scan box, as part of the camera panel
    static final float BOX_L = 0.06f, BOX_R = 0.94f, BOX_T = 0.28f, BOX_B = 0.72f;
    private static final long FRAME_GAP_MS = 110, SAME_LABEL_MS = 4000, SETTLE_MS = 1300, TWICE_MS = 2500;

    final FrameLayout view;
    private final Activity act;
    private final Listener listener;
    private final TextureView tex;
    private final Box box;
    private final TextView status, light;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService dec = Executors.newSingleThreadExecutor();
    private final BarcodeDecoder decoder = new BarcodeDecoder();

    private HandlerThread camThread;
    private Handler camH;
    private Camera cam;
    private SurfaceTexture surface;
    private volatile int pw, ph, rot, vw, vh;
    private volatile boolean running, busy;
    private boolean previewOn, torch, autoFocusLoop;
    private long lastFrame;

    // only whole, steady reads are taken
    private String cand = null;
    private long candAt;
    private String lastTaken = "";
    private long lastTakenAt;

    CameraScan(Activity a, Listener l) {
        act = a;
        listener = l;
        view = new FrameLayout(a);
        view.setBackgroundColor(0xFF000000);
        tex = new TextureView(a);
        tex.setSurfaceTextureListener(this);
        view.addView(tex, new FrameLayout.LayoutParams(-1, -1));
        box = new Box(a);
        view.addView(box, new FrameLayout.LayoutParams(-1, -1));
        status = Ui.text(a, "Hold the long barcode inside the box", 13, 0xFFFFFFFF, true);
        status.setGravity(Gravity.CENTER);
        status.setShadowLayer(4, 0, 0, 0xFF000000);
        status.setPadding(Ui.dp(8), Ui.dp(4), Ui.dp(8), Ui.dp(6));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        view.addView(status, sp);
        light = Ui.text(a, "💡", 18, 0xFFFFFFFF, true);
        light.setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(12), Ui.dp(6));
        light.setBackground(Ui.box(0x66000000, 0, 18));
        light.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { toggleTorch(); } });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lp.setMargins(0, Ui.dp(6), Ui.dp(6), 0);
        view.addView(light, lp);
        view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                vw = r - l;
                vh = b - t;
                fitPreview();
            }
        });
    }

    // ------------------------------------------------------------------ start / stop
    void start() {
        if (running) return;
        running = true;
        busy = false;
        camThread = new HandlerThread("hike-camera");
        camThread.start();
        camH = new Handler(camThread.getLooper());
        camH.post(new Runnable() { @Override public void run() { open(); } });
        say("Starting the camera…", false);
    }

    void stop() {
        if (!running) return;
        running = false;
        final HandlerThread t = camThread;
        camH.post(new Runnable() {
            @Override public void run() {
                close();
                t.quit();
            }
        });
        camH = null;
        camThread = null;
    }

    void release() {
        stop();
        dec.shutdownNow();
    }

    private void open() {
        try {
            int id = -1;
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { id = i; break; }
            }
            if (id < 0) { say("This phone has no back camera", false); return; }
            Camera.getCameraInfo(id, info);
            cam = Camera.open(id);
            Camera.Parameters p = cam.getParameters();
            Camera.Size best = null;
            for (Camera.Size s : p.getSupportedPreviewSizes()) {          // ~1280x720: sharp enough, fast enough
                if (s.width * s.height < 640 * 480) continue;
                if (best == null || Math.abs(s.width * s.height - 1280 * 720) < Math.abs(best.width * best.height - 1280 * 720)) best = s;
            }
            if (best != null) p.setPreviewSize(best.width, best.height);
            p.setPreviewFormat(ImageFormat.NV21);
            List<String> fm = p.getSupportedFocusModes();
            autoFocusLoop = false;
            if (fm != null && fm.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            else if (fm != null && fm.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            else if (fm != null && fm.contains(Camera.Parameters.FOCUS_MODE_AUTO)) { p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO); autoFocusLoop = true; }
            try { cam.setParameters(p); } catch (RuntimeException e) {     // some phones refuse a combination: keep their defaults
                Camera.Parameters q = cam.getParameters();
                if (best != null) q.setPreviewSize(best.width, best.height);
                try { cam.setParameters(q); } catch (RuntimeException ignored) { }
            }
            Camera.Size s = cam.getParameters().getPreviewSize();
            pw = s.width;
            ph = s.height;
            int deg = 0;
            switch (act.getWindowManager().getDefaultDisplay().getRotation()) {
                case Surface.ROTATION_90: deg = 90; break;
                case Surface.ROTATION_180: deg = 180; break;
                case Surface.ROTATION_270: deg = 270; break;
                default: deg = 0;
            }
            rot = (info.orientation - deg + 360) % 360;
            cam.setDisplayOrientation(rot);
            int size = pw * ph * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
            cam.addCallbackBuffer(new byte[size]);
            cam.addCallbackBuffer(new byte[size]);
            cam.setPreviewCallbackWithBuffer(frames);
            ui.post(new Runnable() { @Override public void run() { fitPreview(); } });
            startPreview();
        } catch (RuntimeException e) {
            close();
            say("Camera not available (" + e.getMessage() + ") - close other camera apps and switch camera mode on again", false);
        }
    }

    private void startPreview() {
        if (cam == null || surface == null || previewOn) return;
        try {
            cam.setPreviewTexture(surface);
            cam.startPreview();
            previewOn = true;
            say("Hold the long barcode inside the box", false);
            if (autoFocusLoop) focusLoop.run();
        } catch (Exception e) {
            say("Camera could not start: " + e.getMessage(), false);
        }
    }

    private final Runnable focusLoop = new Runnable() {
        @Override public void run() {
            if (cam == null || !previewOn || camH == null) return;
            try { cam.autoFocus(null); } catch (RuntimeException ignored) { }
            camH.postDelayed(this, 2500);
        }
    };

    private void close() {
        previewOn = false;
        if (cam == null) return;
        try { cam.setPreviewCallbackWithBuffer(null); } catch (RuntimeException ignored) { }
        try { cam.stopPreview(); } catch (RuntimeException ignored) { }
        try { cam.release(); } catch (RuntimeException ignored) { }
        cam = null;
        torch = false;
    }

    private void toggleTorch() {
        final Handler h = camH;
        if (h == null) return;
        h.post(new Runnable() {
            @Override public void run() {
                if (cam == null) return;
                try {
                    Camera.Parameters p = cam.getParameters();
                    List<String> f = p.getSupportedFlashModes();
                    if (f == null || !f.contains(Camera.Parameters.FLASH_MODE_TORCH)) { say("This phone has no torch", false); return; }
                    torch = !torch;
                    p.setFlashMode(torch ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
                    cam.setParameters(p);
                } catch (RuntimeException ignored) { }
            }
        });
    }

    // ------------------------------------------------------------------ picture fills the panel without stretching
    private void fitPreview() {
        if (pw == 0 || vw == 0 || vh == 0) return;
        float uw = rot % 180 == 0 ? pw : ph, uh = rot % 180 == 0 ? ph : pw;
        float s = Math.max(vw / uw, vh / uh);
        Matrix m = new Matrix();
        m.setScale(uw * s / vw, uh * s / vh, vw / 2f, vh / 2f);
        tex.setTransform(m);
    }

    // ------------------------------------------------------------------ frames -> codes
    private final Camera.PreviewCallback frames = new Camera.PreviewCallback() {
        @Override public void onPreviewFrame(final byte[] data, final Camera c) {
            if (!running || data == null) return;
            long now = SystemClock.uptimeMillis();
            if (busy || now - lastFrame < FRAME_GAP_MS || vw == 0) { c.addCallbackBuffer(data); return; }
            busy = true;
            lastFrame = now;
            final int[] cr = BarcodeDecoder.cropFor(pw, ph, rot, vw, vh, BOX_L, BOX_T, BOX_R, BOX_B);
            final int w = pw, h = ph;
            try {
                dec.execute(new Runnable() {
                    @Override public void run() {
                        String code = null;
                        try { code = decoder.decode(data, w, h, cr[0], cr[1], cr[2], cr[3]); } catch (Throwable ignored) { }
                        final Handler hh = camH;
                        if (hh != null) hh.post(new Runnable() {
                            @Override public void run() { if (cam != null) try { cam.addCallbackBuffer(data); } catch (RuntimeException ignored) { } }
                        });
                        busy = false;
                        if (code != null) {
                            final String got = code.trim().toUpperCase(Locale.ROOT);
                            ui.post(new Runnable() { @Override public void run() { onRead(got); } });
                        }
                    }
                });
            } catch (RuntimeException e) {
                busy = false;
                c.addCallbackBuffer(data);
            }
        }
    };

    /** screen thread: decide if a read is a WHOLE code */
    private void onRead(String code) {
        if (!running) return;
        long now = SystemClock.uptimeMillis();
        if (code.equals(lastTaken) && now - lastTakenAt < SAME_LABEL_MS) {        // the same label is still in front of the camera
            lastTakenAt = now;
            return;
        }
        if (now - lastTakenAt < SETTLE_MS) return;                                // just took one - give time to move to the next packet
        boolean known = listener.known(code);
        if (!known && !BarcodeDecoder.looksWhole(code)) {
            say("Read " + code + " - not a full tracking number. Keep the long barcode inside the box.", false);
            return;
        }
        if (!known) {                     // not in today's list: take it only after the same number was read twice
            if (!code.equals(cand) || now - candAt > TWICE_MS) {
                cand = code;
                candAt = now;
                say("Reading " + code + " … hold still", false);
                return;
            }
        }
        cand = null;
        lastTaken = code;
        lastTakenAt = now;
        say("✔ " + code, true);
        box.flash();
        listener.onCode(code);
    }

    private void say(final String text, final boolean good) {
        ui.post(new Runnable() {
            @Override public void run() {
                status.setText(text);
                status.setTextColor(good ? 0xFF86EFAC : 0xFFFFFFFF);
            }
        });
    }

    // ------------------------------------------------------------------ SurfaceTexture
    @Override public void onSurfaceTextureAvailable(final SurfaceTexture st, int w, int h) {
        surface = st;
        final Handler hh = camH;
        if (hh != null) hh.post(new Runnable() { @Override public void run() { startPreview(); } });
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { fitPreview(); }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        surface = null;
        previewOn = false;
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }

    /** dark around the box, box RED while reading, GREEN when a code was taken */
    static final class Box extends View {
        private final Paint dim = new Paint(), line = new Paint(), laser = new Paint();
        private long greenUntil;

        Box(Context c) {
            super(c);
            dim.setColor(0x88000000);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Ui.dp(4));
            laser.setStrokeWidth(Ui.dp(2));
        }

        void flash() {
            greenUntil = SystemClock.uptimeMillis() + 900;
            invalidate();
            postDelayed(new Runnable() { @Override public void run() { invalidate(); } }, 950);
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float l = w * BOX_L, r = w * BOX_R, t = h * BOX_T, b = h * BOX_B;
            c.drawRect(0, 0, w, t, dim);
            c.drawRect(0, b, w, h, dim);
            c.drawRect(0, t, l, b, dim);
            c.drawRect(r, t, w, b, dim);
            boolean green = SystemClock.uptimeMillis() < greenUntil;
            int col = green ? 0xFF22C55E : 0xFFEF4444;
            line.setColor(col);
            c.drawRoundRect(new android.graphics.RectF(l, t, r, b), Ui.dp(10), Ui.dp(10), line);
            laser.setColor(green ? 0xAA22C55E : 0xAAEF4444);
            c.drawLine(l + Ui.dp(8), (t + b) / 2f, r - Ui.dp(8), (t + b) / 2f, laser);
        }
    }
}
