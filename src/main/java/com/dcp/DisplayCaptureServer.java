package com.dcp;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.view.Surface;

public class DisplayCaptureServer {
    private static final int OUT_W = 248, OUT_H = 144;
    private static final int DEFAULT_X = 390, DEFAULT_Y = 450;
    private static final int DEFAULT_DURATION_MS = 60_000;   // 1 min
    private static final int MAX_DURATION_MS     = 180_000;  // 3 min cap
    private static final int BITRATE = 1_000_000, FPS = 30, IFRAME_SEC = 1;

    public static void main(String[] args) {
        // Parse args: [durationMs] [startX] [startY]
        int durationMs = DEFAULT_DURATION_MS;
        int startX = DEFAULT_X, startY = DEFAULT_Y;

        try {
            if (args.length >= 1) {
                durationMs = Integer.parseInt(args[0]);
            }
            if (args.length >= 3) {
                startX = Integer.parseInt(args[1]);
                startY = Integer.parseInt(args[2]);
            }
        } catch (NumberFormatException nfe) {
            log("Invalid args, using defaults");
        }

        // Enforce max cap
        if (durationMs > MAX_DURATION_MS) {
            log("Duration capped at " + MAX_DURATION_MS + " ms (3 minutes)");
            durationMs = MAX_DURATION_MS;
        }

        Rect crop = new Rect(startX, startY, startX + OUT_W, startY + OUT_H);
        log("Starting capture… duration=" + durationMs + "ms, crop=" + crop);

        if (!HiddenTxn.isAvailable()) {
            log("Fatal: HiddenTxn unavailable — cannot create virtual display on this platform");
            return;
        }

        File outFile = new File("/sdcard/dump.raw");

        try {
            MediaCodec encoder = MediaCodec.createEncoderByType("video/avc");
            MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", OUT_W, OUT_H);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_SEC);
            encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface inputSurface = encoder.createInputSurface();
            encoder.start();

            Object displayToken = HiddenTxn.createDisplay("MiniSrvDisplay", false);
            HiddenTxn.setDisplaySurface(displayToken, inputSurface);
            HiddenTxn.setDisplayProjection(displayToken, crop, new Rect(0, 0, OUT_W, OUT_H));
            HiddenTxn.apply();

            long start = System.currentTimeMillis();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                while (System.currentTimeMillis() - start < durationMs) {
                    int idx = encoder.dequeueOutputBuffer(info, 10_000);
                    if (idx >= 0) {
                        ByteBuffer buf = encoder.getOutputBuffer(idx);
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset);
                            buf.limit(info.offset + info.size);
                            byte[] nal = new byte[info.size];
                            buf.get(nal);
                            fos.write(nal);
                        }
                        encoder.releaseOutputBuffer(idx, false);
                    }
                }
                fos.flush();
            }

            encoder.stop();
            encoder.release();
            HiddenTxn.releaseDisplay(displayToken);

            long actual = System.currentTimeMillis() - start;
            log("Dump complete: " + outFile.getAbsolutePath() + " (actual run " + actual + " ms)");
        } catch (Throwable t) {
            t.printStackTrace();
            log("Fatal: " + t);
        }
    }

    private static void log(String s) { System.out.println("[srv] " + s); }
}