package com.acid.browser;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Base64;
import android.util.Log;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-responsibility screenshot capture, coordinate alignment, and off-DOM SoM overlay renderer.
 *
 * <p>Keeps coordinate transforms and per-tab frame metadata strictly separated from WebView
 * lifecycle management and pure-Java unit-testable on the host JVM.
 */
public class ScreenshotCaptureHelper {

    private static final String TAG = "ScreenshotHelper";

    public static final int MAX_IMAGE_LONG_EDGE = 1536;
    public static final int DEFAULT_JPEG_QUALITY = 80;
    public static final String DEFAULT_MIME_TYPE = "image/jpeg";

    private static final int[] SOM_PALETTE = new int[] {
        0xFF00E676, 0xFF00E5FF, 0xFFFFB300, 0xFFFF4081, 0xFFB388FF
    };

    public static final class ImageDimensions {
        public final int width;
        public final int height;

        public ImageDimensions(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    public static final class ViewportMapping {
        public final int viewWidth;
        public final int viewHeight;
        public final int imageWidth;
        public final int imageHeight;
        public final double cssToImgScaleX;
        public final double cssToImgScaleY;
        public final double vvOffsetLeft;
        public final double vvOffsetTop;

        public ViewportMapping(
            int viewWidth,
            int viewHeight,
            int imageWidth,
            int imageHeight,
            double cssToImgScaleX,
            double cssToImgScaleY,
            double vvOffsetLeft,
            double vvOffsetTop
        ) {
            this.viewWidth = viewWidth;
            this.viewHeight = viewHeight;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.cssToImgScaleX = cssToImgScaleX;
            this.cssToImgScaleY = cssToImgScaleY;
            this.vvOffsetLeft = vvOffsetLeft;
            this.vvOffsetTop = vvOffsetTop;
        }
    }

    public static final class SomMarkRect {
        public final String ref;
        public final String label;
        public final String tag;
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;
        public final String summaryLine;

        public SomMarkRect(
            String ref,
            String label,
            String tag,
            float left,
            float top,
            float right,
            float bottom,
            String summaryLine
        ) {
            this.ref = ref;
            this.label = label;
            this.tag = tag;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.summaryLine = summaryLine;
        }
    }

    public static final class ScreenshotFrame {
        public final int tabId;
        public final int imageWidth;
        public final int imageHeight;
        public final int viewWidth;
        public final int viewHeight;
        public final boolean desktopMode;
        public final double cssViewportWidth;
        public final double cssViewportHeight;
        public final long capturedAtMs;

        public ScreenshotFrame(
            int tabId,
            int imageWidth,
            int imageHeight,
            int viewWidth,
            int viewHeight,
            boolean desktopMode,
            double cssViewportWidth,
            double cssViewportHeight,
            long capturedAtMs
        ) {
            this.tabId = tabId;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.viewWidth = viewWidth;
            this.viewHeight = viewHeight;
            this.desktopMode = desktopMode;
            this.cssViewportWidth = cssViewportWidth;
            this.cssViewportHeight = cssViewportHeight;
            this.capturedAtMs = capturedAtMs;
        }
    }

    public static final class ResolvedPoint {
        public final boolean ok;
        public final float viewX;
        public final float viewY;
        public final String coordSpaceUsed;
        public final String errorCode;
        public final String errorMessage;

        private ResolvedPoint(
            boolean ok,
            float viewX,
            float viewY,
            String coordSpaceUsed,
            String errorCode,
            String errorMessage
        ) {
            this.ok = ok;
            this.viewX = viewX;
            this.viewY = viewY;
            this.coordSpaceUsed = coordSpaceUsed;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        public static ResolvedPoint success(float viewX, float viewY, String coordSpaceUsed) {
            return new ResolvedPoint(true, viewX, viewY, coordSpaceUsed, null, null);
        }

        public static ResolvedPoint error(String errorCode, String errorMessage) {
            return new ResolvedPoint(false, 0f, 0f, null, errorCode, errorMessage);
        }
    }

    public enum SaveFormat {
        PNG,
        JPEG
    }

    public static final class SaveTargetResolution {
        public final boolean ok;
        public final File file;
        public final String relativePath;
        public final SaveFormat format;
        public final String errorMessage;

        private SaveTargetResolution(
            boolean ok,
            File file,
            String relativePath,
            SaveFormat format,
            String errorMessage
        ) {
            this.ok = ok;
            this.file = file;
            this.relativePath = relativePath;
            this.format = format;
            this.errorMessage = errorMessage;
        }

        public static SaveTargetResolution success(File file, String relativePath, SaveFormat format) {
            return new SaveTargetResolution(true, file, relativePath, format, null);
        }

        public static SaveTargetResolution error(String message) {
            return new SaveTargetResolution(false, null, null, null, message);
        }
    }

    public static final class CaptureResult {
        public final boolean ok;
        public final String errorCode;
        public final String errorMessage;
        public final String base64;
        public final byte[] savedBytes;
        public final String mimeType;
        public final ScreenshotFrame frame;
        public final List<SomMarkRect> somMarks;
        public final boolean somDrawn;
        public final boolean hardwareCaptured;
        public final String url;
        public final String title;

        private CaptureResult(
            boolean ok,
            String errorCode,
            String errorMessage,
            String base64,
            byte[] savedBytes,
            String mimeType,
            ScreenshotFrame frame,
            List<SomMarkRect> somMarks,
            boolean somDrawn,
            boolean hardwareCaptured,
            String url,
            String title
        ) {
            this.ok = ok;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.base64 = base64;
            this.savedBytes = savedBytes;
            this.mimeType = mimeType;
            this.frame = frame;
            this.somMarks = somMarks != null ? somMarks : Collections.<SomMarkRect>emptyList();
            this.somDrawn = somDrawn;
            this.hardwareCaptured = hardwareCaptured;
            this.url = url;
            this.title = title;
        }

        public static CaptureResult success(
            String base64,
            byte[] savedBytes,
            ScreenshotFrame frame,
            List<SomMarkRect> somMarks,
            boolean somDrawn,
            String url,
            String title
        ) {
            return success(base64, savedBytes, frame, somMarks, somDrawn, false, url, title);
        }

        public static CaptureResult success(
            String base64,
            byte[] savedBytes,
            ScreenshotFrame frame,
            List<SomMarkRect> somMarks,
            boolean somDrawn,
            boolean hardwareCaptured,
            String url,
            String title
        ) {
            return new CaptureResult(
                true, null, null, base64, savedBytes, DEFAULT_MIME_TYPE,
                frame, somMarks, somDrawn, hardwareCaptured, url, title
            );
        }

        public static CaptureResult error(String code, String msg) {
            return new CaptureResult(false, code, msg, null, null, null, null, null, false, false, null, null);
        }
    }

    private final Object mLock = new Object();
    private final Map<Integer, ScreenshotFrame> mFramesByTab = new HashMap<Integer, ScreenshotFrame>();

    public void putFrame(ScreenshotFrame frame) {
        if (frame == null || frame.tabId <= 0) return;
        synchronized (mLock) {
            mFramesByTab.put(frame.tabId, frame);
        }
    }

    public ScreenshotFrame getFrame(int tabId) {
        if (tabId <= 0) return null;
        synchronized (mLock) {
            return mFramesByTab.get(tabId);
        }
    }

    public void clearTab(int tabId) {
        if (tabId <= 0) return;
        synchronized (mLock) {
            mFramesByTab.remove(tabId);
        }
    }

    public void clearAll() {
        synchronized (mLock) {
            mFramesByTab.clear();
        }
    }

    /** Computes aspect-preserving scaled image dimensions bounded by {@code maxLongEdge}. */
    public static ImageDimensions computeOutputDimensions(int viewWidth, int viewHeight, int maxLongEdge) {
        if (viewWidth <= 0 || viewHeight <= 0) {
            return new ImageDimensions(Math.max(1, viewWidth), Math.max(1, viewHeight));
        }
        int limit = maxLongEdge > 0 ? maxLongEdge : MAX_IMAGE_LONG_EDGE;
        int longest = Math.max(viewWidth, viewHeight);
        if (longest <= limit) {
            return new ImageDimensions(viewWidth, viewHeight);
        }
        double ratio = limit / (double) longest;
        int outW = Math.max(1, (int) Math.round(viewWidth * ratio));
        int outH = Math.max(1, (int) Math.round(viewHeight * ratio));
        return new ImageDimensions(outW, outH);
    }

    /**
     * Builds the exact affine projection from page CSS viewport boxes to screenshot image pixels,
     * covering mobile viewports, desktop 1280px overview scaling, and pinch-zoom offsets.
     */
    public static ViewportMapping computeViewportMapping(
        int viewWidth,
        int viewHeight,
        int imageWidth,
        int imageHeight,
        double vvWidth,
        double vvHeight,
        double vvOffsetLeft,
        double vvOffsetTop,
        float fallbackWebViewScale
    ) {
        double safeViewW = Math.max(1, viewWidth);
        double safeViewH = Math.max(1, viewHeight);
        double safeImgW = Math.max(1, imageWidth);
        double safeImgH = Math.max(1, imageHeight);

        double viewToImgX = safeImgW / safeViewW;
        double viewToImgY = safeImgH / safeViewH;

        double cssToViewX;
        double cssToViewY;
        if (vvWidth > 1.0 && vvHeight > 1.0) {
            cssToViewX = safeViewW / vvWidth;
            cssToViewY = safeViewH / vvHeight;
        } else {
            double s = fallbackWebViewScale > 0.01f ? fallbackWebViewScale : 1.0;
            cssToViewX = s;
            cssToViewY = s;
        }

        return new ViewportMapping(
            viewWidth,
            viewHeight,
            imageWidth,
            imageHeight,
            cssToViewX * viewToImgX,
            cssToViewY * viewToImgY,
            vvOffsetLeft,
            vvOffsetTop
        );
    }

    /**
     * Projects JS {@code observe()} element boxes into clipped screenshot pixel boxes for off-DOM SoM drawing.
     */
    public static List<SomMarkRect> extractSomMarks(JSONObject obsJson, ViewportMapping map) {
        if (obsJson == null || map == null) return Collections.emptyList();
        JSONArray elements = obsJson.optJSONArray("elements");
        if (elements == null || elements.length() == 0) return Collections.emptyList();

        List<SomMarkRect> marks = new ArrayList<SomMarkRect>();
        for (int i = 0; i < elements.length(); i++) {
            JSONObject el = elements.optJSONObject(i);
            if (el == null) continue;
            JSONObject box = el.optJSONObject("box");
            if (box == null) continue;

            double cw = box.optDouble("width", 0);
            double ch = box.optDouble("height", 0);
            if (cw <= 0 || ch <= 0) continue;

            double cx = box.optDouble("x", 0);
            double cy = box.optDouble("y", 0);

            float rawLeft = (float) ((cx - map.vvOffsetLeft) * map.cssToImgScaleX);
            float rawTop = (float) ((cy - map.vvOffsetTop) * map.cssToImgScaleY);
            float rawRight = (float) ((cx + cw - map.vvOffsetLeft) * map.cssToImgScaleX);
            float rawBottom = (float) ((cy + ch - map.vvOffsetTop) * map.cssToImgScaleY);

            // Reject boxes completely outside the captured screenshot viewport
            if (rawRight <= 0f || rawBottom <= 0f || rawLeft >= map.imageWidth || rawTop >= map.imageHeight) {
                continue;
            }

            float clLeft = Math.max(0f, rawLeft);
            float clTop = Math.max(0f, rawTop);
            float clRight = Math.min(map.imageWidth, rawRight);
            float clBottom = Math.min(map.imageHeight, rawBottom);
            if ((clRight - clLeft) < 2f || (clBottom - clTop) < 2f) {
                continue;
            }

            String ref = el.optString("ref", "");
            if (ref.isEmpty()) continue;
            String tag = el.optString("tag", "");
            String type = el.optString("type", "");
            String label = el.optString("label", "");

            StringBuilder summary = new StringBuilder();
            summary.append("[").append(ref).append("] ").append(tag);
            if (!type.isEmpty()) summary.append("(").append(type).append(")");
            if (!label.isEmpty()) summary.append(" \"").append(label).append("\"");
            summary.append(" @(")
                   .append(Math.round((clLeft + clRight) * 0.5f))
                   .append(",")
                   .append(Math.round((clTop + clBottom) * 0.5f))
                   .append(")");

            marks.add(new SomMarkRect(ref, label, tag, clLeft, clTop, clRight, clBottom, summary.toString()));
        }
        return marks;
    }

    /**
     * Converts agent-supplied {@code (x, y)} coordinates back into exact physical Android WebView
     * pixel coordinates according to {@code coordSpace} ({@code auto}, {@code screenshot},
     * {@code normalized}, or {@code view}).
     */
    public static ResolvedPoint resolveClickCoordinates(
        ScreenshotFrame frame,
        double rawX,
        double rawY,
        String coordSpace,
        int curViewWidth,
        int curViewHeight,
        boolean curDesktopMode
    ) {
        if (Double.isNaN(rawX) || Double.isInfinite(rawX) || Double.isNaN(rawY) || Double.isInfinite(rawY)) {
            return ResolvedPoint.error("INVALID_COORDINATE", "Coordinates must be finite numbers.");
        }
        String space = coordSpace != null ? coordSpace.trim().toLowerCase(Locale.US) : "auto";
        if (space.isEmpty()) space = "auto";

        if ("normalized".equals(space)) {
            if (curViewWidth <= 0 || curViewHeight <= 0) {
                return ResolvedPoint.error("NO_VIEW_DIMENSIONS", "WebView dimensions unavailable for normalized coordinates.");
            }
            float vx = (float) ((rawX / 1000.0) * curViewWidth);
            float vy = (float) ((rawY / 1000.0) * curViewHeight);
            return ResolvedPoint.success(clamp(vx, 0f, Math.max(0f, curViewWidth - 1f)),
                clamp(vy, 0f, Math.max(0f, curViewHeight - 1f)), "normalized");
        }

        if ("view".equals(space)) {
            return ResolvedPoint.success((float) rawX, (float) rawY, "view");
        }

        boolean frameMatchesTab = frame != null
            && frame.imageWidth > 0 && frame.imageHeight > 0
            && frame.viewWidth > 0 && frame.viewHeight > 0
            && frame.desktopMode == curDesktopMode
            && (curViewWidth <= 0 || frame.viewWidth == curViewWidth)
            && (curViewHeight <= 0 || frame.viewHeight == curViewHeight);

        if ("screenshot".equals(space)) {
            if (frame == null) {
                return ResolvedPoint.error(
                    "MISSING_SCREENSHOT",
                    "coord_space='screenshot' requires calling browser(action='screenshot') on this tab first."
                );
            }
            if (!frameMatchesTab) {
                return ResolvedPoint.error(
                    "STALE_SCREENSHOT",
                    "WebView mode or dimensions changed since last screenshot; please capture a fresh screenshot first."
                );
            }
            float vx = (float) (rawX * (frame.viewWidth / (double) frame.imageWidth));
            float vy = (float) (rawY * (frame.viewHeight / (double) frame.imageHeight));
            return ResolvedPoint.success(clamp(vx, 0f, Math.max(0f, frame.viewWidth - 1f)),
                clamp(vy, 0f, Math.max(0f, frame.viewHeight - 1f)), "screenshot");
        }

        if ("auto".equals(space)) {
            if (frameMatchesTab) {
                float vx = (float) (rawX * (frame.viewWidth / (double) frame.imageWidth));
                float vy = (float) (rawY * (frame.viewHeight / (double) frame.imageHeight));
                return ResolvedPoint.success(clamp(vx, 0f, Math.max(0f, frame.viewWidth - 1f)),
                    clamp(vy, 0f, Math.max(0f, frame.viewHeight - 1f)), "screenshot");
            }
            return ResolvedPoint.success((float) rawX, (float) rawY, "view");
        }

        return ResolvedPoint.error(
            "INVALID_COORD_SPACE",
            "Unknown coord_space '" + coordSpace + "'. Use 'auto', 'screenshot', 'normalized', or 'view'."
        );
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    /**
     * Validates {@code save_path} against workspace boundaries and cleanup protection rules,
     * rejecting directory targets and non-image extensions so disk format always matches filename.
     */
    public static SaveTargetResolution resolveSaveTarget(File workspaceDir, String rawSavePath) {
        AgentUploadManager.WorkspaceFileResolution check =
            AgentUploadManager.resolveSafeUnprotectedWorkspaceFile(workspaceDir, rawSavePath, "overwritten");
        if (!check.ok) {
            return SaveTargetResolution.error(check.errorMessage);
        }
        if (check.file.exists() && check.file.isDirectory()) {
            return SaveTargetResolution.error("save_path points to an existing directory: " + rawSavePath);
        }
        String lowerName = check.file.getName().toLowerCase(Locale.US);
        final SaveFormat fmt;
        if (lowerName.endsWith(".png")) {
            fmt = SaveFormat.PNG;
        } else if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) {
            fmt = SaveFormat.JPEG;
        } else {
            return SaveTargetResolution.error(
                "save_path must end with .png, .jpg, or .jpeg (" + rawSavePath + ")"
            );
        }
        return SaveTargetResolution.success(check.file, check.relativePath, fmt);
    }

    /**
     * Captures a software-drawn bitmap of {@code wv}'s current viewport on the UI thread.
     * Used for background tabs, pre-API 26 devices, and uncovered edges of partially clipped views.
     */
    public static Bitmap captureSoftwareBitmapOnUiThread(WebView wv, int viewW, int viewH) {
        Bitmap rawBmp = Bitmap.createBitmap(viewW, viewH, Bitmap.Config.ARGB_8888);
        Canvas rawCanvas = new Canvas(rawBmp);
        rawCanvas.drawColor(Color.WHITE);
        int sx = wv.getScrollX();
        int sy = wv.getScrollY();
        if (sx != 0 || sy != 0) {
            rawCanvas.translate(-sx, -sy);
        }
        wv.draw(rawCanvas);
        return rawBmp;
    }

    private static void recycleQuietly(Bitmap bmp) {
        if (bmp != null && !bmp.isRecycled()) {
            try {
                bmp.recycle();
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Must run on the Android UI thread. Captures the visible WebView viewport into an appropriately
     * scaled image using software rendering, optionally draws SoM element markings onto the bitmap
     * copy, and encodes PNG bytes if {@code saveAsPng} is requested.
     */
    public CaptureResult captureOnUiThread(
        WebView wv,
        int tabId,
        boolean desktopMode,
        JSONObject obsJson,
        boolean drawSom,
        int jpegQuality
    ) {
        return captureOnUiThread(wv, tabId, desktopMode, obsJson, drawSom, jpegQuality, false);
    }

    public CaptureResult captureOnUiThread(
        WebView wv,
        int tabId,
        boolean desktopMode,
        JSONObject obsJson,
        boolean drawSom,
        int jpegQuality,
        boolean saveAsPng
    ) {
        return captureFromHardwareOrSoftwareOnUiThread(
            wv, tabId, desktopMode, obsJson, drawSom, jpegQuality, saveAsPng, null, null
        );
    }

    /**
     * Must run on the Android UI thread. Consumes an optional hardware {@code PixelCopy} bitmap
     * (either 1:1 full viewport or composited at its exact unstretched offset over a software
     * backing bitmap when partially clipped), otherwise falls back to software drawing, then
     * performs scaling, off-DOM SoM drawing, and JPEG/PNG encoding.
     */
    public CaptureResult captureFromHardwareOrSoftwareOnUiThread(
        WebView wv,
        int tabId,
        boolean desktopMode,
        JSONObject obsJson,
        boolean drawSom,
        int jpegQuality,
        boolean saveAsPng,
        Bitmap hwBitmap,
        WindowPixelCopyCapturer.CopyBounds hwBounds
    ) {
        if (wv == null) {
            recycleQuietly(hwBitmap);
            return CaptureResult.error("NO_TARGET_TAB", "Target WebView is unavailable.");
        }
        int viewW = wv.getWidth();
        int viewH = wv.getHeight();
        if (viewW <= 0 || viewH <= 0) {
            recycleQuietly(hwBitmap);
            return CaptureResult.error(
                "VIEW_NOT_LAID_OUT",
                "Target WebView has zero dimensions (" + viewW + "x" + viewH + "); switch the tab to foreground before taking a screenshot."
            );
        }

        ImageDimensions imgDims = computeOutputDimensions(viewW, viewH, MAX_IMAGE_LONG_EDGE);
        Bitmap ownedHwBmp = hwBitmap;
        Bitmap rawBmp = null;
        Bitmap scaledBmp = null;
        try {
            final boolean usedHardware;
            if (ownedHwBmp != null && !ownedHwBmp.isRecycled() && hwBounds != null && hwBounds.valid) {
                if (hwBounds.exactFullMatch && ownedHwBmp.getWidth() == viewW && ownedHwBmp.getHeight() == viewH) {
                    rawBmp = ownedHwBmp;
                    ownedHwBmp = null;
                    usedHardware = true;
                } else {
                    rawBmp = captureSoftwareBitmapOnUiThread(wv, viewW, viewH);
                    Canvas compCanvas = new Canvas(rawBmp);
                    compCanvas.drawBitmap(ownedHwBmp, (float) hwBounds.destOffsetX, (float) hwBounds.destOffsetY, null);
                    recycleQuietly(ownedHwBmp);
                    ownedHwBmp = null;
                    usedHardware = true;
                }
            } else {
                recycleQuietly(ownedHwBmp);
                ownedHwBmp = null;
                rawBmp = captureSoftwareBitmapOnUiThread(wv, viewW, viewH);
                usedHardware = false;
            }

            final Bitmap outBmp;
            if (imgDims.width == viewW && imgDims.height == viewH) {
                outBmp = rawBmp;
            } else {
                scaledBmp = Bitmap.createBitmap(imgDims.width, imgDims.height, Bitmap.Config.ARGB_8888);
                Canvas scaleCanvas = new Canvas(scaledBmp);
                Paint blitPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
                scaleCanvas.drawBitmap(
                    rawBmp,
                    null,
                    new RectF(0f, 0f, imgDims.width, imgDims.height),
                    blitPaint
                );
                rawBmp.recycle();
                rawBmp = null;
                outBmp = scaledBmp;
            }

            double vvW = 0;
            double vvH = 0;
            double vvOffL = 0;
            double vvOffT = 0;
            if (obsJson != null && obsJson.optJSONObject("viewport") != null) {
                JSONObject vp = obsJson.optJSONObject("viewport");
                vvW = vp.optDouble("vvWidth", vp.optDouble("width", 0));
                vvH = vp.optDouble("vvHeight", vp.optDouble("height", 0));
                vvOffL = vp.optDouble("vvOffsetLeft", 0);
                vvOffT = vp.optDouble("vvOffsetTop", 0);
            }

            ViewportMapping mapping = computeViewportMapping(
                viewW,
                viewH,
                imgDims.width,
                imgDims.height,
                vvW,
                vvH,
                vvOffL,
                vvOffT,
                wv.getScale()
            );

            List<SomMarkRect> somMarks = drawSom ? extractSomMarks(obsJson, mapping) : Collections.<SomMarkRect>emptyList();
            if (drawSom && !somMarks.isEmpty()) {
                drawSomBoxesOnBitmap(outBmp, somMarks);
            }

            int quality = (jpegQuality >= 30 && jpegQuality <= 100) ? jpegQuality : DEFAULT_JPEG_QUALITY;
            ByteArrayOutputStream jpegOut = new ByteArrayOutputStream();
            outBmp.compress(Bitmap.CompressFormat.JPEG, quality, jpegOut);
            byte[] jpegBytes = jpegOut.toByteArray();
            String b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP);

            final byte[] savedBytes;
            if (saveAsPng) {
                ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
                outBmp.compress(Bitmap.CompressFormat.PNG, 100, pngOut);
                savedBytes = pngOut.toByteArray();
            } else {
                savedBytes = jpegBytes;
            }

            ScreenshotFrame frame = new ScreenshotFrame(
                tabId,
                imgDims.width,
                imgDims.height,
                viewW,
                viewH,
                desktopMode,
                vvW,
                vvH,
                System.currentTimeMillis()
            );
            putFrame(frame);

            String url = obsJson != null ? obsJson.optString("url", wv.getUrl()) : wv.getUrl();
            String title = obsJson != null ? obsJson.optString("title", wv.getTitle()) : wv.getTitle();
            return CaptureResult.success(
                b64, savedBytes, frame, somMarks, drawSom && !somMarks.isEmpty(), usedHardware, url, title
            );
        } catch (Throwable t) {
            Log.e(TAG, "Screenshot capture failed", t);
            return CaptureResult.error("SCREENSHOT_FAILED", t.getMessage() != null ? t.getMessage() : "Bitmap capture failed");
        } finally {
            recycleQuietly(ownedHwBmp);
            recycleQuietly(rawBmp);
            recycleQuietly(scaledBmp);
        }
    }

    private static void drawSomBoxesOnBitmap(Bitmap bmp, List<SomMarkRect> marks) {
        if (bmp == null || marks == null || marks.isEmpty()) return;
        Canvas canvas = new Canvas(bmp);
        int imgW = bmp.getWidth();
        int imgH = bmp.getHeight();
        float unit = Math.max(1.0f, Math.min(imgW, imgH) / 420f);

        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Math.max(1.5f, 1.6f * unit));

        Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tintPaint.setStyle(Paint.Style.FILL);

        Paint badgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        badgeBgPaint.setStyle(Paint.Style.FILL);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setFakeBoldText(true);
        textPaint.setColor(0xFF0A0E14);
        textPaint.setTextSize(Math.max(12f, 11.5f * unit));

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textHeight = fm.descent - fm.ascent;
        float padH = Math.max(3f, 3.5f * unit);
        float padV = Math.max(1.5f, 1.8f * unit);
        float badgeH = textHeight + padV * 2f;
        float radius = Math.max(2f, 2.5f * unit);

        for (int i = 0; i < marks.size(); i++) {
            SomMarkRect m = marks.get(i);
            int baseColor = SOM_PALETTE[i % SOM_PALETTE.length];

            strokePaint.setColor(baseColor);
            tintPaint.setColor((baseColor & 0x00FFFFFF) | 0x18000000);
            RectF boxRect = new RectF(m.left, m.top, m.right, m.bottom);
            canvas.drawRoundRect(boxRect, radius, radius, tintPaint);
            canvas.drawRoundRect(boxRect, radius, radius, strokePaint);

            float textW = textPaint.measureText(m.ref);
            float badgeW = textW + padH * 2f;
            float bx = clamp(m.left, 0f, Math.max(0f, imgW - badgeW));
            float preferredTop = m.top - badgeH;
            float by = (preferredTop >= 0f)
                ? preferredTop
                : clamp(m.top, 0f, Math.max(0f, imgH - badgeH));

            badgeBgPaint.setColor(baseColor);
            RectF badgeRect = new RectF(bx, by, bx + badgeW, by + badgeH);
            canvas.drawRoundRect(badgeRect, radius, radius, badgeBgPaint);
            canvas.drawText(m.ref, bx + padH, by + padV - fm.ascent, textPaint);
        }
    }
}
