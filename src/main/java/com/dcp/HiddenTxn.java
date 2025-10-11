package com.dcp;

import android.view.Surface;
import android.graphics.Rect;
import android.os.IBinder;

import java.lang.reflect.*;

final class HiddenTxn {
    private static Class<?> SC, TxnCls, DisplayTokenCls, RectCls;
    private static Object txn;
    private static Method createDisplay, setDisplaySurface, setDisplayProjection4, setDisplayProjection3, releaseDisplay, apply;
    private static boolean available = true;
    private static Throwable initError = null;

    static {
        try {
            SC = Class.forName("android.view.SurfaceControl");
            TxnCls = Class.forName("android.view.SurfaceControl$Transaction");
            RectCls = Class.forName("android.graphics.Rect");
            try {
                DisplayTokenCls = Class.forName("android.view.SurfaceControl$DisplayToken");
            } catch (ClassNotFoundException ignored) {
                DisplayTokenCls = null;
            }

            txn = TxnCls.getDeclaredConstructor().newInstance();

            // createDisplay: prefer (String, boolean), but tolerate flag variants.
            for (Method m : SC.getDeclaredMethods()) {
                if (!"createDisplay".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && String.class.isAssignableFrom(p[0]) &&
                        (p[1] == boolean.class || p[1] == Boolean.class)) {
                    createDisplay = m; createDisplay.setAccessible(true); break;
                }
                // Some builds introduce flags: (String, int) or (String, long)
                if (p.length == 2 && String.class.isAssignableFrom(p[0]) &&
                        (p[1] == int.class || p[1] == long.class)) {
                    createDisplay = m; createDisplay.setAccessible(true); break;
                }
            }

            // setDisplaySurface(token, Surface)
            for (Method m : TxnCls.getDeclaredMethods()) {
                if (!"setDisplaySurface".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && Surface.class.isAssignableFrom(p[1])) {
                    // Accept any token type in p[0] (DisplayToken, IBinder, Object)
                    setDisplaySurface = m; setDisplaySurface.setAccessible(true); break;
                }
            }

            // setDisplayProjection variants:
            for (Method m : TxnCls.getDeclaredMethods()) {
                if (!"setDisplayProjection".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                // Older: (token, int rotation, Rect crop, Rect viewport)
                if (p.length == 4 && p[1] == int.class &&
                        RectCls.isAssignableFrom(p[2]) && RectCls.isAssignableFrom(p[3])) {
                    setDisplayProjection4 = m; setDisplayProjection4.setAccessible(true);
                }
                // Newer: (token, Rect crop, Rect viewport)
                if (p.length == 3 &&
                        RectCls.isAssignableFrom(p[1]) && RectCls.isAssignableFrom(p[2])) {
                    setDisplayProjection3 = m; setDisplayProjection3.setAccessible(true);
                }
            }

            // releaseDisplay(token) on SurfaceControl
            for (Method m : SC.getDeclaredMethods()) {
                if (!"releaseDisplay".equals(m.getName())) continue;
                if (m.getParameterTypes().length == 1) {
                    releaseDisplay = m; releaseDisplay.setAccessible(true); break;
                }
            }

            // apply() on Transaction
            for (Method m : TxnCls.getDeclaredMethods()) {
                if ("apply".equals(m.getName()) && m.getParameterTypes().length == 0) {
                    apply = m; apply.setAccessible(true); break;
                }
            }

            if (createDisplay == null || setDisplaySurface == null || (setDisplayProjection4 == null && setDisplayProjection3 == null) || apply == null) {
                throw new NoSuchMethodException("Required SurfaceControl methods not found via reflection");
            }
        } catch (Exception e) {
            available = false;
            initError = e;
            System.err.println("HiddenTxn reflection init failed: " + e);
            e.printStackTrace(System.err);
        }
    }

    static boolean isAvailable() { return available; }

    static Object createDisplay(String name, boolean secure) {
        if (!available) throw new RuntimeException("HiddenTxn unavailable", initError);
        try {
            Class<?>[] p = createDisplay.getParameterTypes();
            if (p.length == 2 && (p[1] == boolean.class || p[1] == Boolean.class)) {
                return createDisplay.invoke(null, name, secure);
            }
            // If flags signature: choose 0 for no flags; add secure if needed by setting a known bit.
            if (p.length == 2 && p[1] == int.class) {
                int flags = secure ? 0x1 /*SECURE_FLAG placeholder*/ : 0;
                return createDisplay.invoke(null, name, flags);
            }
            if (p.length == 2 && p[1] == long.class) {
                long flags = secure ? 0x1L : 0L;
                return createDisplay.invoke(null, name, flags);
            }
            // Fallback
            return createDisplay.invoke(null, name, secure);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static void setDisplaySurface(Object token, Surface surface) {
        if (!available) throw new RuntimeException("HiddenTxn unavailable", initError);
        try {
            setDisplaySurface.invoke(txn, adaptTokenFor(setDisplaySurface, token), surface);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static void setDisplayProjection(Object token, Rect crop, Rect viewport) {
        if (!available) throw new RuntimeException("HiddenTxn unavailable", initError);
        try {
            if (setDisplayProjection4 != null) {
                // rotation = 0
                setDisplayProjection4.invoke(txn, adaptTokenFor(setDisplayProjection4, token), 0, crop, viewport);
            } else if (setDisplayProjection3 != null) {
                setDisplayProjection3.invoke(txn, adaptTokenFor(setDisplayProjection3, token), crop, viewport);
            } else {
                throw new RuntimeException("No compatible setDisplayProjection found");
            }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static void apply() {
        if (!available) throw new RuntimeException("HiddenTxn unavailable", initError);
        try { apply.invoke(txn); } catch (Exception e) { throw new RuntimeException(e); }
    }

    static void releaseDisplay(Object token) {
        if (!available) return;
        try { releaseDisplay.invoke(null, adaptTokenFor(releaseDisplay, token)); } catch (Exception ignored) {}
    }

    // Try to adapt the token type expected by the reflected method.
    private static Object adaptTokenFor(Method m, Object token) {
        Class<?> expected = m.getParameterTypes()[0];
        if (expected.isInstance(token)) return token;
        // Some platforms expect IBinder instead of DisplayToken; often the returned token already is an IBinder.
        if (expected == IBinder.class && token instanceof IBinder) return token;
        // If we got DisplayTokenCls and method expects it, assume token is compatible.
        if (DisplayTokenCls != null && expected == DisplayTokenCls && DisplayTokenCls.isInstance(token)) return token;
        // Otherwise, pass through and let reflection fail loudly.
        return token;
    }
}