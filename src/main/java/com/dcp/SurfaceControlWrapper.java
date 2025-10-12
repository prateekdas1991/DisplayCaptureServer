package com.dcp;

import android.graphics.Rect;
import android.os.IBinder;
import android.util.Log;
import android.view.Surface;

import java.lang.reflect.Method;

public final class SurfaceControlWrapper {
    private static final String TAG = "VDD";

    private static Class<?> SC;
    private static Class<?> TransactionClass;

    // Core display/control methods
    private static Method createDisplayMethod;
    private static Method destroyDisplayMethod;

    // Getting the main display token: multiple fallbacks
    private static Method getBuiltInDisplayMethod;       // getBuiltInDisplay(int)           [older]
    private static Method getInternalDisplayTokenMethod; // getInternalDisplayToken()        [newer]
    private static Method getPhysicalDisplayTokenMethod; // getPhysicalDisplayToken(int)     [alt]

    // Transactions
    private static Method openTransactionMethod;
    private static Method closeTransactionMethod;

    // Static path (no Transaction)
    private static Method setDisplaySurfaceMethod;
    private static Method setDisplayProjectionMethod;
    private static Method setDisplayLayerStackMethod;

    // Transaction variants
    private static Method setDisplaySurfaceTMethod;
    private static Method setDisplayProjectionTMethod;
    private static Method setDisplayLayerStackTMethod;
    private static Method transactionApplyMethod;

    // Power control (optional, OEM/version-dependent)
    private static Method setDisplayPowerModeMethod;     // setDisplayPowerMode(IBinder,int)
    private static Method setDisplayEnabledMethod;       // setDisplayEnabled(IBinder,boolean)

    static {
        try {
            SC = Class.forName("android.view.SurfaceControl");
            TransactionClass = Class.forName("android.view.SurfaceControl$Transaction");

            // Display create/destroy
            try {
                createDisplayMethod = SC.getDeclaredMethod("createDisplay", String.class, boolean.class);
            } catch (NoSuchMethodException ignored) {}
            try {
                destroyDisplayMethod = SC.getDeclaredMethod("destroyDisplay", IBinder.class);
            } catch (NoSuchMethodException ignored) {}

            // Main display token fallbacks
            try {
                getBuiltInDisplayMethod = SC.getDeclaredMethod("getBuiltInDisplay", int.class);
            } catch (NoSuchMethodException ignored) {}
            try {
                getInternalDisplayTokenMethod = SC.getDeclaredMethod("getInternalDisplayToken");
            } catch (NoSuchMethodException ignored) {}
            try {
                getPhysicalDisplayTokenMethod = SC.getDeclaredMethod("getPhysicalDisplayToken", int.class);
            } catch (NoSuchMethodException ignored) {}

            // Transaction management
            try {
                openTransactionMethod = SC.getDeclaredMethod("openTransaction");
            } catch (NoSuchMethodException ignored) {}
            try {
                closeTransactionMethod = SC.getDeclaredMethod("closeTransaction");
            } catch (NoSuchMethodException ignored) {}

            // Static setters
            try {
                setDisplaySurfaceMethod = SC.getDeclaredMethod("setDisplaySurface", IBinder.class, Surface.class);
            } catch (NoSuchMethodException ignored) {}
            try {
                setDisplayProjectionMethod = SC.getDeclaredMethod("setDisplayProjection",
                        IBinder.class, int.class, Rect.class, Rect.class);
            } catch (NoSuchMethodException ignored) {}
            try {
                setDisplayLayerStackMethod = SC.getDeclaredMethod("setDisplayLayerStack", IBinder.class, int.class);
            } catch (NoSuchMethodException ignored) {}

            // Transaction setters
            try {
                setDisplaySurfaceTMethod = SC.getDeclaredMethod("setDisplaySurface",
                        TransactionClass, IBinder.class, Surface.class);
            } catch (NoSuchMethodException ignored) {}
            try {
                setDisplayProjectionTMethod = SC.getDeclaredMethod("setDisplayProjection",
                        TransactionClass, IBinder.class, int.class, Rect.class, Rect.class);
            } catch (NoSuchMethodException ignored) {}
            // Note: some builds don’t expose a Transaction variant for layer stack; use static method if needed
            try {
                setDisplayLayerStackTMethod = SC.getDeclaredMethod("setDisplayLayerStack",
                        IBinder.class, int.class);
            } catch (NoSuchMethodException ignored) {}

            try {
                transactionApplyMethod = TransactionClass.getDeclaredMethod("apply");
            } catch (NoSuchMethodException ignored) {}

            // Power control (these are optional and may not exist)
            try {
                setDisplayPowerModeMethod = SC.getDeclaredMethod("setDisplayPowerMode", IBinder.class, int.class);
            } catch (NoSuchMethodException ignored) {}
            try {
                setDisplayEnabledMethod = SC.getDeclaredMethod("setDisplayEnabled", IBinder.class, boolean.class);
            } catch (NoSuchMethodException ignored) {}

        } catch (Throwable t) {
            Log.e(TAG, "Reflection init failed", t);
        }
    }

    // --- Display management ---

    public static IBinder createDisplay(String name, boolean secure) {
        if (createDisplayMethod == null) throw new UnsupportedOperationException("createDisplay not available");
        try {
            return (IBinder) createDisplayMethod.invoke(null, name, secure);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void destroyDisplay(IBinder displayToken) {
        if (destroyDisplayMethod == null) return;
        try {
            destroyDisplayMethod.invoke(null, displayToken);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Obtain the main/internal display token, trying several hidden APIs.
     * Order: getInternalDisplayToken() -> getBuiltInDisplay(0) -> getPhysicalDisplayToken(0)
     */
    public static IBinder getBuiltInOrInternalDisplay() {
        try {
            if (getInternalDisplayTokenMethod != null) {
                IBinder tok = (IBinder) getInternalDisplayTokenMethod.invoke(null);
                if (tok != null) return tok;
            }
            if (getBuiltInDisplayMethod != null) {
                IBinder tok = (IBinder) getBuiltInDisplayMethod.invoke(null, 0 /* BUILT_IN_DISPLAY_ID_MAIN */);
                if (tok != null) return tok;
            }
            if (getPhysicalDisplayTokenMethod != null) {
                IBinder tok = (IBinder) getPhysicalDisplayTokenMethod.invoke(null, 0 /* main physical id */);
                if (tok != null) return tok;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        throw new UnsupportedOperationException("No method to obtain main display token is available on this build");
    }

    // --- Transactions ---

    public static void openTransaction() {
        if (openTransactionMethod == null) throw new UnsupportedOperationException("openTransaction not available");
        try {
            openTransactionMethod.invoke(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void closeTransaction() {
        if (closeTransactionMethod == null) throw new UnsupportedOperationException("closeTransaction not available");
        try {
            closeTransactionMethod.invoke(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --- Projection / Surface binding (static path) ---

    public static void setDisplaySurface(IBinder displayToken, Surface surface) {
        if (setDisplaySurfaceMethod == null) throw new UnsupportedOperationException("setDisplaySurface not available");
        try {
            setDisplaySurfaceMethod.invoke(null, displayToken, surface);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void setDisplayProjection(IBinder displayToken, int rotation, Rect layerStackRect, Rect displayRect) {
        if (setDisplayProjectionMethod == null) throw new UnsupportedOperationException("setDisplayProjection not available");
        try {
            setDisplayProjectionMethod.invoke(null, displayToken, rotation, layerStackRect, displayRect);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void setDisplayLayerStack(IBinder displayToken, int layerStack) {
        if (setDisplayLayerStackMethod == null) throw new UnsupportedOperationException("setDisplayLayerStack not available");
        try {
            setDisplayLayerStackMethod.invoke(null, displayToken, layerStack);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --- Projection / Surface binding (Transaction path) ---

    public static void setDisplaySurface(android.view.SurfaceControl.Transaction t, IBinder displayToken, Surface surface) {
        if (setDisplaySurfaceTMethod == null) throw new UnsupportedOperationException("Transaction setDisplaySurface not available");
        try {
            setDisplaySurfaceTMethod.invoke(null, t, displayToken, surface);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void setDisplayProjection(android.view.SurfaceControl.Transaction t, IBinder displayToken, int rotation, Rect layerStackRect, Rect displayRect) {
        if (setDisplayProjectionTMethod == null) throw new UnsupportedOperationException("Transaction setDisplayProjection not available");
        try {
            setDisplayProjectionTMethod.invoke(null, t, displayToken, rotation, layerStackRect, displayRect);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Some builds do not provide a Transaction variant for layer stack.
     * In such cases, call the static method before/after applying the transaction.
     */
    public static void setDisplayLayerStack(android.view.SurfaceControl.Transaction t, IBinder displayToken, int layerStack) {
        if (setDisplayLayerStackTMethod != null) {
            try {
                // Note: On many builds, setDisplayLayerStack only exists as a static method.
                setDisplayLayerStackTMethod.invoke(null, displayToken, layerStack);
                return;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        // Fallback to static if Transaction variant not available
        setDisplayLayerStack(displayToken, layerStack);
    }

    public static void applyTransaction(android.view.SurfaceControl.Transaction t) {
        if (transactionApplyMethod == null) throw new UnsupportedOperationException("Transaction.apply not available");
        try {
            transactionApplyMethod.invoke(t);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --- Power mode (optional) ---

    /**
     * Attempts to turn the display on/off. Tries setDisplayPowerMode first, then setDisplayEnabled.
     */
    public static void setDisplayPowerMode(IBinder displayToken, int mode) {
        try {
            if (setDisplayPowerModeMethod != null) {
                setDisplayPowerModeMethod.invoke(null, displayToken, mode);
                return;
            }
            if (setDisplayEnabledMethod != null) {
                boolean enabled = (mode != 0); // treat 0 as OFF, others as ON
                setDisplayEnabledMethod.invoke(null, displayToken, enabled);
                return;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        throw new UnsupportedOperationException("No power mode API available on this build");
    }
}