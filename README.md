# DisplayCaptureServer

A lightweight Android display capture server that uses hidden `SurfaceControl.Transaction` APIs to create a virtual display, crop a region, and encode it to H.264 using `MediaCodec`.  
The encoded stream is dumped as raw NAL units (`dump.raw`) to `/sdcard/`.

⚠️ **Note:** This tool relies on hidden Android APIs (`SurfaceControl`) and is intended for system‑level testing/KPI measurement. It may not work on all Android versions without adapting the reflection layer (`HiddenTxn`).

---

## Features
- Crops a fixed output size (**248×144**) from a configurable start `(x,y)` coordinate.
- Encodes to H.264 (`video/avc`) at **1 Mbps**, **30 fps**, with **1s keyframe interval**.
- Duration is configurable, with a **default of 1 minute** and a **hard cap of 3 minutes**.
- Dumps raw H.264 stream to `/sdcard/dump.raw`.

---

## Build

Compile into a dex‑jar (e.g. with Maven + Android SDK):

```bash
mvn package -Pandroid-dex
```
---

Push to device:
```
adb push target/server-dex.jar /data/local/tmp/server.jar
```


Run
Launch via app_process:
```
adb shell CLASSPATH=/data/local/tmp/server.jar app_process / com.dcp.DisplayCaptureServer [durationMs] [startX] [startY]
```
---
Parameters
- durationMs (optional): capture duration in milliseconds.
- Default: 60000 (1 min)
- Max: 180000 (3 min cap)
- startX (optional): crop start X coordinate. Default: 390
- startY (optional): crop start Y coordinate. Default: 450

Examples
- Default (1 min, crop at 390,450):
```
adb shell CLASSPATH=/data/local/tmp/server.jar app_process / com.dcp.DisplayCaptureServer
```
---

- Custom duration (90 sec):
```
adb shell CLASSPATH=/data/local/tmp/server.jar app_process / com.dcp.DisplayCaptureServer 90000
```


- Custom duration + crop start (x=100, y=200):

```
adb shell CLASSPATH=/data/local/tmp/server.jar app_process / com.dcp.DisplayCaptureServer 120000 100 200
```


---
- Oversized request (10 min) → capped at 3 min:

```
adb shell CLASSPATH=/data/local/tmp/server.jar app_process / com.dcp.DisplayCaptureServer 600000
```

---
Output
- File: /sdcard/dump.raw
