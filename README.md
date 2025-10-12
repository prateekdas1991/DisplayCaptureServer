📖 VirtualDisplayDumpServer – Usage Guide

## Features
- Crops a fixed output size (**248×144**) from a configurable start `(x,y)` coordinate.
- Encodes to H.264 (`video/avc`) at **1 Mbps**, **30 fps**, with **1s keyframe interval**.
- Duration is configurable, with a **default of 1 minute** and a **hard cap of 3 minutes**.

🔧 Push the JAR to device

```
adb push VirtualDisplayDumpServer.jar /data/local/tmp/

```


▶️ Run with defaults
Runs for 60 s, full screen, rotation = 0.

```
adb shell CLASSPATH=/data/local/tmp/VirtualDisplayDumpServer.jar \
    app_process / com.dcp.VirtualDisplayDumpServer
```


⏱️ Custom duration
Example: run for 90 s.

```
adb shell CLASSPATH=/data/local/tmp/VirtualDisplayDumpServer.jar \
    app_process / com.dcp.VirtualDisplayDumpServer --time 90000
```


✂️ Crop region
Syntax: --crop X:Y:W:H
Example: capture a 248×144 patch at (390,450) for 60 s.

```
adb shell CLASSPATH=/data/local/tmp/VirtualDisplayDumpServer.jar \
    app_process / com.dcp.VirtualDisplayDumpServer --time 60000 --crop 390:450:248:144
```

---

🔄 Rotation
Values:
- 0 = 0° (portrait)
- 1 = 90° (landscape)
- 2 = 180°
- 3 = 270°
Example: landscape capture.

```
adb shell CLASSPATH=/data/local/tmp/VirtualDisplayDumpServer.jar \
    app_process / com.dcp.VirtualDisplayDumpServer --time 60000 --crop 390:450:248:144 --rotation 1
```

---

🌙 Screen‑off mode
Turn off the physical screen during capture (like scrcpy -S).

```
adb shell CLASSPATH=/data/local/tmp/VirtualDisplayDumpServer.jar \
    app_process / com.dcp.VirtualDisplayDumpServer --time 60000 --crop 390:450:248:144 --screen-off
```

---

📂 Output
- Raw H.264 stream is written to:
/sdcard/kpi_dump.h264
- Pull it back:

```
adb pull /sdcard/kpi_dump.h264 .
```

- Optional: remux to MP4 for playback:

```
ffmpeg -r 30 -i kpi_dump.h264 -c copy out.mp4
```


✅ Summary
- --crop X:Y:W:H is required.
- --time, --rotation, and --screen-off are optional.
- Output is always /sdcard/kpi_dump.h264.


