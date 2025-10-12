package com.dcp;

import android.graphics.Rect;
import android.os.IBinder;
import android.util.Log;
import android.view.Surface;

import java.lang.reflect.Method;

final class HiddenTxn {
    private static final String TAG = "pds6";

    private static Class<?> SC, TxnCls, DisplayTokenCls;
    private static Object txn;
    private static Method createDisplay, setDisplaySurface,
            setDisplayProjection4, setDisplayProjection3,
            releaseDisplay, apply;
    private static boolean available = true;
    private static Throwable initError = null;

    static {
        try {
            SC = Class.forName("android.view.SurfaceControl");
            TxnCls = Class.forName("android.view.SurfaceControl$Transaction");
            try {
                DisplayTokenCls = Class.forName("android.view.SurfaceControl$DisplayToken");
            } catch (ClassNotFoundException ignored) {
                DisplayTokenCls = null; // some builds use IBinder directly
            }

            txn = TxnCls.getDeclaredConstructor().newInstance();

            // SurfaceControl methods
            for (Method m : SC.getDeclaredMethods()) {
                String name = m.getName();
                m.setAccessible(true);
                if ("createDisplay".equals(name)) {
                    createDisplay = m; Log.i(TAG, "Found createDisplay: " + sig(m));
                } else if ("releaseDisplay".equals(name)) {
                    releaseDisplay = m; Log.i(TAG, "Found releaseDisplay: " + sig(m));
                }
            }

            // Transaction methods
            for (Method m : TxnCls.getDeclaredMethods()) {
                String name = m.getName();
                m.setAccessible(true);
                if ("setDisplaySurface".equals(name)) {
                    setDisplaySurface = m; Log.i(TAG, "Found setDisplaySurface: " + sig(m));
                } else if ("setDisplayProjection".equals(name)) {
                    if (m.getParameterTypes().length == 3) {
                        setDisplayProjection3 = m; Log.i(TAG, "Found 3-arg setDisplayProjection: " + sig(m));
                    } else if (m.getParameterTypes().length == 4) {
                        setDisplayProjection4 = m; Log.i(TAG, "Found 4-arg setDisplayProjection: " + sig(m));
                    }
                } else if ("apply".equals(name) && m.getParameterTypes().length == 0) {
                    apply = m; Log.i(TAG, "Found apply(): " + sig(m));
                }
            }

            // Minimal requirements (no getDisplayInfo hard dependency)
            if (createDisplay == null || setDisplaySurface == null ||
                (setDisplayProjection4 == null && setDisplayProjection3 == null) ||
                apply == null) {
                throw new NoSuchMethodException("Required SurfaceControl methods not found");
            }
        } catch (Exception e) {
            available = false;
            initError = e;
            Log.e(TAG, "HiddenTxn reflection init failed", e);
        }
    }

    static boolean isAvailable() { return available; }

    static Object createDisplay(String name, boolean secure) {
        ensureAvailable();
        try {
            Log.i(TAG, "Invoking createDisplay(" + name + ", secure=" + secure + ") via " + sig(createDisplay));
            Object token = createDisplay.invoke(null, name, secure);
            Log.i(TAG, "createDisplay returned token=" + typeOf(token));
            return token;
        } catch (Exception e) {
            Log.e(TAG, "createDisplay failed", e);
            throw new RuntimeException(e);
        }
    }

    static void setDisplaySurface(Object token, Surface surface) {
        ensureAvailable();
        try {
            Object adapted = adaptTokenFor(setDisplaySurface, token);
            Log.i(TAG, "Invoking setDisplaySurface(token=" + typeOf(adapted) + ", surface=" + surface + ")");
            Object ret = setDisplaySurface.invoke(txn, adapted, surface);
            Log.i(TAG, "setDisplaySurface done, ret=" + ret);
            applyNow("after setDisplaySurface");
        } catch (Exception e) {
            Log.e(TAG, "setDisplaySurface failed", e);
            throw new RuntimeException(e);
        }
    }

    // Full-screen crop with encoder-sized viewport; rotation parameter optional
    static void setDisplayProjection(Object token, Rect crop, Rect viewport, int rotation) {
        ensureAvailable();
        try {
            Object adapted3 = adaptTokenFor(setDisplayProjection3, token);
            Object adapted4 = adaptTokenFor(setDisplayProjection4, token);

            if (setDisplayProjection3 != null && adapted3 != null) {
                Log.i(TAG, "Invoking 3-arg setDisplayProjection(crop=" + crop + ", viewport=" + viewport + ")");
                Object ret = setDisplayProjection3.invoke(txn, adapted3, crop, viewport);
                Log.i(TAG, "setDisplayProjection(3) done, ret=" + ret);
                applyNow("after setDisplayProjection(3)");
                return;
            }

            if (setDisplayProjection4 != null && adapted4 != null) {
                Log.i(TAG, "Invoking 4-arg setDisplayProjection(rotation=" + rotation + ", crop=" + crop + ", viewport=" + viewport + ")");
                Object ret = setDisplayProjection4.invoke(txn, adapted4, rotation, crop, viewport);
                Log.i(TAG, "setDisplayProjection(4) done, ret=" + ret);
                applyNow("after setDisplayProjection(4)");
                return;
            }

            throw new RuntimeException("No compatible setDisplayProjection found for token type");
        } catch (Exception e) {
            Log.e(TAG, "setDisplayProjection failed", e);
            throw new RuntimeException(e);
        }
    }

    static void apply() {
        ensureAvailable();
        try {
            Log.i(TAG, "Invoking apply()");
            apply.invoke(txn);
        } catch (Exception e) {
            Log.e(TAG, "apply() failed", e);
            throw new RuntimeException(e);
        }
    }

    static void releaseDisplay(Object token) {
        if (!available) return;
        try {
            Object adapted = adaptTokenFor(releaseDisplay, token);
            Log.i(TAG, "Invoking releaseDisplay(token=" + typeOf(adapted) + ")");
            releaseDisplay.invoke(null, adapted);
        } catch (Exception e) {
            Log.w(TAG, "releaseDisplay failed (ignored)", e);
        }
    }

    // Helpers

    private static void ensureAvailable() {
        if (!available) throw new RuntimeException("HiddenTxn unavailable", initError);
    }

    private static void applyNow(String context) {
        try {
            Log.i(TAG, "apply() " + context);
            apply.invoke(txn);
            Thread.sleep(10);
            apply.invoke(txn);
        } catch (Throwable t) {
            Log.w(TAG, "applyNow failed", t);
        }
    }

    private static Object adaptTokenFor(Method m, Object token) {
        if (m == null || token == null) return null;
        Class<?> expected = m.getParameterTypes()[0];
        if (expected.isInstance(token)) return token;
        if (expected == IBinder.class && token instanceof IBinder) return token;
        if (DisplayTokenCls != null && expected == DisplayTokenCls && DisplayTokenCls.isInstance(token)) return token;
        Log.w(TAG, "Token type mismatch: expected=" + expected + " got=" + token.getClass());
        return null;
    }

    private static String sig(Method m) {
        if (m == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append(m.getDeclaringClass().getName()).append(".").append(m.getName()).append("(");
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(p[i].getName());
        }
        sb.append(")");
        return sb.toString();
    }

    private static String typeOf(Object o) {
        return (o == null) ? "null" : o.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(o));
    }
}