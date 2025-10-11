package com.dcp;

import android.view.Surface;
import android.graphics.Rect;
import java.lang.reflect.*;

final class HiddenTxn {
    private static Class<?> SC, TxnCls, DisplayTokenCls, RectCls;
    private static Object txn;
    private static Method createDisplay, setDisplaySurface, setDisplayProjection, releaseDisplay, apply;
    private static boolean available = true;
    private static Throwable initError = null;

    static {
        try {
            SC = Class.forName("android.view.SurfaceControl");
            TxnCls = Class.forName("android.view.SurfaceControl$Transaction");
            // DisplayToken may not exist on all platform versions — tolerate it.
            try {
                DisplayTokenCls = Class.forName("android.view.SurfaceControl$DisplayToken");
            } catch (ClassNotFoundException cnfe) {
                DisplayTokenCls = null; // will accept Object/IBinder as token where needed
            }
            RectCls = Class.forName("android.graphics.Rect");

            txn = TxnCls.getDeclaredConstructor().newInstance();

            // Find createDisplay(String, boolean) — keep tolerant to signatures across Android versions
            for (Method m : SC.getDeclaredMethods()) {
                if ("createDisplay".equals(m.getName())) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 2 && String.class.isAssignableFrom(p[0])
                            && (p[1] == boolean.class || p[1] == Boolean.class)) {
                        createDisplay = m;
                        createDisplay.setAccessible(true);
                        break;
                    }
                }
            }

            // setDisplaySurface(token, Surface)
            for (Method m : TxnCls.getDeclaredMethods()) {
                if ("setDisplaySurface".equals(m.getName())) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 2 && Surface.class.isAssignableFrom(p[1])) {
                        setDisplaySurface = m; setDisplaySurface.setAccessible(true); break;
                    }
                }
            }

            // setDisplayProjection(token, int, Rect, Rect)
            for (Method m : TxnCls.getDeclaredMethods()) {
                if ("setDisplayProjection".equals(m.getName())) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 4 && p[1] == int.class
                            && RectCls.isAssignableFrom(p[2]) && RectCls.isAssignableFrom(p[3])) {
                        setDisplayProjection = m; setDisplayProjection.setAccessible(true); break;
                    }
                }
            }

            // releaseDisplay(token) on SurfaceControl
            for (Method m : SC.getDeclaredMethods()) {
                if ("releaseDisplay".equals(m.getName())) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1) { releaseDisplay = m; releaseDisplay.setAccessible(true); break; }
                }
            }

            // apply() on Transaction
            for (Method m : TxnCls.getDeclaredMethods()) {
                if ("apply".equals(m.getName()) && m.getParameterTypes().length == 0) {
                    apply = m; apply.setAccessible(true); break;
                }
            }

            // Verify we found required methods
            if (createDisplay == null || setDisplaySurface == null || setDisplayProjection == null || apply == null) {
                throw new NoSuchMethodException("Required SurfaceControl methods not found via reflection");
            }
        } catch (Exception e) {
            // Don't throw from static initializer — record the root cause and mark as unavailable.
            available = false;
            initError = e;
            System.err.println("HiddenTxn reflection init failed: " + e);
            e.printStackTrace(System.err);
        }
    }

    static boolean isAvailable() { return available; }

    static Object createDisplay(String name, boolean secure) {
        if (!available) throw new RuntimeException("HiddenTxn unavailable", initError);
        try { return createDisplay.invoke(null, name, secure); } catch (Exception e) { throw new RuntimeException(e); }
    }
    static void setDisplaySurface(Object token, Surface surface) {
        if (!available) throw new RuntimeException("HiddenTxn unavailable", initError);
        try { setDisplaySurface.invoke(txn, token, surface); } catch (Exception e) { throw new RuntimeException(e); }
    }
    static void setDisplayProjection(Object token, Rect crop, Rect out) {
        if (!available) throw new RuntimeException("HiddenTxn unavailable", initError);
        try { setDisplayProjection.invoke(txn, token, 0, crop, out); } catch (Exception e) { throw new RuntimeException(e); }
    }
    static void apply() {
        if (!available) throw new RuntimeException("HiddenTxn unavailable", initError);
        try { apply.invoke(txn); } catch (Exception e) { throw new RuntimeException(e); }
    }
    static void releaseDisplay(Object token) {
        if (!available) return;
        try { releaseDisplay.invoke(null, token); } catch (Exception ignored) {}
    }
}