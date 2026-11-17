package com.acid.browser;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

/**
 * Full-chrome continuous breathing border overlay ("一笔画") shown while the AI Agent
 * locks the active foreground tab.
 *
 * <p>Measures live descendant view bounds on every layout/draw pass so phones, tablets,
 * foldables, split-screen windows, and display-cutout fullscreen layouts all align exactly,
 * carving out only the unlocked top-right AI button and bottom Tabs switcher button.
 * Never intercepts touch events; all click blocking lives on the controls themselves.
 */
public class AgentLockChromeOverlay extends View {

    /** Pure-Java immutable rectangle usable in host-JVM unit tests without Android stubs. */
    public static final class Bounds {
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;

        public Bounds(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public float width() {
            return right - left;
        }

        public float height() {
            return bottom - top;
        }

        public boolean isValid() {
            return right > left && bottom > top;
        }
    }

    /** Pure-Java polygon result containing flattened clockwise vertices and clamped corner radius. */
    public static final class OutlineGeometry {
        public final float[] points;
        public final float cornerRadius;

        public OutlineGeometry(float[] points, float cornerRadius) {
            this.points = points;
            this.cornerRadius = cornerRadius;
        }

        public int vertexCount() {
            return points != null ? points.length / 2 : 0;
        }
    }

    /**
     * Single source of truth for whether Chrome controls on the currently visible tab
     * must be locked against human interference during AI automation.
     */
    public static boolean isChromeLocked(
        int controlMode,
        boolean agentTargetLost,
        int agentBoundTabId,
        int activeTabId
    ) {
        if (controlMode != AgentOverlayView.MODE_AGENT || agentTargetLost) {
            return false;
        }
        if (agentBoundTabId <= 0) {
            return true;
        }
        return activeTabId == agentBoundTabId;
    }

    /**
     * Builds a clockwise orthogonal one-stroke outline around locked Chrome regions using
     * live measured view bounds so resolution/DPI/tablet/split-screen changes stay aligned.
     */
    public static OutlineGeometry computeOutlineGeometry(
        Bounds container,
        Bounds topBar,
        Bounds topSearchEnd,
        Bounds topAgentBtn,
        Bounds takeoverBar,
        Bounds centerFrame,
        Bounds bottomBar,
        Bounds tabsBtn,
        float strokeWidthPx,
        float desiredCornerPx,
        float minStepPx
    ) {
        if (container == null || !container.isValid() || centerFrame == null || !centerFrame.isValid()) {
            return null;
        }
        float halfStroke = Math.max(0.5f, strokeWidthPx * 0.5f);
        float left = container.left + halfStroke;
        float right = container.right - halfStroke;
        float topAnchor = (topBar != null && topBar.isValid()) ? topBar.top : centerFrame.top;
        float top = topAnchor + halfStroke;

        boolean hasBottomCutout = bottomBar != null && bottomBar.isValid()
            && tabsBtn != null && tabsBtn.isValid()
            && bottomBar.bottom > centerFrame.bottom + minStepPx;
        float bottomAnchor = hasBottomCutout ? bottomBar.bottom : centerFrame.bottom;
        float bottom = bottomAnchor - halfStroke;

        if (right - left <= minStepPx * 2f || bottom - top <= minStepPx * 2f) {
            return null;
        }

        boolean hasTopNotch = false;
        float topCutX = right;
        float topStepY = top;
        if (topBar != null && topBar.isValid() && topAgentBtn != null && topAgentBtn.isValid()) {
            float searchRight = (topSearchEnd != null && topSearchEnd.isValid())
                ? topSearchEnd.right : topAgentBtn.left;
            float candidateX = (searchRight + topAgentBtn.left) * 0.5f;
            float candidateY = (takeoverBar != null && takeoverBar.isValid() && takeoverBar.top >= topBar.bottom)
                ? (topBar.bottom + takeoverBar.top) * 0.5f
                : Math.min(centerFrame.top, topBar.bottom + halfStroke * 0.5f);
            candidateY = Math.min(Math.max(top + minStepPx, candidateY), bottom - minStepPx * 2f);
            if (candidateX >= left + minStepPx && candidateX <= right - minStepPx
                    && candidateY - top >= minStepPx) {
                hasTopNotch = true;
                topCutX = candidateX;
                topStepY = candidateY;
            }
        }

        boolean applyBottomNotch = false;
        float tabsLeft = left;
        float tabsRight = right;
        float tabsNotchY = bottom;
        if (hasBottomCutout) {
            float candidateLeft = tabsBtn.left;
            float candidateRight = tabsBtn.right;
            float minTopY = hasTopNotch ? topStepY + minStepPx : top + minStepPx;
            float candidateNotchY = Math.max(minTopY, bottomBar.top - halfStroke * 0.5f);
            if (candidateLeft >= left + minStepPx
                    && candidateRight <= right - minStepPx
                    && candidateRight - candidateLeft >= minStepPx
                    && bottom - candidateNotchY >= minStepPx) {
                applyBottomNotch = true;
                tabsLeft = candidateLeft;
                tabsRight = candidateRight;
                tabsNotchY = candidateNotchY;
            }
        }

        float[] raw;
        if (hasTopNotch && applyBottomNotch) {
            raw = new float[] {
                left, top,
                topCutX, top,
                topCutX, topStepY,
                right, topStepY,
                right, bottom,
                tabsRight, bottom,
                tabsRight, tabsNotchY,
                tabsLeft, tabsNotchY,
                tabsLeft, bottom,
                left, bottom
            };
        } else if (hasTopNotch) {
            raw = new float[] {
                left, top,
                topCutX, top,
                topCutX, topStepY,
                right, topStepY,
                right, bottom,
                left, bottom
            };
        } else if (applyBottomNotch) {
            raw = new float[] {
                left, top,
                right, top,
                right, bottom,
                tabsRight, bottom,
                tabsRight, tabsNotchY,
                tabsLeft, tabsNotchY,
                tabsLeft, bottom,
                left, bottom
            };
        } else {
            raw = new float[] {
                left, top,
                right, top,
                right, bottom,
                left, bottom
            };
        }

        float minSeg = findMinimumSegmentLength(raw);
        float clampedRadius = Math.max(1f, Math.min(desiredCornerPx, minSeg * 0.45f));
        return new OutlineGeometry(raw, clampedRadius);
    }

    private static float findMinimumSegmentLength(float[] pts) {
        int count = pts.length / 2;
        float minLen = Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            float dx = Math.abs(pts[j * 2] - pts[i * 2]);
            float dy = Math.abs(pts[j * 2 + 1] - pts[i * 2 + 1]);
            float len = Math.max(dx, dy);
            if (len < minLen) {
                minLen = len;
            }
        }
        return minLen == Float.MAX_VALUE ? 0f : minLen;
    }

    public static void showLockedHint(Context context) {
        AcidFeedback.info(
            context,
            "AI 正在操控当前页面，请点下方「标签页」新建或切换标签，或点击上方「点此接管」"
        );
    }

    private static final class ChromeBgStyleTag {
        final boolean locked;
        final boolean dark;
        final float radius;

        ChromeBgStyleTag(boolean locked, boolean dark, float radius) {
            this.locked = locked;
            this.dark = dark;
            this.radius = radius;
        }

        boolean matches(boolean locked, boolean dark, float radius) {
            return this.locked == locked
                && this.dark == dark
                && Math.abs(this.radius - radius) < 0.01f;
        }
    }

    public static void applyIconButtonLockStyle(
        View container,
        AcidIcons.SvgIconView icon,
        boolean locked,
        boolean unlockedEnabled,
        boolean dark,
        int unlockedColor,
        float bgRadius
    ) {
        if (container == null) return;
        Object rawTag = container.getTag();
        boolean styleUnchanged = (rawTag instanceof ChromeBgStyleTag)
            && ((ChromeBgStyleTag) rawTag).matches(locked, dark, bgRadius);
        if (!styleUnchanged) {
            if (locked) {
                container.setAlpha(0.38f);
                container.setBackgroundColor(Color.TRANSPARENT);
            } else {
                container.setAlpha(1.0f);
                container.setBackground(AcidTheme.interactiveBg(
                    container.getContext(), Color.TRANSPARENT, AcidTheme.rowPressed(dark), 0, bgRadius
                ));
            }
            container.setTag(new ChromeBgStyleTag(locked, dark, bgRadius));
        }
        if (icon != null) {
            icon.setColor(
                locked
                    ? AcidTheme.iconDisabled(dark)
                    : (unlockedEnabled ? unlockedColor : AcidTheme.iconDisabled(dark))
            );
        }
    }

    public static void applyUrlBarLockStyle(
        View searchContainer,
        AcidIcons.SvgIconView searchIcon,
        EditText urlInput,
        boolean locked,
        boolean dark,
        boolean incognito
    ) {
        if (searchContainer != null) {
            float targetAlpha = locked ? 0.42f : 1.0f;
            if (Math.abs(searchContainer.getAlpha() - targetAlpha) > 0.01f) {
                searchContainer.setAlpha(targetAlpha);
            }
            if (searchContainer.isClickable() != locked) {
                searchContainer.setClickable(locked);
            }
        }
        if (searchIcon != null) {
            searchIcon.setColor(
                locked
                    ? AcidTheme.iconDisabled(dark)
                    : (incognito ? AcidTheme.COLOR_PURPLE : AcidTheme.muted(dark))
            );
        }
        if (urlInput != null) {
            boolean wantEditable = !locked;
            if (urlInput.isFocusable() != wantEditable) {
                urlInput.setFocusable(wantEditable);
            }
            if (urlInput.isFocusableInTouchMode() != wantEditable) {
                urlInput.setFocusableInTouchMode(wantEditable);
            }
            if (urlInput.isLongClickable() != wantEditable) {
                urlInput.setLongClickable(wantEditable);
            }
            if (urlInput.isCursorVisible() != wantEditable) {
                urlInput.setCursorVisible(wantEditable);
            }
        }
    }

    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final Rect mScratchRect = new Rect();

    private ViewGroup mCoordRoot;
    private View mContainerView;
    private View mTopBarView;
    private View mSearchEndView;
    private View mTopAgentBtnView;
    private View mTakeoverBarView;
    private View mCenterFrameView;
    private View mBottomBarView;
    private View mTabsBtnView;

    private boolean mActive = false;
    private boolean mGeometryDirty = true;
    private boolean mHasValidPath = false;
    private float mLastAppliedCornerRadius = -1f;

    public AgentLockChromeOverlay(Context context) {
        super(context);
        setWillNotDraw(false);
        setVisibility(GONE);
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeCap(Paint.Cap.ROUND);
        mBorderPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void attachTrackedViews(
        ViewGroup coordRoot,
        View containerView,
        View topBarView,
        View searchEndView,
        View topAgentBtnView,
        View takeoverBarView,
        View centerFrameView,
        View bottomBarView,
        View tabsBtnView
    ) {
        mCoordRoot = coordRoot;
        mContainerView = containerView;
        mTopBarView = topBarView;
        mSearchEndView = searchEndView;
        mTopAgentBtnView = topAgentBtnView;
        mTakeoverBarView = takeoverBarView;
        mCenterFrameView = centerFrameView;
        mBottomBarView = bottomBarView;
        mTabsBtnView = tabsBtnView;
    }

    public void setLockBorderVisible(boolean visible) {
        if (visible) {
            mGeometryDirty = true;
        }
        if (mActive == visible) return;
        mActive = visible;
        setVisibility(visible ? VISIBLE : GONE);
        if (visible) {
            postInvalidateOnAnimation();
        }
    }

    public void invalidateOutlineGeometry() {
        mGeometryDirty = true;
        if (mActive) {
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mGeometryDirty = true;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private Bounds measureBoundsInOverlay(View target) {
        if (target == null || mCoordRoot == null || target.getVisibility() != VISIBLE
                || target.getWidth() <= 0 || target.getHeight() <= 0) {
            return null;
        }
        try {
            mScratchRect.set(0, 0, target.getWidth(), target.getHeight());
            mCoordRoot.offsetDescendantRectToMyCoords(target, mScratchRect);
            return new Bounds(mScratchRect.left, mScratchRect.top, mScratchRect.right, mScratchRect.bottom);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void rebuildCachedPath(float strokeW) {
        mGeometryDirty = false;
        OutlineGeometry geom = computeOutlineGeometry(
            measureBoundsInOverlay(mContainerView),
            measureBoundsInOverlay(mTopBarView),
            measureBoundsInOverlay(mSearchEndView),
            measureBoundsInOverlay(mTopAgentBtnView),
            measureBoundsInOverlay(mTakeoverBarView),
            measureBoundsInOverlay(mCenterFrameView),
            measureBoundsInOverlay(mBottomBarView),
            measureBoundsInOverlay(mTabsBtnView),
            strokeW,
            dp(9.0f),
            dp(6.0f)
        );
        if (geom == null || geom.vertexCount() < 4) {
            mHasValidPath = false;
            return;
        }

        mPath.reset();
        mPath.moveTo(geom.points[0], geom.points[1]);
        for (int i = 1; i < geom.vertexCount(); i++) {
            mPath.lineTo(geom.points[i * 2], geom.points[i * 2 + 1]);
        }
        mPath.close();

        if (Math.abs(geom.cornerRadius - mLastAppliedCornerRadius) > 0.5f) {
            mLastAppliedCornerRadius = geom.cornerRadius;
            mBorderPaint.setPathEffect(new CornerPathEffect(geom.cornerRadius));
        }
        mHasValidPath = true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mActive) return;

        float strokeW = dp(3.0f);
        if (mGeometryDirty) {
            rebuildCachedPath(strokeW);
        }
        if (!mHasValidPath) return;

        long now = SystemClock.uptimeMillis();
        double phase = (now % 1800) / 1800.0 * Math.PI * 2.0;
        float alphaFactor = (float) (0.45 + 0.55 * (0.5 + 0.5 * Math.sin(phase)));
        int alpha = Math.min(255, Math.max(40, (int) (220 * alphaFactor)));
        int baseColor = AcidTheme.DARK_ACCENT;
        mBorderPaint.setColor(Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)));
        mBorderPaint.setStrokeWidth(strokeW);

        canvas.drawPath(mPath, mBorderPaint);
        postInvalidateOnAnimation();
    }
}
