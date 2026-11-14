package com.hikestore.scan;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads the long barcode of a label from one camera frame (pure Java + ZXing, no Android - tested on the PC).
 * Only the part of the frame inside the scan box is read; bars may be across or up-down (tries both).
 * Only straight barcodes (Code 128 etc.) - the square code on Ekart labels has no tracking number.
 */
final class BarcodeDecoder {
    private final MultiFormatReader reader = new MultiFormatReader();

    BarcodeDecoder() {
        Map<DecodeHintType, Object> h = new EnumMap<>(DecodeHintType.class);
        h.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93,
                BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.ITF, BarcodeFormat.CODABAR, BarcodeFormat.UPC_A));
        h.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);      // also tries the frame turned 90 degrees
        reader.setHints(h);
    }

    /**
     * @param y      the brightness part of a camera frame (NV21: first width*height bytes), row by row
     * @param left   crop (the scan box) in frame pixels
     * @return the code, or null
     */
    String decode(byte[] y, int width, int height, int left, int top, int cw, int ch) {
        left = Math.max(0, Math.min(left, width - 1));
        top = Math.max(0, Math.min(top, height - 1));
        cw = Math.max(1, Math.min(cw, width - left));
        ch = Math.max(1, Math.min(ch, height - top));
        byte[] c = new byte[cw * ch];
        for (int r = 0; r < ch; r++) System.arraycopy(y, (top + r) * width + left, c, r * cw, cw);
        Gray src = new Gray(c, cw, ch);
        String s = read(new BinaryBitmap(new HybridBinarizer(src)));
        if (s == null) s = read(new BinaryBitmap(new GlobalHistogramBinarizer(src)));   // often better on creased labels
        return s;
    }

    private String read(BinaryBitmap b) {
        try {
            Result r = reader.decodeWithState(b);
            return r.getText();
        } catch (ReaderException e) {
            return null;
        } catch (RuntimeException e) {      // a damaged frame must never stop the camera
            return null;
        } finally {
            reader.reset();
        }
    }

    // ------------------------------------------------------------------ where the scan box is in the camera frame
    /**
     * The scan box (fractions of the camera panel) in camera-frame pixels: {left, top, width, height}.
     * pw x ph = camera frame (sensor), rot = how far the picture is turned for the screen (0/90/180/270),
     * vw x vh = the panel on screen (the picture fills it, cut at the sides - not stretched).
     */
    static int[] cropFor(int pw, int ph, int rot, int vw, int vh, float bl, float bt, float br, float bb) {
        float uw = rot % 180 == 0 ? pw : ph, uh = rot % 180 == 0 ? ph : pw;
        float s = Math.max(vw / uw, vh / uh), ox = (vw - uw * s) / 2f, oy = (vh - uh * s) / 2f;
        float ux0 = clamp((vw * bl - ox) / s, uw), ux1 = clamp((vw * br - ox) / s, uw);
        float uy0 = clamp((vh * bt - oy) / s, uh), uy1 = clamp((vh * bb - oy) / s, uh);
        float[] a = toSensor(ux0, uy0, rot, pw, ph), b = toSensor(ux1, uy1, rot, pw, ph);
        int l = (int) Math.min(a[0], b[0]), t = (int) Math.min(a[1], b[1]);
        int r = (int) Math.max(a[0], b[0]), btm = (int) Math.max(a[1], b[1]);
        return new int[]{l, t, Math.max(1, r - l), Math.max(1, btm - t)};
    }

    private static float clamp(float v, float max) { return Math.max(0, Math.min(max, v)); }

    /** a point of the upright picture -> the same point in the camera frame */
    static float[] toSensor(float ux, float uy, int rot, int pw, int ph) {
        switch (rot) {
            case 90: return new float[]{uy, ph - ux};
            case 180: return new float[]{pw - ux, ph - uy};
            case 270: return new float[]{pw - uy, ux};
            default: return new float[]{ux, uy};
        }
    }

    // ------------------------------------------------------------------ is this a WHOLE code?
    // Real numbers seen on our labels: FMPP4394872414 / FMPC… / FMPR… (14), Ekart AWB 5965821066531 (13),
    // Blue Dart 82299030505 (11), Delhivery 1344811559150 (13), forward SF3740985588F, order OD438925804919539100 (20),
    // bag EDELPNI-662385505. Pin codes (6), "B10" etc. are other barcodes on the label - never taken.
    private static final Pattern[] WHOLE = {
            Pattern.compile("^FMP[A-Z]\\d{10}$"),
            Pattern.compile("^SF\\d{8,13}[A-Z]{0,4}$"),
            Pattern.compile("^OD\\d{15,21}$"),
            Pattern.compile("^\\d{10,15}$"),
            Pattern.compile("^[A-Z]{2,8}-?\\d{6,14}$"),
            Pattern.compile("^[A-Z]{1,4}\\d{9,14}[A-Z]{0,3}$"),
    };

    /** looks like a complete tracking / AWB / order number (not a part of one, not a pin code) */
    static boolean looksWhole(String code) {
        if (code == null || code.length() < 10 || code.length() > 30) return false;
        for (Pattern p : WHOLE) if (p.matcher(code).matches()) return true;
        return false;
    }

    /** grey image (one byte per pixel) that ZXing can also turn 90 degrees */
    static final class Gray extends LuminanceSource {
        private final byte[] px;

        Gray(byte[] px, int w, int h) {
            super(w, h);
            this.px = px;
        }

        @Override public byte[] getRow(int y, byte[] row) {
            int w = getWidth();
            if (row == null || row.length < w) row = new byte[w];
            System.arraycopy(px, y * w, row, 0, w);
            return row;
        }

        @Override public byte[] getMatrix() { return px; }

        @Override public boolean isRotateSupported() { return true; }

        @Override public LuminanceSource rotateCounterClockwise() {
            int w = getWidth(), h = getHeight();
            byte[] r = new byte[w * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) r[(w - 1 - x) * h + y] = px[y * w + x];
            return new Gray(r, h, w);
        }
    }
}
