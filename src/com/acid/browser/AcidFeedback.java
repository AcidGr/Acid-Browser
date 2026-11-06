package com.acid.browser;

import android.content.Context;
import android.widget.Toast;

/**
 * Unified user-facing feedback dispatcher for Acid Browser.
 *
 * <p>Replaces scattered {@code Toast.makeText(...).show()} calls across activities, controllers,
 * and sheets, and coalesces rapid notifications so consecutive actions never queue stale Toasts.
 */
public final class AcidFeedback {

    private static Toast sCurrentToast = null;

    private AcidFeedback() {}

    public static void ok(Context context, String message) {
        show(context, message, Toast.LENGTH_SHORT);
    }

    public static void info(Context context, String message) {
        show(context, message, Toast.LENGTH_SHORT);
    }

    public static void warn(Context context, String message) {
        show(context, message, Toast.LENGTH_SHORT);
    }

    public static void error(Context context, String message) {
        show(context, message, Toast.LENGTH_LONG);
    }

    private static synchronized void show(Context context, String message, int duration) {
        if (context == null || message == null || message.isEmpty()) return;
        try {
            if (sCurrentToast != null) {
                sCurrentToast.cancel();
            }
            sCurrentToast = Toast.makeText(context.getApplicationContext(), AcidLocale.tr(message), duration);
            sCurrentToast.show();
        } catch (Exception ignored) {
            // Safe no-op in headless/JVM test environments
        }
    }
}
