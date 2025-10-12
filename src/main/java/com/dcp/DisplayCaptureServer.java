package com.dcp;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;

public class DisplayCaptureServer {
    private static final String TAG = "pds6";

    private static final int OUT_W = 248, OUT_H = 144;
    private static final int DEFAULT_DURATION_MS = 60_000;   // 1 min
    private static final int MAX_DURATION_MS     = 180_000;  // 3 min cap
    private static final int BITRATE = 1_000_000, FPS = 30, IFRAME_SEC = 1;

    private static final boolean USE_SECURE_DISPLAY = true;
    // If your device is portrait 1080x2400, set these accordingly.
    // For robustness without getDisplayInfo, you can pass your known physical size.
    private static final int PHYS_W = 1080;  // adjust to your device
    private static final int PHYS_H = 2400;  // adjust to your device
    private static final int ROTATION = 0;   // try 0..3 if needed

    public static void main(String[] args) {
        int durationMs = DEFAULT_DURATION_MS;

        try {
            if (args.length >= 1) durationMs = Integer.parseInt(args[0]);
        } catch (NumberFormatException nfe) {
            Log.w(TAG, "Invalid args, using defaults");
        }
        if (durationMs > MAX_DURATION_MS) {
            Log.i(TAG, "Duration capped at " + MAX_DURATION_MS + " ms (3 minutes)");
            durationMs = MAX_DURATION_MS;
        }

        Log.i(TAG, "Starting capture… duration=" + durationMs + "ms");

        if (!HiddenTxn.isAvailable()) {
            Log.e(TAG, "Fatal: HiddenTxn unavailable — cannot create virtual display");
            return;
        }

        File outFile = new File("/sdcard/dump.raw");

        try {
            MediaCodec encoder = MediaCodec.createEncoderByType("video/avc");
            MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", OUT_W, OUT_H);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_SEC);
            encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface inputSurface = encoder.createInputSurface();
            encoder.start();

            Object displayToken = HiddenTxn.createDisplay("MiniSrvDisplay", USE_SECURE_DISPLAY);
            HiddenTxn.setDisplaySurface(displayToken, inputSurface);

            // Full-screen crop, encoder-sized viewport, rotation (scrcpy-style behavior)
            Rect fullCrop = new Rect(0, 0, PHYS_W, PHYS_H);
            Rect viewport = new Rect(0, 0, OUT_W, OUT_H);
            HiddenTxn.setDisplayProjection(displayToken, fullCrop, viewport, ROTATION);
            HiddenTxn.apply();

            // Force an immediate keyframe
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            encoder.setParameters(b);

            long start = System.currentTimeMillis();
            long lastSync = start;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            // Accumulate all encoded buffers in memory (be mindful of RAM usage)
            List<byte[]> bufferList = new ArrayList<>();
            int frames = 0;
            long totalBytes = 0;

            while (System.currentTimeMillis() - start < durationMs) {
                int idx = encoder.dequeueOutputBuffer(info, 10_000);
                if (idx >= 0) {
                    ByteBuffer buf = encoder.getOutputBuffer(idx);
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        byte[] nal = new byte[info.size];
                        buf.get(nal);
                        bufferList.add(nal);
                        totalBytes += nal.length;
                        frames++;
                        Log.d(TAG, "Captured frame idx=" + idx +
                                " size=" + info.size +
                                " pts=" + info.presentationTimeUs +
                                " (accum=" + totalBytes + " bytes, frames=" + frames + ")");
                    }
                    encoder.releaseOutputBuffer(idx, false);
                } else if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    Log.d(TAG, "No output buffer available (timeout)");
                }

                // Request a sync frame every ~1s
                long now = System.currentTimeMillis();
                if (now - lastSync >= 1000) {
                    Bundle sync = new Bundle();
                    sync.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                    encoder.setParameters(sync);
                    lastSync = now;
                    Log.d(TAG, "Requested sync frame");
                }
            }

            // Stop and release codec and display
            encoder.stop();
            encoder.release();
            HiddenTxn.releaseDisplay(displayToken);

            // Dump everything at once to file
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                for (byte[] nal : bufferList) {
                    fos.write(nal);
                }
                fos.flush();
            }

            long actual = System.currentTimeMillis() - start;
            Log.i(TAG, "Dump complete: " + outFile.getAbsolutePath() +
                    " (actual run " + actual + " ms, frames=" + frames + ", bytes=" + totalBytes + ")");

            if (frames == 0) {
                Log.w(TAG, "No frames produced — verify projection signature/rotation and secure display.");
            } else {
                Log.i(TAG, "Tip: mux raw H.264 to MP4 for playback: ffmpeg -f h264 -i dump.raw -c copy dump.mp4");
                Log.i(TAG, "To crop later (post-encode): ffmpeg -i dump.raw -vf \"crop=248:144:390:450\" -c:v libx264 out.mp4");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Fatal error", t);
        }
    }
}