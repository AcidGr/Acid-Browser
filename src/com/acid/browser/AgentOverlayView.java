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

    private int mControlMode = MODE_HUMAN;
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabelTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBorderRect = new RectF();

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
        mLabelBgPaint.setColor(Color.parseColor("#E60A0E14"));

        mLabelTextPaint.setColor(Color.parseColor("#00FF88"));
        mLabelTextPaint.setTextSize(dp(11));
        mLabelTextPaint.setFakeBoldText(true);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setControlMode(int mode) {
        if (mControlMode != mode) {
            mControlMode = mode;
            postInvalidateOnAnimation();
        }
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
        // When AI is actively driving, block accidental human touches on the WebView
        if (mControlMode == MODE_AGENT) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                long now = SystemClock.uptimeMillis();
                if (now - mLastToastTime > 2200) {
                    mLastToastTime = now;
                    Toast.makeText(
                        getContext(),
                        "[AI LOCK] AI 正在操作网页，如需手动操作请点击底部 [接管]",
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

        // 1. Draw breathing border in AGENT or TAKEOVER_REQUESTED mode
        if (mControlMode == MODE_AGENT || mControlMode == MODE_TAKEOVER_REQUESTED) {
            needsMoreFrames = true;
            double phase = (now % 1800) / 1800.0 * Math.PI * 2.0;
            float alphaFactor = (float) (0.45 + 0.55 * (0.5 + 0.5 * Math.sin(phase)));
            int baseColor = (mControlMode == MODE_AGENT)
                ? Color.parseColor("#00FF88") // Acid green for AI driving
                : Color.parseColor("#FFB300"); // Amber for human takeover alert

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
