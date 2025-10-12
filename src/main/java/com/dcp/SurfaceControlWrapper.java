package com.dcp;

import android.graphics.Rect;
import android.os.IBinder;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class SurfaceControlWrapper {
    // Core classes
    private static final Class<?> SC; // android.view.SurfaceControl
    private static final Class<?> TransactionClass; // android.view.SurfaceControl$Transaction

    // Display creation/destroy
    private static Method createDisplay;
    private static Method destroyDisplay;

    // Static transaction methods (preferred, like scrcpy)
    private static Method openTransaction;
    private static Method closeTransaction;
    private static Method setDisplaySurfaceStatic;     // SurfaceControl.setDisplaySurface(IBinder, Surface)
    private static Method setDisplayProjectionStatic;  // SurfaceControl.setDisplayProjection(IBinder, int, Rect, Rect)
    private static Method setDisplayLayerStackStatic;  // SurfaceControl.setDisplayLayerStack(IBinder, int)

    // Instance Transaction methods (fallback)
    private static Method setDisplaySurfaceTxn;        // Transaction.setDisplaySurface(IBinder, Surface)
    private static Method setDisplayProjectionTxn;     // Transaction.setDisplayProjection(IBinder, int, Rect, Rect)

    // Built-in/internal/physical display token discovery (multiple fallbacks)
    private static Method getBuiltInDisplay;           // SurfaceControl.getBuiltInDisplay(int)
    private static Method getInternalDisplayToken;     // SurfaceControl.getInternalDisplayToken()
    private static Method getPhysicalDisplayIds;       // SurfaceControl.getPhysicalDisplayIds()
    private static Method getPhysicalDisplayToken;     // SurfaceControl.getPhysicalDisplayToken(long)

    // Display info via DisplayManagerGlobal (preferred)
    private static Class<?> DisplayManagerGlobalClass;
    private static Method getInstanceDMG;
    private static Method getDisplayInfoDMG;
    private static Class<?> DisplayInfoClass;
    private static Constructor<?> DisplayInfoCtor;
    private static Field appWidthField;
    private static Field appHeightField;
    private static Field rotationField;
    private static Field layerStackField; // may not exist on newer builds

    // Optional SurfaceControl.getDisplayInfo(binder, DisplayInfo)
    private static Method getDisplayInfoSC;

    static {
        try {
            SC = Class.forName("android.view.SurfaceControl");
            TransactionClass = Class.forName("android.view.SurfaceControl$Transaction");

            // Display create/destroy
            createDisplay = find(SC, "createDisplay", String.class, boolean.class);
            destroyDisplay = find(SC, "destroyDisplay", IBinder.class);

            // Static transaction path (scrcpy-preferred)
            openTransaction = find(SC, "openTransaction");
            closeTransaction = find(SC, "closeTransaction");
            setDisplaySurfaceStatic = find(SC, "setDisplaySurface", IBinder.class, Surface.class);
            setDisplayProjectionStatic = find(SC, "setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class);
            setDisplayLayerStackStatic = find(SC, "setDisplayLayerStack", IBinder.class, int.class);

            // Transaction instance methods (fallback)
            setDisplaySurfaceTxn = find(TransactionClass, "setDisplaySurface", IBinder.class, Surface.class);
            setDisplayProjectionTxn = find(TransactionClass, "setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class);

            // Built-in display token methods (varies by API/OEM)
            getBuiltInDisplay = find(SC, "getBuiltInDisplay", int.class);
            getInternalDisplayToken = find(SC, "getInternalDisplayToken");
            getPhysicalDisplayIds = find(SC, "getPhysicalDisplayIds");
            getPhysicalDisplayToken = find(SC, "getPhysicalDisplayToken", long.class);

            // Display info classes/methods
            try {
                DisplayInfoClass = Class.forName("android.view.DisplayInfo");
                getDisplayInfoSC = find(SC, "getDisplayInfo", IBinder.class, DisplayInfoClass);
            } catch (Throwable ignored) {
                DisplayInfoClass = null;
                getDisplayInfoSC = null;
            }

            // DisplayManagerGlobal path
            try {
                DisplayManagerGlobalClass = Class.forName("android.view.DisplayManagerGlobal");
                getInstanceDMG = find(DisplayManagerGlobalClass, "getInstance");
                getDisplayInfoDMG = find(DisplayManagerGlobalClass, "getDisplayInfo", int.class);

                if (DisplayInfoClass == null) {
                    DisplayInfoClass = Class.forName("android.view.DisplayInfo");
                }
                DisplayInfoCtor = DisplayInfoClass.getDeclaredConstructor();
                DisplayInfoCtor.setAccessible(true);

                appWidthField = findField(DisplayInfoClass, "appWidth");
                appHeightField = findField(DisplayInfoClass, "appHeight");
                rotationField = findFieldAny(DisplayInfoClass, "rotation", "logicalRotation");
                layerStackField = findField(DisplayInfoClass, "layerStack");
            } catch (Throwable ignored) {
            }
        } catch (Exception e) {
            throw new RuntimeException("SurfaceControlWrapper init failed", e);
        }
    }

    private SurfaceControlWrapper() {}

    // ------- helpers -------

    private static Method find(Class<?> cls, String name, Class<?>... params) {
        try {
            Method m = cls.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable e) {
            return null;
        }
    }

    private static Field findField(Class<?> cls, String name) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable e) {
            return null;
        }
    }

    private static Field findFieldAny(Class<?> cls, String... names) {
        for (String n : names) {
            Field f = findField(cls, n);
            if (f != null) return f;
        }
        return null;
    }

    // ------- display create/destroy -------

    public static IBinder createDisplay(String name, boolean secure) {
        if (createDisplay == null) throw new RuntimeException("createDisplay hidden method unavailable");
        try {
            return (IBinder) createDisplay.invoke(null, name, secure);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void destroyDisplay(IBinder display) {
        if (destroyDisplay == null) return;
        try {
            destroyDisplay.invoke(null, display);
        } catch (Exception ignored) {
        }
    }

    // ------- built-in/internal display token -------

    public static IBinder getBuiltInOrInternalDisplay() {
        // 1) built-in(0)
        if (getBuiltInDisplay != null) {
            try {
                return (IBinder) getBuiltInDisplay.invoke(null, 0);
            } catch (Exception ignored) {}
        }
        // 2) internal token
        if (getInternalDisplayToken != null) {
            try {
                return (IBinder) getInternalDisplayToken.invoke(null);
            } catch (Exception ignored) {}
        }
        // 3) physical displays
        if (getPhysicalDisplayIds != null && getPhysicalDisplayToken != null) {
            try {
                long[] ids = (long[]) getPhysicalDisplayIds.invoke(null);
                if (ids != null && ids.length > 0) {
                    return (IBinder) getPhysicalDisplayToken.invoke(null, ids[0]);
                }
            } catch (Exception ignored) {}
        }
        throw new RuntimeException("Unable to resolve internal display token");
    }

    // ------- static transaction path (preferred) -------

    public static void openTransaction() {
        if (openTransaction == null) return;
        try {
            openTransaction.invoke(null);
        } catch (Exception ignored) {
        }
    }

    public static void closeTransaction() {
        if (closeTransaction == null) return;
        try {
            closeTransaction.invoke(null);
        } catch (Exception ignored) {
        }
    }

    public static void setDisplaySurface(IBinder display, Surface surface) {
        if (setDisplaySurfaceStatic == null) throw new RuntimeException("setDisplaySurface(hidden) unavailable");
        try {
            setDisplaySurfaceStatic.invoke(null, display, surface);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void setDisplayProjection(IBinder display, int rotation, Rect crop, Rect viewport) {
        if (setDisplayProjectionStatic == null) throw new RuntimeException("setDisplayProjection(hidden) unavailable");
        try {
            setDisplayProjectionStatic.invoke(null, display, rotation, crop, viewport);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void setDisplayLayerStack(IBinder display, int layerStack) {
        if (setDisplayLayerStackStatic == null) return;
        try {
            setDisplayLayerStackStatic.invoke(null, display, layerStack);
        } catch (Exception ignored) {
        }
    }

    // ------- Transaction instance fallback -------

    public static void setDisplaySurface(Object txn, IBinder display, Surface surface) {
        if (setDisplaySurfaceTxn == null) throw new RuntimeException("Transaction.setDisplaySurface(hidden) unavailable");
        try {
            setDisplaySurfaceTxn.invoke(txn, display, surface);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void setDisplayProjection(Object txn, IBinder display, int rotation, Rect crop, Rect viewport) {
        if (setDisplayProjectionTxn == null) throw new RuntimeException("Transaction.setDisplayProjection(hidden) unavailable");
        try {
            setDisplayProjectionTxn.invoke(txn, display, rotation, crop, viewport);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------- display info -------

    public static class DisplayInfoData {
        public final int width;
        public final int height;
        public final int rotation;
        public final Integer layerStack;

        public DisplayInfoData(int width, int height, int rotation, Integer layerStack) {
            this.width = width;
            this.height = height;
            this.rotation = rotation;
            this.layerStack = layerStack;
        }
    }

    public static DisplayInfoData queryBuiltInDisplayInfo() {
        // DMG path
        try {
            if (getInstanceDMG != null && getDisplayInfoDMG != null && DisplayInfoCtor != null) {
                Object dmg = getInstanceDMG.invoke(null);
                Object di = getDisplayInfoDMG.invoke(dmg, 0);
                if (di != null) {
                    int w = getInt(di, appWidthField, 1080);
                    int h = getInt(di, appHeightField, 1920);
                    int rot = getInt(di, rotationField, 0);
                    Integer ls = getInteger(di, layerStackField);
                    return new DisplayInfoData(w, h, rot, ls);
                }
            }
        } catch (Throwable ignored) {}

        // SC.getDisplayInfo fallback (if present)
        try {
            if (getDisplayInfoSC != null && DisplayInfoCtor != null) {
                IBinder builtIn = getBuiltInOrInternalDisplay();
                Object di = DisplayInfoCtor.newInstance();
                Object ok = getDisplayInfoSC.invoke(null, builtIn, di);
                if (ok != null) {
                    int w = getInt(di, appWidthField, 1080);
                    int h = getInt(di, appHeightField, 1920);
                    int rot = getInt(di, rotationField, 0);
                    Integer ls = getInteger(di, layerStackField);
                    return new DisplayInfoData(w, h, rot, ls);
                }
            }
        } catch (Throwable ignored) {}

        // Default fallback
        return new DisplayInfoData(1080, 1920, 0, null);
    }

    private static int getInt(Object obj, Field field, int def) {
        try {
            if (field != null) return (int) field.get(obj);
        } catch (Throwable ignored) {}
        return def;
    }

    private static Integer getInteger(Object obj, Field field) {
        try {
            if (field != null) return (Integer) field.get(obj);
        } catch (Throwable ignored) {}
        return null;
    }
}