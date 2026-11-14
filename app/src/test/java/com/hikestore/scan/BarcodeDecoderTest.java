package com.hikestore.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;

import org.junit.Test;

/** camera mode reads whole label numbers only (plain JUnit, no phone needed) */
public class BarcodeDecoderTest {
    private static byte[] frame(String code, int cut, int centerX) {
        int w = 1280, h = 720;
        byte[] f = new byte[w * h * 3 / 2];
        java.util.Arrays.fill(f, 0, w * h, (byte) 190);
        BitMatrix m = new Code128Writer().encode(code, BarcodeFormat.CODE_128, 0, 0);
        int mod = 3, bw = m.getWidth() * mod, bh = 90;
        for (int i = 0; i < bw - cut * mod; i++)
            for (int j = 0; j < bh; j++) {           // portrait phone: the barcode runs along the frame's height
                int x = centerX - bh / 2 + j, y = h / 2 - bw / 2 + i;
                if (x >= 0 && y >= 0 && x < w && y < h) f[y * w + x] = (byte) (m.get(i / mod, 0) ? 30 : 235);
            }
        return f;
    }

    @Test public void readsWholeLabelNumbers() {
        BarcodeDecoder d = new BarcodeDecoder();
        int[] cr = BarcodeDecoder.cropFor(1280, 720, 90, 1080, 760, 0.06f, 0.28f, 0.94f, 0.72f);
        for (String c : new String[]{"FMPP4394872414", "5965821066531", "82299030505", "OD438925804919539100"}) {
            assertEquals(c, d.decode(frame(c, 0, 640), 1280, 720, cr[0], cr[1], cr[2], cr[3]));
            assertNull("part of a barcode gives nothing", d.decode(frame(c, 20, 640), 1280, 720, cr[0], cr[1], cr[2], cr[3]));
            assertNull("outside the box gives nothing", d.decode(frame(c, 0, 100), 1280, 720, cr[0], cr[1], cr[2], cr[3]));
            assertTrue(BarcodeDecoder.looksWhole(c));
        }
        for (String bad : new String[]{"132106", "B10", "5965821", "FMPP43948"}) assertFalse(bad, BarcodeDecoder.looksWhole(bad));
    }
}
