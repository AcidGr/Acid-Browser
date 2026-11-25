package com.acid.browser;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Choreographer;
import android.view.View;
import android.view.Window;
import android.webkit.WebView;
import android.widget.FrameLayout;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pure Android Window hardware frame capturer backed by API 26+ {@code PixelCopy}.
 *
 * <p>Has zero dependencies on browser business logic or specific sheet/overlay classes. Coordinates
 * single-frame non-primary child masking via {@link MaskingFrameLayout} and {@link Choreographer}
 * traversal sync so GPU-composited CSS animations, 3D transforms, {@code backdrop-filter}, and
 * WebGL/Canvas layers are captured verbatim on the foreground tab without modifying overlay view
 * visibility, alpha, focus, or IME state.
 */
public final class WindowPixelCopyCapturer {

    private static final String TAG = "WindowPixelCopy";
    private static final int PIXEL_COPY_SUCCESS = 0;
    private static final long MASK_WATCHDOG_TIMEOUT_MS = 450L;
    private static final int MAX_MASK_FRAME_ATTEMPTS = 2;

    /**
     * Implemented by always-attached HUD overlays whose {@link View#getVisibility()} stays
     * {@link View#VISIBLE} even when drawing nothing.
     */
    public interface ConditionalVisualOverlay {
        boolean hasVisibleVisualContent();
    }

    /**
     * Abstracts overlay masking across one or more container layouts without exposing concrete UI
     * views to {@link WindowPixelCopyCapturer}.
     */
    public interface OverlayMaskController {
        boolean hasVisibleOverlays();
        void setOverlaysMasked(boolean masked);
        boolean hasRecordedMaskedFrame();
    }

    /**
     * Delivers the captured hardware viewport bitmap and its exact window copy bounds, or
     * {@code (null, null)} when hardware capture is ineligible or fails so callers fall back
     * cleanly to software drawing.
     */
    public interface BitmapCaptureCallback {
        void onCaptureComplete(Bitmap capturedBitmap, CopyBounds bounds);
    }

    /**
     * Pure-Java immutable geometry describing the intersection between a target View's window
     * rectangle and the host DecorView's bounds.
     */
    public static final class CopyBounds {
        public final boolean valid;
        public final boolean exactFullMatch;
        public final int srcLeft;
        public final int srcTop;
        public final int srcRight;
        public final int srcBottom;
        public final int copyWidth;
        public final int copyHeight;
        public final int destOffsetX;
        public final int destOffsetY;

        public CopyBounds(
            boolean valid,
            boolean exactFullMatch,
            int srcLeft,
            int srcTop,
            int srcRight,
            int srcBottom,
            int copyWidth,
            int copyHeight,
            int destOffsetX,
            int destOffsetY
        ) {
            this.valid = valid;
            this.exactFullMatch = exactFullMatch;
            this.srcLeft = srcLeft;
            this.srcTop = srcTop;
            this.srcRight = srcRight;
            this.srcBottom = srcBottom;
            this.copyWidth = copyWidth;
            this.copyHeight = copyHeight;
            this.destOffsetX = destOffsetX;
            this.destOffsetY = destOffsetY;
        }

        public static CopyBounds invalid() {
            return new CopyBounds(false, false, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * Computes the exact 1:1 unstretched PixelCopy source rectangle clipped to the host DecorView
     * and the corresponding offset into a {@code viewW x viewH} target bitmap.
     */
    public static CopyBounds computeWindowCopyBounds(
        int winX,
        int winY,
        int viewW,
        int viewH,
        int decorW,
        int decorH
    ) {
        if (viewW <= 0 || viewH <= 0 || decorW <= 0 || decorH <= 0) {
            return CopyBounds.invalid();
        }
        int srcLeft = Math.max(0, winX);
        int srcTop = Math.max(0, winY);
        int srcRight = Math.min(decorW, winX + viewW);
        int srcBottom = Math.min(decorH, winY + viewH);
        int copyW = srcRight - srcLeft;
        int copyH = srcBottom - srcTop;
        if (copyW <= 0 || copyH <= 0) {
            return CopyBounds.invalid();
        }
        int destOffsetX = srcLeft - winX;
        int destOffsetY = srcTop - winY;
        boolean exactFullMatch = (destOffsetX == 0 && destOffsetY == 0
            && copyW == viewW && copyH == viewH);
        return new CopyBounds(
            true,
            exactFullMatch,
            srcLeft,
            srcTop,
            srcRight,
            srcBottom,
            copyW,
            copyH,
            destOffsetX,
            destOffsetY
        );
    }

    /**
     * Pure eligibility check determining whether GPU frame readback via {@code PixelCopy} may run.
     */
    public static boolean isHardwareCaptureEligible(
        int sdkInt,
        boolean reflectorAvailable,
        boolean onMainLooper,
        boolean isForegroundTab,
        boolean customViewShowing,
        boolean webViewShown,
        boolean hardwareAccelerated,
        int viewW,
        int viewH,
        int decorW,
        int decorH
    ) {
        return sdkInt >= 26
            && reflectorAvailable
            && !onMainLooper
            && isForegroundTab
            && !customViewShowing
            && webViewShown
            && hardwareAccelerated
            && viewW > 0
            && viewH > 0
            && decorW > 0
            && decorH > 0;
    }

    /**
     * Re-entrant, idempotent reference-counted guard controlling {@link OverlayMaskController}.
     */
    public static final class SuppressionGuard {
        public static final class ReleaseHandle {
            private final SuppressionGuard mOwner;
            private final AtomicBoolean mReleased = new AtomicBoolean(false);

            private ReleaseHandle(SuppressionGuard owner) {
                mOwner = owner;
            }

            public void release() {
                if (mReleased.compareAndSet(false, true)) {
                    mOwner.releaseOne();
                }
            }
        }

        private final OverlayMaskController mController;
        private int mCount = 0;

        public SuppressionGuard(OverlayMaskController controller) {
            mController = controller;
        }

        public synchronized ReleaseHandle acquire() {
            mCount++;
            if (mCount == 1 && mController != null) {
                mController.setOverlaysMasked(true);
            }
            return new ReleaseHandle(this);
        }

        private synchronized void releaseOne() {
            if (mCount <= 0) {
                return;
            }
            mCount--;
            if (mCount == 0 && mController != null) {
                mController.setOverlaysMasked(false);
            }
        }

        public synchronized int activeCount() {
            return mCount;
        }

        public synchronized void releaseAll() {
            if (mCount > 0) {
                mCount = 0;
                if (mController != null) {
                    mController.setOverlaysMasked(false);
                }
            }
        }
    }

    /**
     * Generic {@link FrameLayout} capable of skipping all non-primary child views during
     * {@link #drawChild} for a single draw pass without altering visibility, alpha, focus, or IME.
     */
    public static class MaskingFrameLayout extends FrameLayout {
        private View mPrimaryChild;
        private boolean mMaskOverlays = false;
        private boolean mRecordedMaskedDraw = false;

        public MaskingFrameLayout(Context context) {
            super(context);
            // Guarantee invalidate() marks this container dirty and drives dispatchDraw()
            setWillNotDraw(false);
        }

        public void setPrimaryChild(View primaryChild) {
            mPrimaryChild = primaryChild;
        }

        public void setMaskOverlays(boolean mask) {
            boolean changed = (mMaskOverlays != mask);
            mMaskOverlays = mask;
            if (mask) {
                mRecordedMaskedDraw = false;
                invalidate();
            } else if (changed) {
                mRecordedMaskedDraw = false;
                invalidate();
            }
        }

        public boolean isMaskingOverlays() {
            return mMaskOverlays;
        }

        public boolean hasRecordedMaskedDraw() {
            return mRecordedMaskedDraw;
        }

        public boolean hasVisibleNonPrimaryChild() {
            int count = getChildCount();
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child == null || child == mPrimaryChild) continue;
                if (child.getVisibility() != View.VISIBLE) continue;
                if (child.getAlpha() <= 0f) continue;
                if (child.getWidth() <= 0 || child.getHeight() <= 0) continue;
                if (child instanceof ConditionalVisualOverlay) {
                    if (((ConditionalVisualOverlay) child).hasVisibleVisualContent()) {
                        return true;
                    }
                } else {
                    return true;
                }
            }
            return false;
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (mMaskOverlays) {
                mRecordedMaskedDraw = true;
            }
        }

        @Override
        protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
            if (mMaskOverlays && mPrimaryChild != null && child != mPrimaryChild) {
                return false;
            }
            return super.drawChild(canvas, child, drawingTime);
        }
    }

    private interface IntResultListener {
        void onResult(int copyResult);
    }

    private static final class PixelCopyReflector {
        private static volatile boolean sInitAttempted = false;
        private static volatile boolean sAvailable = false;
        private static Class<?> sListenerClass;
        private static Method sRequestWindowMethod;

        static boolean isAvailable() {
            ensureInit();
            return sAvailable;
        }

        private static synchronized void ensureInit() {
            if (sInitAttempted) return;
            sInitAttempted = true;
            if (Build.VERSION.SDK_INT < 26) {
                sAvailable = false;
                return;
            }
            try {
                Class<?> pcClass = Class.forName("android.view.PixelCopy");
                Class<?> listenerClass = Class.forName("android.view.PixelCopy$OnPixelCopyFinishedListener");
                Method reqMethod = pcClass.getMethod(
                    "request",
                    Window.class,
                    Rect.class,
                    Bitmap.class,
                    listenerClass,
                    Handler.class
                );
                sListenerClass = listenerClass;
                sRequestWindowMethod = reqMethod;
                sAvailable = true;
            } catch (Throwable t) {
                Log.w(TAG, "PixelCopy reflection unavailable; falling back to software draw", t);
                sAvailable = false;
            }
        }

        static boolean request(
            Window window,
            Rect srcRect,
            Bitmap destBitmap,
            final IntResultListener listener,
            Handler handler
        ) {
            ensureInit();
            if (!sAvailable || window == null || srcRect == null || destBitmap == null || listener == null || handler == null) {
                return false;
            }
            try {
                Object proxy = Proxy.newProxyInstance(
                    sListenerClass.getClassLoader(),
                    new Class<?>[] { sListenerClass },
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object p, Method method, Object[] args) {
                            String name = method.getName();
                            if ("onPixelCopyFinished".equals(name) && args != null && args.length == 1 && args[0] instanceof Integer) {
                                listener.onResult(((Integer) args[0]).intValue());
                                return null;
                            }
                            if ("equals".equals(name)) {
                                return args != null && args.length == 1 && p == args[0];
                            }
                            if ("hashCode".equals(name)) {
                                return System.identityHashCode(p);
                            }
                            if ("toString".equals(name)) {
                                return "PixelCopyListenerProxy";
                            }
                            return null;
                        }
                    }
                );
                sRequestWindowMethod.invoke(null, window, srcRect, destBitmap, proxy, handler);
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "PixelCopy.request invocation failed", t);
                return false;
            }
        }
    }

    private final class PendingCapture {
        final BitmapCaptureCallback callback;
        final AtomicBoolean done = new AtomicBoolean(false);
        SuppressionGuard.ReleaseHandle maskHandle;
        Choreographer.FrameCallback frameCallback;
        Runnable postTraversalRunnable;
        Runnable watchdogRunnable;

        PendingCapture(BitmapCaptureCallback callback) {
            this.callback = callback;
        }

        void finish(Bitmap bmp, CopyBounds bounds) {
            if (!done.compareAndSet(false, true)) {
                recycleQuietly(bmp);
                return;
            }
            removePending(this);
            cancelFrameAndWatchdog();
            if (maskHandle != null) {
                maskHandle.release();
                maskHandle = null;
            }
            callback.onCaptureComplete(bmp, bounds);
        }

        void cancelFrameAndWatchdog() {
            if (frameCallback != null) {
                try {
                    Choreographer.getInstance().removeFrameCallback(frameCallback);
                } catch (Throwable ignored) {}
                frameCallback = null;
            }
            if (postTraversalRunnable != null) {
                mMainHandler.removeCallbacks(postTraversalRunnable);
                postTraversalRunnable = null;
            }
            if (watchdogRunnable != null) {
                mMainHandler.removeCallbacks(watchdogRunnable);
                watchdogRunnable = null;
            }
        }
    }

    private final Activity mActivity;
    private final OverlayMaskController mMaskController;
    private final SuppressionGuard mSuppressionGuard;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final List<PendingCapture> mPendingCaptures = new ArrayList<PendingCapture>();
    private volatile boolean mShutdown = false;

    public WindowPixelCopyCapturer(Activity activity, OverlayMaskController maskController) {
        mActivity = activity;
        mMaskController = maskController;
        mSuppressionGuard = new SuppressionGuard(maskController);
    }

    private void addPending(PendingCapture pending) {
        synchronized (mPendingCaptures) {
            mPendingCaptures.add(pending);
        }
    }

    private void removePending(PendingCapture pending) {
        synchronized (mPendingCaptures) {
            mPendingCaptures.remove(pending);
        }
    }

    /**
     * Must run on the Android UI thread. Captures a 1:1 unstretched GPU frame of {@code wv}'s
     * visible viewport if eligible, masking overlapping sibling overlays for at most one frame
     * when needed. Always invokes {@code callback} exactly once.
     */
    public void captureViewportOnUiThread(
        final WebView wv,
        final boolean isForegroundTab,
        final boolean customViewShowing,
        final boolean onMainLooper,
        final BitmapCaptureCallback callback
    ) {
        if (callback == null) return;
        if (mShutdown || mActivity == null || wv == null || isActivityInvalid()) {
            callback.onCaptureComplete(null, null);
            return;
        }
        final Window window = mActivity.getWindow();
        final View decorView = window != null ? window.peekDecorView() : null;
        final int viewW = wv.getWidth();
        final int viewH = wv.getHeight();
        final int decorW = decorView != null ? decorView.getWidth() : 0;
        final int decorH = decorView != null ? decorView.getHeight() : 0;

        if (!isHardwareCaptureEligible(
            Build.VERSION.SDK_INT,
            PixelCopyReflector.isAvailable(),
            onMainLooper,
            isForegroundTab,
            customViewShowing,
            wv.isShown() && wv.getParent() != null,
            wv.isHardwareAccelerated(),
            viewW,
            viewH,
            decorW,
            decorH
        )) {
            callback.onCaptureComplete(null, null);
            return;
        }

        CopyBounds initialBounds = measureWindowCopyBounds(wv, decorView, viewW, viewH, decorW, decorH);
        if (!initialBounds.valid) {
            callback.onCaptureComplete(null, null);
            return;
        }

        final PendingCapture pending = new PendingCapture(callback);
        addPending(pending);

        boolean needsMask = (mMaskController != null && mMaskController.hasVisibleOverlays());
        if (!needsMask) {
            executePixelCopy(pending, window, decorView, wv, viewW, viewH, initialBounds);
            return;
        }

        pending.maskHandle = mSuppressionGuard.acquire();
        pending.watchdogRunnable = new Runnable() {
            @Override
            public void run() {
                pending.finish(null, null);
            }
        };
        mMainHandler.postDelayed(pending.watchdogRunnable, MASK_WATCHDOG_TIMEOUT_MS);

        scheduleMaskedFrameCheck(pending, window, decorView, wv, viewW, viewH, 1);
    }

    private void scheduleMaskedFrameCheck(
        final PendingCapture pending,
        final Window window,
        final View decorView,
        final WebView wv,
        final int expectedViewW,
        final int expectedViewH,
        final int attempt
    ) {
        if (mShutdown || pending.done.get()) {
            pending.finish(null, null);
            return;
        }
        pending.frameCallback = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                pending.frameCallback = null;
                if (mShutdown || pending.done.get()) {
                    pending.finish(null, null);
                    return;
                }
                pending.postTraversalRunnable = new Runnable() {
                    @Override
                    public void run() {
                        pending.postTraversalRunnable = null;
                        if (mShutdown || pending.done.get()) {
                            pending.finish(null, null);
                            return;
                        }
                        if (mMaskController != null && !mMaskController.hasRecordedMaskedFrame()) {
                            if (attempt < MAX_MASK_FRAME_ATTEMPTS) {
                                scheduleMaskedFrameCheck(
                                    pending, window, decorView, wv, expectedViewW, expectedViewH, attempt + 1
                                );
                                return;
                            }
                            // Never risk capturing an unmasked floating overlay into webpage pixels
                            pending.finish(null, null);
                            return;
                        }
                        if (isActivityInvalid() || wv.getParent() == null || !wv.isShown()
                            || wv.getWidth() != expectedViewW || wv.getHeight() != expectedViewH) {
                            pending.finish(null, null);
                            return;
                        }
                        CopyBounds latestBounds = measureWindowCopyBounds(
                            wv, decorView, expectedViewW, expectedViewH, decorView.getWidth(), decorView.getHeight()
                        );
                        if (!latestBounds.valid) {
                            pending.finish(null, null);
                            return;
                        }
                        executePixelCopy(pending, window, decorView, wv, expectedViewW, expectedViewH, latestBounds);
                    }
                };
                // Runs on MainLooper right after the current Choreographer traversal & RenderThread sync
                mMainHandler.post(pending.postTraversalRunnable);
            }
        };
        try {
            Choreographer.getInstance().postFrameCallback(pending.frameCallback);
        } catch (Throwable t) {
            pending.finish(null, null);
        }
    }

    private void executePixelCopy(
        final PendingCapture pending,
        Window window,
        View decorView,
        WebView wv,
        int expectedViewW,
        int expectedViewH,
        final CopyBounds bounds
    ) {
        if (mShutdown || pending.done.get() || isActivityInvalid()
            || wv.getParent() == null || !wv.isShown()
            || wv.getWidth() != expectedViewW || wv.getHeight() != expectedViewH) {
            pending.finish(null, null);
            return;
        }
        Bitmap destBitmap = null;
        try {
            destBitmap = Bitmap.createBitmap(bounds.copyWidth, bounds.copyHeight, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to allocate PixelCopy bitmap", t);
            pending.finish(null, null);
            return;
        }

        final Bitmap copyBmp = destBitmap;
        Rect srcRect = new Rect(bounds.srcLeft, bounds.srcTop, bounds.srcRight, bounds.srcBottom);
        boolean dispatched = PixelCopyReflector.request(
            window,
            srcRect,
            copyBmp,
            new IntResultListener() {
                @Override
                public void onResult(int copyResult) {
                    if (mShutdown || copyResult != PIXEL_COPY_SUCCESS || copyBmp.isRecycled()) {
                        recycleQuietly(copyBmp);
                        pending.finish(null, null);
                        return;
                    }
                    pending.finish(copyBmp, bounds);
                }
            },
            mMainHandler
        );
        if (!dispatched) {
            recycleQuietly(copyBmp);
            pending.finish(null, null);
        }
    }

    private CopyBounds measureWindowCopyBounds(WebView wv, View decorView, int viewW, int viewH, int decorW, int decorH) {
        int[] wvLoc = new int[2];
        int[] decorLoc = new int[2];
        wv.getLocationInWindow(wvLoc);
        decorView.getLocationInWindow(decorLoc);
        return computeWindowCopyBounds(
            wvLoc[0] - decorLoc[0],
            wvLoc[1] - decorLoc[1],
            viewW,
            viewH,
            decorW,
            decorH
        );
    }

    private boolean isActivityInvalid() {
        if (mActivity == null || mActivity.isFinishing()) return true;
        return Build.VERSION.SDK_INT >= 17 && mActivity.isDestroyed();
    }

    private static void recycleQuietly(Bitmap bmp) {
        if (bmp != null && !bmp.isRecycled()) {
            try {
                bmp.recycle();
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Releases any active overlay masks and cancels pending frame captures on teardown.
     */
    public void shutdown() {
        mShutdown = true;
        List<PendingCapture> copy;
        synchronized (mPendingCaptures) {
            copy = new ArrayList<PendingCapture>(mPendingCaptures);
        }
        for (PendingCapture p : copy) {
            p.finish(null, null);
        }
        mSuppressionGuard.releaseAll();
    }
}
