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
import java.util.ArrayList;
import java.util.List;

public class VirtualDisplayDumpServer {

    private static final String TAG = "pds6";

    private static final int OUT_W = 1080;
    private static final int OUT_H = 1920;
    private static final int FPS = 30;
    private static final int BITRATE = 8_000_000;
    private static final int DURATION_MS = 10_000;
    private static final String OUTPUT_PATH = "/sdcard/kpi_dump.h264";

    public static void main(String[] args) {
        try {
            Log.i(TAG, "Starting scrcpy-style capture…");

            // Query real display info
            SurfaceControlWrapper.DisplayInfoData di = SurfaceControlWrapper.queryBuiltInDisplayInfo();
            int physW = di.width;
            int physH = di.height;
            int rotation = di.rotation;
            Integer layerStack = di.layerStack;

            boolean rotated90 = (rotation == 1 || rotation == 3);
            int cropW = rotated90 ? physH : physW;
            int cropH = rotated90 ? physW : physH;

            // Configure encoder
            MediaCodec codec = MediaCodec.createEncoderByType("video/avc");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", OUT_W, OUT_H);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface inputSurface = codec.createInputSurface();
            codec.start();

            // Create display and map to internal
            IBinder displayToken = SurfaceControlWrapper.createDisplay("scrcpy-display", false);
            IBinder internalToken = SurfaceControlWrapper.getBuiltInOrInternalDisplay();

            Rect crop = new Rect(0, 0, cropW, cropH);
            Rect viewport = new Rect(0, 0, OUT_W, OUT_H);

            boolean usedStaticPath = false;
            // Preferred: static transaction path (like scrcpy)
            try {
                SurfaceControlWrapper.openTransaction();
                SurfaceControlWrapper.setDisplaySurface(displayToken, inputSurface);
                SurfaceControlWrapper.setDisplayProjection(displayToken, rotation, crop, viewport);
                SurfaceControlWrapper.setDisplayLayerStack(displayToken, (layerStack != null) ? layerStack : 0);
                SurfaceControlWrapper.closeTransaction();
                usedStaticPath = true;
            } catch (Throwable staticFail) {
                // Fallback: instance transaction path
                android.view.SurfaceControl.Transaction t = new android.view.SurfaceControl.Transaction();
                SurfaceControlWrapper.setDisplaySurface(t, displayToken, inputSurface);
                SurfaceControlWrapper.setDisplayProjection(t, displayToken, rotation, crop, viewport);
                // Layer stack method is static; use it if available
                SurfaceControlWrapper.setDisplayLayerStack(displayToken, (layerStack != null) ? layerStack : 0);
                t.apply();
            }

            // Capture loop
            List<byte[]> frameBufferList = new ArrayList<>();
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            long startTime = System.currentTimeMillis();
            long lastSync = startTime;

            while (System.currentTimeMillis() - startTime < DURATION_MS) {
                int outputIndex = codec.dequeueOutputBuffer(bufferInfo, 5000);
                if (outputIndex >= 0) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        byte[] data = new byte[bufferInfo.size];
                        outputBuffer.get(data);
                        frameBufferList.add(data);
                    }
                    codec.releaseOutputBuffer(outputIndex, false);
                }

                long now = System.currentTimeMillis();
                if (now - lastSync >= 1000) {
                    Bundle sync = new Bundle();
                    sync.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                    codec.setParameters(sync);
                    lastSync = now;
                }
            }

            // Dump frames to file
            File outFile = new File(OUTPUT_PATH);
            try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(outFile, false))) {
                for (byte[] frameData : frameBufferList) {
                    bos.write(frameData);
                }
                bos.flush();
            }

            codec.stop();
            codec.release();
            SurfaceControlWrapper.destroyDisplay(displayToken);

            Log.i(TAG, "Dump complete: " + outFile.getAbsolutePath()
                    + " frames=" + frameBufferList.size()
                    + " size=" + outFile.length()
                    + " staticPath=" + usedStaticPath);

        } catch (Throwable t) {
            Log.e(TAG, "Fatal error", t);
        }
    }
}