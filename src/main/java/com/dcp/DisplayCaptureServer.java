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
    private static final Rect CROP = new Rect(390, 450, 390+248, 450+144);
    private static final int BITRATE = 1_000_000, FPS = 30, IFRAME_SEC = 1;

    public static void main(String[] args) {
        log("Starting capture…");
        //try { Runtime.getRuntime().exec("input keyevent 223"); } catch (IOException ignored) {}

        // Fail fast if hidden display txn APIs are not available
        if (!HiddenTxn.isAvailable()) {
            log("Fatal: HiddenTxn unavailable — cannot create virtual display on this platform");
            return;
        }
        
        // Stream output directly to file to avoid unbounded memory growth
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
             HiddenTxn.setDisplayProjection(displayToken, CROP, new Rect(0, 0, OUT_W, OUT_H));
             HiddenTxn.apply();

             long start = System.currentTimeMillis();
             // shorten duration for testing to avoid long-running issues
             final long DURATION_MS = 15_000;
             MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
 
             try (FileOutputStream fos = new FileOutputStream(outFile)) {
                while (System.currentTimeMillis() - start < DURATION_MS) {
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

             log("Dump complete: " + outFile.getAbsolutePath());
             //try { Runtime.getRuntime().exec("input keyevent 224"); } catch (IOException ignored) {}
         } catch (Throwable t) {
             log("Fatal: " + t);
         }
     }

     private static void log(String s) { System.out.println("[srv] " + s); }
 }