package com.acid.browser;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure SVG Vector Icon Engine for Acid-broswer (24x24 viewBox coordinate space).
 * Zero emoji, crisp anti-aliased vector geometry at any screen DPI.
 */
public class AcidIcons {

    public static final int ICON_BACK = 1;
    public static final int ICON_HOME = 2;
    public static final int ICON_RELOAD = 3;
    public static final int ICON_MOBILE = 4;
    public static final int ICON_DESKTOP = 5;
    public static final int ICON_SOM = 6;
    public static final int ICON_TABS = 7;
    public static final int ICON_AGENT = 8;
    public static final int ICON_HUMAN = 9;
    public static final int ICON_SHIELD = 10;
    public static final int ICON_TERMINAL = 11;
    public static final int ICON_EYE = 12;
    public static final int ICON_CLICK = 13;
    public static final int ICON_FILL = 14;
    public static final int ICON_LOGO = 15;
    public static final int ICON_FORWARD = 16;
    public static final int ICON_MENU = 17;
    public static final int ICON_MOON = 18;
    public static final int ICON_SUN = 19;
    public static final int ICON_BOOKMARK = 20;
    public static final int ICON_HISTORY = 21;
    public static final int ICON_DOWNLOAD = 22;
    public static final int ICON_CLOSE = 23;
    public static final int ICON_SEARCH = 24;
    public static final int ICON_PLUS = 25;
    public static final int ICON_TRASH = 26;
    public static final int ICON_INCOGNITO = 27;
    public static final int ICON_RETURN = 28;
    public static final int ICON_GLOBE = 29;
    public static final int ICON_CHEVRON_DOWN = 30;
    public static final int ICON_CHEVRON_UP = 31;
    public static final int ICON_CHECK = 32;
    public static final int ICON_SEND = 33;
    public static final int ICON_SETTINGS = 34;
    public static final int ICON_CHAT_LIST = 35;
    public static final int ICON_FILE = 36;
    public static final int ICON_MODEL = 37;
    public static final int ICON_EDIT = 38;

    /**
     * Parses a subset of SVG path data ("M x y L x y H x V y C x1 y1 x2 y2 x y Z")
     * in a 24x24 viewBox into an Android Graphics Path.
     */
    public static Path parseSvgPath(String d) {
        Path p = new Path();
        if (d == null || d.isEmpty()) return p;

        List<String> tokens = tokenizePath(d);
        int i = 0;
        char cmd = 0;
        float curX = 0f, curY = 0f;
        float startX = 0f, startY = 0f;

        while (i < tokens.size()) {
            String t = tokens.get(i);
            char first = t.charAt(0);
            if (Character.isLetter(first)) {
                cmd = first;
                i++;
                if (cmd == 'Z' || cmd == 'z') {
                    p.close();
                    curX = startX;
                    curY = startY;
                    continue;
                }
            }

            switch (cmd) {
                case 'M': {
                    float x = Float.parseFloat(tokens.get(i++));
                    float y = Float.parseFloat(tokens.get(i++));
                    p.moveTo(x, y);
                    curX = x; curY = y;
                    startX = x; startY = y;
                    cmd = 'L';
                    break;
                }
                case 'm': {
                    float dx = Float.parseFloat(tokens.get(i++));
                    float dy = Float.parseFloat(tokens.get(i++));
                    curX += dx; curY += dy;
                    p.moveTo(curX, curY);
                    startX = curX; startY = curY;
                    cmd = 'l';
                    break;
                }
                case 'L': {
                    float x = Float.parseFloat(tokens.get(i++));
                    float y = Float.parseFloat(tokens.get(i++));
                    p.lineTo(x, y);
                    curX = x; curY = y;
                    break;
                }
                case 'l': {
                    float dx = Float.parseFloat(tokens.get(i++));
                    float dy = Float.parseFloat(tokens.get(i++));
                    curX += dx; curY += dy;
                    p.lineTo(curX, curY);
                    break;
                }
                case 'H': {
                    float x = Float.parseFloat(tokens.get(i++));
                    p.lineTo(x, curY);
                    curX = x;
                    break;
                }
                case 'h': {
                    float dx = Float.parseFloat(tokens.get(i++));
                    curX += dx;
                    p.lineTo(curX, curY);
                    break;
                }
                case 'V': {
                    float y = Float.parseFloat(tokens.get(i++));
                    p.lineTo(curX, y);
                    curY = y;
                    break;
                }
                case 'v': {
                    float dy = Float.parseFloat(tokens.get(i++));
                    curY += dy;
                    p.lineTo(curX, curY);
                    break;
                }
                case 'C': {
                    float x1 = Float.parseFloat(tokens.get(i++));
                    float y1 = Float.parseFloat(tokens.get(i++));
                    float x2 = Float.parseFloat(tokens.get(i++));
                    float y2 = Float.parseFloat(tokens.get(i++));
                    float x = Float.parseFloat(tokens.get(i++));
                    float y = Float.parseFloat(tokens.get(i++));
                    p.cubicTo(x1, y1, x2, y2, x, y);
                    curX = x; curY = y;
                    break;
                }
                case 'c': {
                    float dx1 = Float.parseFloat(tokens.get(i++));
                    float dy1 = Float.parseFloat(tokens.get(i++));
                    float dx2 = Float.parseFloat(tokens.get(i++));
                    float dy2 = Float.parseFloat(tokens.get(i++));
                    float dx = Float.parseFloat(tokens.get(i++));
                    float dy = Float.parseFloat(tokens.get(i++));
                    p.cubicTo(curX + dx1, curY + dy1, curX + dx2, curY + dy2, curX + dx, curY + dy);
                    curX += dx; curY += dy;
                    break;
                }
                case 'Q': {
                    float x1 = Float.parseFloat(tokens.get(i++));
                    float y1 = Float.parseFloat(tokens.get(i++));
                    float x = Float.parseFloat(tokens.get(i++));
                    float y = Float.parseFloat(tokens.get(i++));
                    p.quadTo(x1, y1, x, y);
                    curX = x; curY = y;
                    break;
                }
                case 'q': {
                    float dx1 = Float.parseFloat(tokens.get(i++));
                    float dy1 = Float.parseFloat(tokens.get(i++));
                    float dx = Float.parseFloat(tokens.get(i++));
                    float dy = Float.parseFloat(tokens.get(i++));
                    p.quadTo(curX + dx1, curY + dy1, curX + dx, curY + dy);
                    curX += dx; curY += dy;
                    break;
                }
                default:
                    i++;
                    break;
            }
        }
        return p;
    }

    private static List<String> tokenizePath(String d) {
        List<String> list = new ArrayList<String>();
        int n = d.length();
        int i = 0;
        while (i < n) {
            char c = d.charAt(i);
            if (c == ' ' || c == ',' || c == '\n' || c == '\t' || c == '\r') {
                i++;
            } else if (Character.isLetter(c)) {
                list.add(String.valueOf(c));
                i++;
            } else if (c == '-' || c == '+' || c == '.' || Character.isDigit(c)) {
                int start = i;
                i++;
                boolean seenDot = (c == '.');
                while (i < n) {
                    char nc = d.charAt(i);
                    if (Character.isDigit(nc)) {
                        i++;
                    } else if (nc == '.' && !seenDot) {
                        seenDot = true;
                        i++;
                    } else {
                        break;
                    }
                }
                list.add(d.substring(start, i));
            } else {
                i++;
            }
        }
        return list;
    }

    /**
     * Builds the vector Path in 24x24 SVG coordinate space for each icon ID.
     */
    public static Path buildIconPath24(int iconId) {
        Path p = new Path();
        switch (iconId) {
            case ICON_BACK:
                // SVG Chevron Left + stem: M 15 18 L 9 12 L 15 6
                p.addPath(parseSvgPath("M 15.5 18.5 L 8.5 12 L 15.5 5.5"));
                break;

            case ICON_HOME:
                // SVG Home outline: M 3 10.5 L 12 3 L 21 10.5 V 20 H 15 V 14 H 9 V 20 H 3 Z
                p.addPath(parseSvgPath("M 3.5 10.5 L 12 3.2 L 20.5 10.5 V 20 H 14.8 V 14.2 H 9.2 V 20 H 3.5 Z"));
                break;

            case ICON_RELOAD:
                // Classic crisp clockwise reload arrow (Chrome / Safari style)
                p.addArc(new RectF(4.5f, 4.5f, 19.5f, 19.5f), -60f, 300f);
                p.addPath(parseSvgPath("M 12.5 3.2 L 18 5.6 L 15.6 11"));
                break;

            case ICON_MOBILE:
                // SVG Smartphone frame + speaker + home indicator
                p.addRoundRect(new RectF(6.5f, 2.5f, 17.5f, 21.5f), 2.2f, 2.2f, Path.Direction.CW);
                p.addPath(parseSvgPath("M 10 5.2 H 14 M 10.5 18.5 H 13.5"));
                break;

            case ICON_DESKTOP:
                // SVG Desktop Monitor + stand
                p.addRoundRect(new RectF(2.5f, 3.5f, 21.5f, 16.5f), 1.8f, 1.8f, Path.Direction.CW);
                p.addPath(parseSvgPath("M 12 16.5 V 20.5 M 8 20.5 H 16"));
                break;

            case ICON_SOM:
                // SVG Set-of-Marks viewfider corners + center crosshair
                p.addPath(parseSvgPath(
                    "M 3 8 V 4 H 7 " +
                    "M 17 4 H 21 V 8 " +
                    "M 21 16 V 20 H 17 " +
                    "M 7 20 H 3 V 16 " +
                    "M 12 8.5 V 15.5 M 8.5 12 H 15.5"
                ));
                p.addCircle(12f, 12f, 2.2f, Path.Direction.CW);
                break;

            case ICON_TABS:
                // SVG Layered window cards
                p.addRoundRect(new RectF(3.5f, 6.5f, 16.5f, 19.5f), 2f, 2f, Path.Direction.CW);
                p.addPath(parseSvgPath("M 8 6.5 V 4.2 H 20.2 V 16.5 H 16.5"));
                break;

            case ICON_AGENT:
                // SVG AI Chip / Autonomous Core with pins
                p.addRoundRect(new RectF(6.5f, 6.5f, 17.5f, 17.5f), 2f, 2f, Path.Direction.CW);
                p.addRect(new RectF(9.8f, 9.8f, 14.2f, 14.2f), Path.Direction.CW);
                p.addPath(parseSvgPath(
                    "M 9.5 3 V 6.5 M 14.5 3 V 6.5 " +
                    "M 9.5 17.5 V 21 M 14.5 17.5 V 21 " +
                    "M 3 9.5 H 6.5 M 3 14.5 H 6.5 " +
                    "M 17.5 9.5 H 21 M 17.5 14.5 H 21"
                ));
                break;

            case ICON_HUMAN:
                // SVG Precision Pointer / Human Control Cursor
                p.addPath(parseSvgPath("M 5.5 3.5 L 19 10.5 L 12.5 13 L 16.5 19.5 L 13.8 21 L 9.8 14.5 L 5.5 18.5 Z"));
                break;

            case ICON_SHIELD:
                // SVG Cyber Shield + Checkmark
                p.addPath(parseSvgPath("M 12 2.5 L 4 6 V 11.5 C 4 16.8 7.4 20.6 12 22 C 16.6 20.6 20 16.8 20 11.5 V 6 L 12 2.5 Z"));
                p.addPath(parseSvgPath("M 8.8 12.2 L 11 14.4 L 15.5 9.8"));
                break;

            case ICON_TERMINAL:
                // SVG Terminal Prompt >_ inside frame
                p.addRoundRect(new RectF(2.5f, 4f, 21.5f, 20f), 2.2f, 2.2f, Path.Direction.CW);
                p.addPath(parseSvgPath("M 6.5 9 L 10.5 12 L 6.5 15 M 12 15.5 H 17.2"));
                break;

            case ICON_EYE:
                // SVG Semantic Observation Eye
                p.addPath(parseSvgPath("M 2 12 C 4.5 6.8 8 4.5 12 4.5 C 16 4.5 19.5 6.8 22 12 C 19.5 17.2 16 19.5 12 19.5 C 8 19.5 4.5 17.2 2 12 Z"));
                p.addCircle(12f, 12f, 3.2f, Path.Direction.CW);
                break;

            case ICON_CLICK:
                // SVG Target Click Ripple + Arrow
                p.addPath(parseSvgPath("M 5 12 A 7 7 0 1 1 16 5.8"));
                p.addCircle(11.5f, 11.5f, 2.5f, Path.Direction.CW);
                p.addPath(parseSvgPath("M 13.5 13.5 L 20.5 16.5 L 17.2 18 L 19.2 21 L 17.5 22 L 15.5 19 L 13.5 21 Z"));
                break;

            case ICON_FILL:
                // SVG Input Field + I-Beam Cursor
                p.addRoundRect(new RectF(2.5f, 6.5f, 21.5f, 17.5f), 2f, 2f, Path.Direction.CW);
                p.addPath(parseSvgPath("M 7.5 9 V 15 M 6 9 H 9 M 6 15 H 9 M 12 12 H 17.5"));
                break;

            case ICON_LOGO:
                // SVG Acid Hexagon + Lab Flask / Node core
                p.addPath(parseSvgPath("M 12 2 L 20.6 7 V 17 L 12 22 L 3.4 17 V 7 Z"));
                p.addPath(parseSvgPath("M 10 7.5 H 14 M 11 7.5 V 10.5 L 7.5 16.2 H 16.5 L 13 10.5 V 7.5"));
                break;

            case ICON_FORWARD:
                // SVG Chevron Right: M 8.5 5.5 L 15.5 12 L 8.5 18.5
                p.addPath(parseSvgPath("M 8.5 5.5 L 15.5 12 L 8.5 18.5"));
                break;

            case ICON_MENU:
                // SVG 3-Line Hamburger Menu
                p.addPath(parseSvgPath("M 4 6.5 H 20 M 4 12 H 20 M 4 17.5 H 20"));
                break;

            case ICON_MOON:
                // SVG Moon outline
                p.addPath(parseSvgPath("M 20.4 13.5 C 19.8 17.8 16.2 21 11.8 21 C 6.9 21 3 17.1 3 12.2 C 3 7.8 6.2 4.2 10.5 3.6 C 9.5 5.2 9 7 9 9 C 9 14 13 18 18 18 C 18.8 18 19.6 17.9 20.4 17.6 Z"));
                break;

            case ICON_SUN:
                // SVG Sun outline
                p.addCircle(12f, 12f, 4f, Path.Direction.CW);
                p.addPath(parseSvgPath("M 12 2 V 5 M 12 19 V 22 M 2 12 H 5 M 19 12 H 22 M 4.9 4.9 L 7.1 7.1 M 16.9 16.9 L 19.1 19.1 M 4.9 19.1 L 7.1 16.9 M 16.9 7.1 L 19.1 4.9"));
                break;

            case ICON_BOOKMARK:
                // SVG Bookmark ribbon
                p.addPath(parseSvgPath("M 6 3 H 18 V 21 L 12 17 L 6 21 Z"));
                break;

            case ICON_HISTORY:
                // SVG Clock circle + hands
                p.addCircle(12f, 12f, 9f, Path.Direction.CW);
                p.addPath(parseSvgPath("M 12 7 V 12 L 15.5 14"));
                break;

            case ICON_DOWNLOAD:
                // SVG Download tray + arrow
                p.addPath(parseSvgPath("M 12 3.5 V 14 M 7.5 10 L 12 14.5 L 16.5 10 M 4 19 H 20"));
                break;

            case ICON_CLOSE:
                // SVG Close X
                p.addPath(parseSvgPath("M 6 6 L 18 18 M 18 6 L 6 18"));
                break;

            case ICON_SEARCH:
                // SVG Magnifier
                p.addCircle(10.5f, 10.5f, 6f, Path.Direction.CW);
                p.addPath(parseSvgPath("M 15 15 L 20.5 20.5"));
                break;

            case ICON_PLUS:
                // SVG Plus
                p.addPath(parseSvgPath("M 12 5 V 19 M 5 12 H 19"));
                break;

            case ICON_TRASH:
                // SVG Trash can
                p.addPath(parseSvgPath("M 3 6 H 21 M 8 6 V 4 H 16 V 6 M 19 6 V 19 C 19 20.1 18.1 21 17 21 H 7 C 5.9 21 5 20.1 5 19 V 6 M 10 10 V 17 M 14 10 V 17"));
                break;

            case ICON_INCOGNITO:
                // SVG Spy glasses & hat
                p.addPath(parseSvgPath("M 2 10 H 22 M 7 10 L 9.2 3.5 H 14.8 L 17 10"));
                p.addCircle(7.5f, 15.5f, 3.5f, Path.Direction.CW);
                p.addCircle(16.5f, 15.5f, 3.5f, Path.Direction.CW);
                p.addPath(parseSvgPath("M 11 15.5 H 13"));
                break;

            case ICON_RETURN:
                // SVG Return undo arrow
                p.addPath(parseSvgPath("M 9 14 L 4 9 L 9 4"));
                p.addPath(parseSvgPath("M 20 20 V 13 C 20 10.8 18.2 9 16 9 H 4"));
                break;

            case ICON_GLOBE:
                // SVG Web Globe
                p.addCircle(12f, 12f, 9f, Path.Direction.CW);
                p.addPath(parseSvgPath("M 3 12 H 21 M 12 3 C 8.5 7.5 8.5 16.5 12 21 M 12 3 C 15.5 7.5 15.5 16.5 12 21"));
                break;

            case ICON_CHEVRON_DOWN:
                p.addPath(parseSvgPath("M 6 9 L 12 15 L 18 9"));
                break;

            case ICON_CHEVRON_UP:
                p.addPath(parseSvgPath("M 6 15 L 12 9 L 18 15"));
                break;

            case ICON_CHECK:
                p.addCircle(12f, 12f, 9f, Path.Direction.CW);
                p.addPath(parseSvgPath("M 8 12.2 L 10.8 15 L 16.2 9.5"));
                break;

            case ICON_SEND:
                // SVG Paper Plane / Up-Right Send Arrow
                p.addPath(parseSvgPath("M 21 3 L 3 10.5 L 10.5 13.5 L 13.5 21 L 21 3 Z M 10.5 13.5 L 21 3"));
                break;

            case ICON_SETTINGS:
                // SVG Sliders / Settings
                p.addPath(parseSvgPath("M 4 6 H 14 M 18 6 H 20 M 4 12 H 8 M 12 12 H 20 M 4 18 H 16 M 20 18 H 20.5"));
                p.addCircle(16f, 6f, 2f, Path.Direction.CW);
                p.addCircle(10f, 12f, 2f, Path.Direction.CW);
                p.addCircle(18f, 18f, 2f, Path.Direction.CW);
                break;

            case ICON_CHAT_LIST:
                // SVG Chat Bubble + Lines
                p.addPath(parseSvgPath("M 4 4 H 20 V 16 H 8 L 4 20 V 4 Z M 8 8.5 H 16 M 8 12 H 13.5"));
                break;

            case ICON_FILE:
                // SVG File Document
                p.addPath(parseSvgPath("M 6 3 H 14 L 19 8 V 21 H 6 V 3 Z M 14 3 V 8 H 19 M 9 13 H 15 M 9 17 H 15"));
                break;

            case ICON_MODEL:
                // SVG Layered Model Stack / Cube (DSH IconDataOutline style)
                p.addPath(parseSvgPath(
                    "M 12 3 L 21 7.5 L 12 12 L 3 7.5 Z " +
                    "M 3 12 L 12 16.5 L 21 12 " +
                    "M 3 16.5 L 12 21 L 21 16.5"
                ));
                break;

            case ICON_EDIT:
                // SVG Pencil Edit
                p.addPath(parseSvgPath("M 16.5 3.5 L 20.5 7.5 L 8 20 H 4 V 16 L 16.5 3.5 Z M 14 6 L 18 10"));
                break;

            default:
                p.addCircle(12f, 12f, 8f, Path.Direction.CW);
                break;
        }
        return p;
    }

    /**
     * Lightweight View that renders a 24x24 SVG vector path scaled to its bounds.
     */
    public static class SvgIconView extends View {
        private int mIconId;
        private int mColor;
        private float mStrokeWidthDp = 1.9f;
        private int mPreferredPx;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Path mBasePath;
        private final Path mScaledPath = new Path();
        private final Matrix mScaleMatrix = new Matrix();

        public SvgIconView(Context context, int iconId, int color, int sizeDp) {
            super(context);
            mIconId = iconId;
            mColor = color;
            mBasePath = buildIconPath24(iconId);

            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeJoin(Paint.Join.ROUND);
            mPaint.setColor(color);

            mPreferredPx = Math.round(sizeDp * context.getResources().getDisplayMetrics().density);
            setLayoutParams(new LinearLayout.LayoutParams(mPreferredPx, mPreferredPx));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int wMode = MeasureSpec.getMode(widthMeasureSpec);
            int wSize = MeasureSpec.getSize(widthMeasureSpec);
            int hMode = MeasureSpec.getMode(heightMeasureSpec);
            int hSize = MeasureSpec.getSize(heightMeasureSpec);

            int w = (wMode == MeasureSpec.EXACTLY) ? wSize : mPreferredPx;
            int h = (hMode == MeasureSpec.EXACTLY) ? hSize : mPreferredPx;
            setMeasuredDimension(w, h);
        }

        public void setIcon(int iconId) {
            if (mIconId != iconId) {
                mIconId = iconId;
                mBasePath = buildIconPath24(iconId);
                invalidate();
            }
        }

        public void setColor(int color) {
            if (mColor != color) {
                mColor = color;
                mPaint.setColor(color);
                invalidate();
            }
        }

        public void setStrokeWidthDp(float dpVal) {
            mStrokeWidthDp = dpVal;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0 || mBasePath == null) return;

            float density = getResources().getDisplayMetrics().density;
            mPaint.setStrokeWidth(mStrokeWidthDp * density);

            float scale = Math.min(w, h) / 24f;
            float dx = (w - 24f * scale) / 2f;
            float dy = (h - 24f * scale) / 2f;

            mScaleMatrix.reset();
            mScaleMatrix.postScale(scale, scale);
            mScaleMatrix.postTranslate(dx, dy);

            mBasePath.transform(mScaleMatrix, mScaledPath);
            canvas.drawPath(mScaledPath, mPaint);
        }
    }

    /**
     * Compound Pill Button containing an SVG vector icon on the left and a crisp label on the right.
     */
    public static class BadgeButton extends LinearLayout {
        private final SvgIconView mIconView;
        private final TextView mTextView;

        public BadgeButton(Context context, int iconId, String text, int accentColor, OnClickListener listener) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            float d = context.getResources().getDisplayMetrics().density;
            int padH = Math.round(7 * d);
            int padV = Math.round(4.5f * d);
            setPadding(padH, padV, padH, padV);

            mIconView = new SvgIconView(context, iconId, accentColor, 14);
            addView(mIconView);

            mTextView = new TextView(context);
            mTextView.setText(text);
            mTextView.setTextColor(accentColor);
            mTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.2f);
            mTextView.setTypeface(Typeface.DEFAULT_BOLD);
            mTextView.setSingleLine(true);
            LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            tvLp.leftMargin = Math.round(4 * d);
            addView(mTextView, tvLp);

            applyFrame(accentColor);

            LinearLayout.LayoutParams selfLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            selfLp.leftMargin = Math.round(4 * d);
            setLayoutParams(selfLp);

            if (listener != null) {
                setOnClickListener(listener);
            }
        }

        private void applyFrame(int accentColor) {
            float d = getResources().getDisplayMetrics().density;
            GradientDrawable gd = new GradientDrawable();
            int fill = Color.argb(30, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor));
            gd.setColor(fill);
            gd.setCornerRadius(6 * d);
            gd.setStroke(Math.max(1, Math.round(1 * d)), accentColor);
            setBackground(gd);
        }

        public void update(int iconId, String text, int accentColor) {
            mIconView.setIcon(iconId);
            mIconView.setColor(accentColor);
            mTextView.setText(text);
            mTextView.setTextColor(accentColor);
            applyFrame(accentColor);
        }
    }

    /**
     * Creates a pure SVG icon toolbar button with touch feedback padding.
     */
    public static LinearLayout createToolbarIconBtn(Context context, int iconId, int color, View.OnClickListener listener) {
        LinearLayout box = new LinearLayout(context);
        box.setGravity(Gravity.CENTER);
        float d = context.getResources().getDisplayMetrics().density;
        int padH = Math.round(7 * d);
        int padV = Math.round(5 * d);
        box.setPadding(padH, padV, padH, padV);
        SvgIconView icon = new SvgIconView(context, iconId, color, 18);
        box.addView(icon);
        if (listener != null) {
            box.setOnClickListener(listener);
        }
        return box;
    }

    /**
     * Browser-style square Tab Box showing the count number inside, vector rendered.
     */
    public static class TabBoxView extends View {
        private int mCount = 1;
        private int mColor;
        private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();

        public TabBoxView(Context context, int color, int sizeDp) {
            super(context);
            mColor = color;
            mStrokePaint.setStyle(Paint.Style.STROKE);
            mStrokePaint.setStrokeCap(Paint.Cap.ROUND);
            mStrokePaint.setStrokeJoin(Paint.Join.ROUND);
            mStrokePaint.setColor(color);

            mTextPaint.setColor(color);
            mTextPaint.setTextAlign(Paint.Align.CENTER);
            mTextPaint.setTypeface(Typeface.DEFAULT_BOLD);

            int px = Math.round(sizeDp * context.getResources().getDisplayMetrics().density);
            setLayoutParams(new LinearLayout.LayoutParams(px, px));
        }

        public void setCount(int count) {
            if (mCount != count) {
                mCount = count;
                invalidate();
            }
        }

        public int getCount() {
            return mCount;
        }

        public void setColor(int color) {
            if (mColor != color) {
                mColor = color;
                mStrokePaint.setColor(color);
                mTextPaint.setColor(color);
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            float density = getResources().getDisplayMetrics().density;
            float stroke = 1.9f * density;
            mStrokePaint.setStrokeWidth(stroke);
            float pad = stroke / 2f + 1f * density;
            mRect.set(pad, pad, w - pad, h - pad);
            float radius = 3.5f * density;
            canvas.drawRoundRect(mRect, radius, radius, mStrokePaint);

            String text = mCount > 99 ? "99+" : String.valueOf(mCount);
            float textSize = (mCount > 9 ? 9.5f : 11.5f) * density;
            mTextPaint.setTextSize(textSize);
            Paint.FontMetrics fm = mTextPaint.getFontMetrics();
            float textY = (h - fm.bottom - fm.top) / 2f;
            canvas.drawText(text, w / 2f, textY, mTextPaint);
        }
    }
}
