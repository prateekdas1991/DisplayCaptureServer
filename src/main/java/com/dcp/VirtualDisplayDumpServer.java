package com.dcp;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Surface;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * Device-side display mirroring to a virtual display, encoding H.264 and dumping raw NALs.
 * Arguments (scrcpy-style):
 *   --time <ms>                 capture duration in milliseconds (default 60000, cap 180000)
 *   --crop X:Y:W:H              crop rectangle in physical display coordinates
 *   --rotation <0|1|2|3>        rotation (0=0°,1=90°,2=180°,3=270°), default 0
 *   --screen-off                turn off the physical screen during capture (like scrcpy -S)
 *
 * Output: /sdcard/kpi_dump.h264 (raw H.264 elementary stream)
 */
public class VirtualDisplayDumpServer {

    private static final String TAG = "VDD";

    // Encoder surface size (viewport)
    private static final int OUT_W = 248;
    private static final int OUT_H = 144;

    // Encoding parameters
    private static final int FPS = 30;
    private static final int BITRATE = 1_000_000; // 1 Mbps
    private static final int IFRAME_SEC = 1;

    // Dump target
    private static final String OUTPUT_PATH = "/sdcard/kpi_dump.h264";

    // Power mode constants
    private static final int POWER_MODE_OFF = 0;
    private static final int POWER_MODE_NORMAL = 2;

    public static void main(String[] args) {
        int DEFAULT_DURATION_MS = 60_000;
        int MAX_DURATION_MS     = 180_000;
        int durationMs = DEFAULT_DURATION_MS;

        Rect crop = null;
        boolean turnScreenOff = false;
        int rotation = 0; // default portrait

        // Parse args
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--time".equals(arg) && i + 1 < args.length) {
                try { durationMs = Integer.parseInt(args[++i]); } catch (NumberFormatException ignored) {}
            } else if ("--crop".equals(arg) && i + 1 < args.length) {
                String[] parts = args[++i].split(":");
                if (parts.length == 4) {
                    try {
                        int x = Integer.parseInt(parts[0]);
                        int y = Integer.parseInt(parts[1]);
                        int w = Integer.parseInt(parts[2]);
                        int h = Integer.parseInt(parts[3]);
                        crop = new Rect(x, y, x + w, y + h);
                    } catch (NumberFormatException ignored) {}
                }
            } else if ("--rotation".equals(arg) && i + 1 < args.length) {
                try { rotation = Integer.parseInt(args[++i]); } catch (NumberFormatException ignored) {}
            } else if ("--screen-off".equals(arg)) {
                turnScreenOff = true;
            }
        }

        if (durationMs > MAX_DURATION_MS) {
            Log.i(TAG, "Duration capped at " + MAX_DURATION_MS + " ms");
            durationMs = MAX_DURATION_MS;
        }

        try {
            Log.i(TAG, "Starting capture… duration=" + durationMs + "ms"
                    + " crop=" + (crop != null ? crop.toShortString() : "FULL")
                    + " rotation=" + rotation
                    + " screenOff=" + turnScreenOff);

            if (crop == null) {
                Log.e(TAG, "No --crop provided. Please specify --crop X:Y:W:H");
                return;
            }

            Rect viewport = new Rect(0, 0, OUT_W, OUT_H);

            // Configure encoder
            MediaCodec codec = MediaCodec.createEncoderByType("video/avc");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", OUT_W, OUT_H);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_SEC);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface inputSurface = codec.createInputSurface();
            codec.start();

            // Create virtual display
            IBinder displayToken = SurfaceControlWrapper.createDisplay("scrcpy-display", false);
            IBinder internalToken = SurfaceControlWrapper.getBuiltInOrInternalDisplay();

            int layerStack = 0; // main display stack

            try {
                SurfaceControlWrapper.openTransaction();
                SurfaceControlWrapper.setDisplaySurface(displayToken, inputSurface);
                SurfaceControlWrapper.setDisplayProjection(displayToken, rotation, crop, viewport);
                SurfaceControlWrapper.setDisplayLayerStack(displayToken, layerStack);
                SurfaceControlWrapper.closeTransaction();
            } catch (Throwable staticFail) {
                android.view.SurfaceControl.Transaction t = new android.view.SurfaceControl.Transaction();
                SurfaceControlWrapper.setDisplaySurface(t, displayToken, inputSurface);
                SurfaceControlWrapper.setDisplayProjection(t, displayToken, rotation, crop, viewport);
                SurfaceControlWrapper.setDisplayLayerStack(displayToken, layerStack);
                t.apply();
            }

            if (turnScreenOff) {
                try {
                    SurfaceControlWrapper.setDisplayPowerMode(internalToken, POWER_MODE_OFF);
                    Log.i(TAG, "Physical screen turned OFF");
                } catch (Throwable e) {
                    Log.w(TAG, "Failed to turn screen off: " + e);
                }
            }

            // Request an immediate keyframe
            Bundle firstIdr = new Bundle();
            firstIdr.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(firstIdr);

            // Capture loop with direct-to-file write
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long startTime = System.currentTimeMillis();
            long lastSync = startTime;
            int frameCount = 0;

            File outFile = new File(OUTPUT_PATH);
            try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(outFile, false))) {
                while (System.currentTimeMillis() - startTime < durationMs) {
                    int idx = codec.dequeueOutputBuffer(info, 5000);
                    if (idx >= 0) {
                        ByteBuffer buf = codec.getOutputBuffer(idx);
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset);
                            buf.limit(info.offset + info.size);
                            byte[] nal = new byte[info.size];
                            buf.get(nal);
                            bos.write(nal);

                            // Optional: log frame timing
                            //Log.d(TAG, "Frame " + frameCount + " pts=" + info.presentationTimeUs);
                            frameCount++;
                        }
                        codec.releaseOutputBuffer(idx, false);
                    } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(TAG, "Output format: " + codec.getOutputFormat());
                    }

                    long now = System.currentTimeMillis();
                    if (now - lastSync >= 1000) {
                        Bundle sync = new Bundle();
                        sync.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                        codec.setParameters(sync);
                        lastSync = now;
                    }
                }
                bos.flush();
            }

            if (turnScreenOff) {
                try {
                    SurfaceControlWrapper.setDisplayPowerMode(internalToken, POWER_MODE_NORMAL);
                    Log.i(TAG, "Physical screen restored to NORMAL");
                } catch (Throwable e) {
                    Log.w(TAG, "Failed to restore screen power mode: " + e);
                }
            }

            codec.stop();
            codec.release();
            SurfaceControlWrapper.destroyDisplay(displayToken);

            long actualMs = System.currentTimeMillis() - startTime;
            Log.i(TAG, "Dump complete: " + outFile.getAbsolutePath()
                    + " frames=" + frameCount
                    + " size=" + outFile.length()
                    + " actual=" + actualMs + "ms"
                    + " crop=" + crop.toShortString()
                    + " layerStack=" + layerStack);

        } catch (Throwable t) {
            Log.e(TAG, "Fatal error", t);
        }
    }
}