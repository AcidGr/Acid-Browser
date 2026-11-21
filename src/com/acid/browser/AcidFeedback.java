package com.acid.browser;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.ref.WeakReference;

/**
 * Unified user-facing feedback dispatcher for Acid Browser.
 *
 * <p>Prefers rendering a non-blocking in-app overlay pill inside the active host Activity
 * (so notifications never float above the system IME window or intercept keyboard input),
 * and falls back to coalesced {@link Toast} only when no host window has focus.
 */
public final class AcidFeedback {

    public static final int LEVEL_INFO = 0;
    public static final int LEVEL_OK = 1;
    public static final int LEVEL_WARN = 2;
    public static final int LEVEL_ERROR = 3;

    public static final long DURATION_SHORT_MS = 2000L;
    public static final long DURATION_LONG_MS = 3500L;

    public interface InAppToastHost {
        boolean showInAppToast(String translatedMessage, int level, long durationMs);
        void dismissInAppToast();
    }

    private static Toast sCurrentToast = null;
    private static WeakReference<InAppToastHost> sHostRef = null;

    private AcidFeedback() {}

    /**
     * Pure eligibility check for whether an in-app toast host can render directly above the
     * Activity content without being occluded by another window (Dialog / external Activity).
     */
    public static boolean isInAppHostEligible(boolean finishing, boolean destroyed, boolean hasWindowFocus) {
        return !finishing && !destroyed && hasWindowFocus;
    }

    public static synchronized void registerHost(InAppToastHost host) {
        sHostRef = host != null ? new WeakReference<InAppToastHost>(host) : null;
    }

    public static synchronized void unregisterHost(InAppToastHost host) {
        if (sHostRef != null && (host == null || sHostRef.get() == host)) {
            sHostRef = null;
        }
    }

    public static synchronized void dismiss() {
        if (sCurrentToast != null) {
            try {
                sCurrentToast.cancel();
            } catch (Exception ignored) {}
            sCurrentToast = null;
        }
        final InAppToastHost host = sHostRef != null ? sHostRef.get() : null;
        if (host != null) {
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    host.dismissInAppToast();
                }
            });
        }
    }

    public static void ok(Context context, String message) {
        show(context, message, LEVEL_OK, Toast.LENGTH_SHORT);
    }

    public static void info(Context context, String message) {
        show(context, message, LEVEL_INFO, Toast.LENGTH_SHORT);
    }

    public static void warn(Context context, String message) {
        show(context, message, LEVEL_WARN, Toast.LENGTH_SHORT);
    }

    public static void error(Context context, String message) {
        show(context, message, LEVEL_ERROR, Toast.LENGTH_LONG);
    }

    private static void runOnMain(Runnable action) {
        try {
            Looper mainLooper = Looper.getMainLooper();
            if (mainLooper == null || Looper.myLooper() == mainLooper) {
                action.run();
            } else {
                new Handler(mainLooper).post(action);
            }
        } catch (Throwable ignored) {
            // Safe no-op in headless/JVM test environments
        }
    }

    private static void show(final Context context, final String message, final int level, final int toastDuration) {
        if (context == null || message == null || message.isEmpty()) return;
        final String translated = AcidLocale.tr(message);
        final long durationMs = (toastDuration == Toast.LENGTH_LONG) ? DURATION_LONG_MS : DURATION_SHORT_MS;
        final Context appCtx;
        try {
            appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        } catch (Throwable ignored) {
            return;
        }

        runOnMain(new Runnable() {
            @Override
            public void run() {
                showOnMain(appCtx, translated, level, toastDuration, durationMs);
            }
        });
    }

    private static synchronized void showOnMain(
        Context appCtx,
        String translated,
        int level,
        int toastDuration,
        long durationMs
    ) {
        try {
            if (sCurrentToast != null) {
                sCurrentToast.cancel();
                sCurrentToast = null;
            }
            InAppToastHost host = sHostRef != null ? sHostRef.get() : null;
            if (host != null && host.showInAppToast(translated, level, durationMs)) {
                return;
            }
            sCurrentToast = Toast.makeText(appCtx, translated, toastDuration);
            sCurrentToast.show();
        } catch (Exception ignored) {
            // Safe no-op in headless/JVM test environments
        }
    }
}
