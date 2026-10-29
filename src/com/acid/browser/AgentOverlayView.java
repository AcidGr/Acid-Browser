package com.acid.browser;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * HUD Overlay above the WebView:
 * 1. Renders breathing border when AI Agent holds control ("agent" mode) or requests takeover.
 * 2. Intercepts accidental human touches while AI is actively driving (with gentle toast tip).
 * 3. Renders animated touch pulse ripples + target [eN] badge whenever AI synthesizes a click.
 */
public class AgentOverlayView extends View {

    public static final int MODE_HUMAN = 0;
    public static final int MODE_AGENT = 1;
    public static final int MODE_TAKEOVER_REQUESTED = 2;

    public interface TabStateProvider {
        boolean isAgentDrivingCurrentTab();
        boolean isOverviewShowing();
    }

    private int mControlMode = MODE_HUMAN;
    private TabStateProvider mTabStateProvider = null;
    private boolean mDismissedTip = false;

    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabelTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBorderRect = new RectF();

    private final Paint mDimFilterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTipCardBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTipCardBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTipTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTipBodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTipFootPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mTipCardRect = new RectF();

    private long mLastToastTime = 0;

    private static class ClickPulse {
        float x;
        float y;
        String label;
        long startTime;
        static final long DURATION_MS = 750;
    }

    private final List<ClickPulse> mPulses = new ArrayList<ClickPulse>();

    public AgentOverlayView(Context context) {
        super(context);
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mPulsePaint.setStyle(Paint.Style.STROKE);

        mLabelBgPaint.setStyle(Paint.Style.FILL);
        mLabelBgPaint.setColor(AcidTheme.OVERLAY_LABEL_BG);

        mLabelTextPaint.setColor(AcidTheme.DARK_ACCENT);
        mLabelTextPaint.setTextSize(dp(11));
        mLabelTextPaint.setFakeBoldText(true);

        mDimFilterPaint.setColor(Color.argb(165, 15, 23, 42)); // ~65% dark slate dim filter
        mTipCardBgPaint.setColor(Color.argb(245, 24, 24, 27)); // rounded dark card
        mTipCardBorderPaint.setStyle(Paint.Style.STROKE);
        mTipCardBorderPaint.setColor(Color.argb(180, 16, 185, 129)); // emerald green border

        mTipTitlePaint.setColor(AcidTheme.DARK_ACCENT); // #10B981
        mTipTitlePaint.setTextSize(dp(15f));
        mTipTitlePaint.setFakeBoldText(true);
        mTipTitlePaint.setTextAlign(Paint.Align.CENTER);

        mTipBodyPaint.setColor(Color.parseColor("#F1F5F9")); // clean light text
        mTipBodyPaint.setTextSize(dp(12.5f));
        mTipBodyPaint.setTextAlign(Paint.Align.CENTER);

        mTipFootPaint.setColor(AcidTheme.COLOR_AMBER); // amber hint
        mTipFootPaint.setTextSize(dp(11f));
        mTipFootPaint.setTextAlign(Paint.Align.CENTER);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setControlMode(int mode) {
        if (mControlMode != mode) {
            mControlMode = mode;
            if (mode == MODE_AGENT) {
                mDismissedTip = false; // Reset to show tip on new agent engagement
            }
            postInvalidateOnAnimation();
        }
    }

    public void setTabStateProvider(TabStateProvider provider) {
        mTabStateProvider = provider;
        postInvalidateOnAnimation();
    }

    public int getControlMode() {
        return mControlMode;
    }

    public void showClickPulse(final float x, final float y, final String label) {
        post(new Runnable() {
            @Override
            public void run() {
                ClickPulse p = new ClickPulse();
                p.x = x;
                p.y = y;
                p.label = label != null ? label : "";
                p.startTime = SystemClock.uptimeMillis();
                mPulses.add(p);
                postInvalidateOnAnimation();
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mTabStateProvider != null && mTabStateProvider.isOverviewShowing()) {
            return false; // Tab overview is showing, pass all touches through!
        }

        // When AI is actively driving, block accidental human touches on the bound WebView only.
        // If the user has switched to a different tab, let the user interact with that tab freely!
        if (mControlMode == MODE_AGENT) {
            if (mTabStateProvider != null && !mTabStateProvider.isAgentDrivingCurrentTab()) {
                return false; // User is browsing a different tab; pass touch through to that active tab
            }

            // If the dim filter & prompt card are currently visible, single tap dismisses it!
            if (!mDismissedTip) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    mDismissedTip = true;
                    postInvalidateOnAnimation();
                }
                return true; // Consume this tap so it does not click into the web page
            }

            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                long now = SystemClock.uptimeMillis();
                if (now - mLastToastTime > 2200) {
                    mLastToastTime = now;
                    Toast.makeText(
                        getContext(),
                        "[AI LOCK] AI 正在操作此网页，如需手动操作请点击底部 [接管]",
                        Toast.LENGTH_SHORT
                    ).show();
                }
            }
            return true; // Consume touch event so WebView isn't disrupted mid-loop
        }
        return false; // Pass through to WebView in HUMAN or TAKEOVER_REQUESTED mode
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.uptimeMillis();
        boolean needsMoreFrames = false;

        int w = getWidth();
        int h = getHeight();

        boolean isCurrentTabDriven = (mTabStateProvider == null || mTabStateProvider.isAgentDrivingCurrentTab());
        boolean isOverviewShowing = (mTabStateProvider != null && mTabStateProvider.isOverviewShowing());

        if (isOverviewShowing) {
            return; // Never draw HUD overlay over tabs overview!
        }

        // 1. Draw dim filter & centered guidance prompt card when tip is not dismissed
        if (mControlMode == MODE_AGENT && isCurrentTabDriven && !mDismissedTip) {
            // A. Dark translucent filter
            canvas.drawRect(0, 0, w, h, mDimFilterPaint);

            // B. Centered prompt card
            float cardW = Math.min(w - dp(40), dp(320));
            float cardH = dp(160);
            float cardLeft = (w - cardW) / 2f;
            float cardTop = (h - cardH) / 2f;
            mTipCardRect.set(cardLeft, cardTop, cardLeft + cardW, cardTop + cardH);

            // Card background & rounded border
            canvas.drawRoundRect(mTipCardRect, dp(14), dp(14), mTipCardBgPaint);
            mTipCardBorderPaint.setStrokeWidth(dp(1.5f));
            canvas.drawRoundRect(mTipCardRect, dp(14), dp(14), mTipCardBorderPaint);

            float centerX = w / 2f;
            float startY = cardTop + dp(32);

            // Title
            canvas.drawText("Agent正在操作此页面", centerX, startY, mTipTitlePaint);

            // Description lines
            startY += dp(26);
            canvas.drawText("请保持浏览器在前台或小窗。", centerX, startY, mTipBodyPaint);
            startY += dp(22);
            canvas.drawText("切换标签页不影响Agent继续运行。", centerX, startY, mTipBodyPaint);

            // Dismiss footnote
            startY += dp(32);
            canvas.drawText("● 点击关闭遮罩提示 ●", centerX, startY, mTipFootPaint);
        }

        // 2. Draw breathing border in AGENT (only if driving currently visible tab) or TAKEOVER_REQUESTED mode
        if ((mControlMode == MODE_AGENT && isCurrentTabDriven) || mControlMode == MODE_TAKEOVER_REQUESTED) {
            needsMoreFrames = true;
            double phase = (now % 1800) / 1800.0 * Math.PI * 2.0;
            float alphaFactor = (float) (0.45 + 0.55 * (0.5 + 0.5 * Math.sin(phase)));
            int baseColor = (mControlMode == MODE_AGENT)
                ? AcidTheme.DARK_ACCENT // Acid green for AI driving
                : AcidTheme.COLOR_AMBER_ALERT; // Amber for human takeover alert

            int alpha = Math.min(255, Math.max(40, (int) (220 * alphaFactor)));
            mBorderPaint.setColor(Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)));
            float strokeW = dp(3.0f);
            mBorderPaint.setStrokeWidth(strokeW);
            float half = strokeW / 2f;
            mBorderRect.set(half, half, w - half, h - half);
            canvas.drawRect(mBorderRect, mBorderPaint);
        }

        // 2. Draw active click pulses
        Iterator<ClickPulse> it = mPulses.iterator();
        while (it.hasNext()) {
            ClickPulse p = it.next();
            long elapsed = now - p.startTime;
            if (elapsed > ClickPulse.DURATION_MS) {
                it.remove();
                continue;
            }
            needsMoreFrames = true;
            float progress = elapsed / (float) ClickPulse.DURATION_MS;
            int alpha = (int) (255 * (1f - progress));

            // Outer expanding ring
            float radius1 = dp(8) + dp(34) * progress;
            mPulsePaint.setStrokeWidth(dp(2.5f));
            mPulsePaint.setColor(Color.argb(alpha, 0, 255, 136));
            canvas.drawCircle(p.x, p.y, radius1, mPulsePaint);

            // Inner crosshair dot
            mPulsePaint.setStrokeWidth(dp(1.5f));
            mPulsePaint.setColor(Color.argb(alpha, 0, 229, 255));
            canvas.drawCircle(p.x, p.y, dp(5), mPulsePaint);

            if (p.label != null && !p.label.isEmpty()) {
                float textW = mLabelTextPaint.measureText(p.label);
                float bx = Math.min(Math.max(dp(6), p.x - textW / 2f - dp(6)), w - textW - dp(16));
                float by = Math.max(dp(24), p.y - dp(18));
                RectF pill = new RectF(bx, by - dp(14), bx + textW + dp(12), by + dp(4));
                mLabelBgPaint.setAlpha(alpha);
                mLabelTextPaint.setAlpha(alpha);
                canvas.drawRoundRect(pill, dp(4), dp(4), mLabelBgPaint);
                canvas.drawText(p.label, bx + dp(6), by - dp(1), mLabelTextPaint);
            }
        }

        if (needsMoreFrames) {
            postInvalidateOnAnimation();
        }
    }
}
