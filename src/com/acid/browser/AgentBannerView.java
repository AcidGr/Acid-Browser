package com.acid.browser;

import android.content.Context;
import android.text.Layout;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Top automation and human-takeover banner for the browser viewport.
 *
 * <p>Owns banner layout, activity summary formatting (head-truncated for tools/errors,
 * tail-following for streaming thinking/replies), and expandable multi-line human takeover reasons.
 */
public class AgentBannerView extends LinearLayout {

    public interface OnBannerActionListener {
        void onBannerActionClicked();
    }

    public static final int ELLIPSIZE_END = 0;
    public static final int ELLIPSIZE_START = 1;
    public static final int ELLIPSIZE_NONE = 2;

    public static final int TAIL_WINDOW_CHARS = 120;
    public static final int HEAD_SUMMARY_MAX_CHARS = 48;
    public static final int TAKEOVER_COLLAPSED_MAX_LINES = 6;

    public static final class ActivitySummary {
        public final String prefix;
        public final String body;
        public final int ellipsizeMode;

        public ActivitySummary(String prefix, String body, int ellipsizeMode) {
            this.prefix = prefix != null ? prefix : "";
            this.body = body != null ? body : "";
            this.ellipsizeMode = ellipsizeMode;
        }

        public boolean isEmpty() {
            return prefix.isEmpty() && body.isEmpty();
        }
    }

    public static final class BannerPresentation {
        public final boolean visible;
        public final int iconId;
        public final int bannerColor;
        public final String prefixText;
        public final String statusText;
        public final boolean singleLine;
        public final int maxLines;
        public final int ellipsizeMode;
        public final boolean statusClickable;
        public final int actionIconId;
        public final String actionLabel;
        public final int actionColor;

        public BannerPresentation(
            boolean visible,
            int iconId,
            int bannerColor,
            String prefixText,
            String statusText,
            boolean singleLine,
            int maxLines,
            int ellipsizeMode,
            boolean statusClickable,
            int actionIconId,
            String actionLabel,
            int actionColor
        ) {
            this.visible = visible;
            this.iconId = iconId;
            this.bannerColor = bannerColor;
            this.prefixText = prefixText != null ? prefixText : "";
            this.statusText = statusText != null ? statusText : "";
            this.singleLine = singleLine;
            this.maxLines = maxLines;
            this.ellipsizeMode = ellipsizeMode;
            this.statusClickable = statusClickable;
            this.actionIconId = actionIconId;
            this.actionLabel = actionLabel != null ? actionLabel : "";
            this.actionColor = actionColor;
        }
    }

    private final AcidIcons.SvgIconView mIconView;
    private final TextView mTvPrefix;
    private final TextView mTvStatus;
    private final AcidIcons.BadgeButton mBtnAction;

    private String mActiveItemId = null;
    private ActivitySummary mLatestSummary = null;
    private int mLastControlMode = AgentOverlayView.MODE_HUMAN;
    private boolean mLastAgentRunning = false;
    private boolean mLastIsDrivingCurrentTab = false;
    private String mLastRawTakeoverReason = "";
    private String mLastNormalizedTakeoverReason = "";
    private boolean mTakeoverExpanded = false;
    private boolean mIsDarkMode = false;

    private boolean mHasAppliedLineConfig = false;
    private boolean mAppliedSingleLine = true;
    private int mAppliedMaxLines = 1;
    private int mAppliedEllipsizeMode = ELLIPSIZE_END;

    public AgentBannerView(Context context, final OnBannerActionListener listener) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int padH = AcidTheme.dp(context, 10);
        int padV = AcidTheme.dp(context, 6);
        setPadding(padH, padV, padH, padV);
        setVisibility(View.GONE);

        mIconView = new AcidIcons.SvgIconView(context, AcidIcons.ICON_HUMAN, AcidTheme.COLOR_AMBER, 16);
        addView(mIconView);

        mTvPrefix = new TextView(context);
        mTvPrefix.setTextColor(AcidTheme.COLOR_AMBER);
        mTvPrefix.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
        mTvPrefix.setSingleLine(true);
        LinearLayout.LayoutParams prefixLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        prefixLp.leftMargin = AcidTheme.dp(context, 6);
        mTvPrefix.setVisibility(View.GONE);
        addView(mTvPrefix, prefixLp);

        mTvStatus = new TextView(context);
        mTvStatus.setText(AcidLocale.tr("AI 请求接管"));
        mTvStatus.setTextColor(AcidTheme.COLOR_AMBER);
        mTvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
        mTvStatus.setSingleLine(true);
        mTvStatus.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        );
        tvLp.leftMargin = AcidTheme.dp(context, 6);
        tvLp.rightMargin = AcidTheme.dp(context, 6);
        mTvStatus.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mLastControlMode != AgentOverlayView.MODE_TAKEOVER_REQUESTED) {
                    return;
                }
                if (!mTakeoverExpanded && !isTakeoverStatusTruncated()) {
                    return;
                }
                mTakeoverExpanded = !mTakeoverExpanded;
                render(
                    mLastControlMode,
                    mLastAgentRunning,
                    mLastIsDrivingCurrentTab,
                    mLastRawTakeoverReason,
                    mIsDarkMode
                );
            }
        });
        addView(mTvStatus, tvLp);

        mBtnAction = new AcidIcons.BadgeButton(context, AcidIcons.ICON_AGENT, "交还AI", AcidTheme.COLOR_AMBER, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onBannerActionClicked();
                }
            }
        });
        addView(mBtnAction);
    }

    /**
     * Collapses whitespace in O(maxChars) from the end of {@code text}, prepending {@code "…"} when
     * earlier non-whitespace content was omitted.
     */
    public static String extractSingleLineTail(String text, int maxChars) {
        if (text == null || maxChars <= 0) return "";
        int end = text.length() - 1;
        while (end >= 0 && Character.isWhitespace(text.charAt(end))) {
            end--;
        }
        if (end < 0) return "";

        StringBuilder rev = new StringBuilder(Math.min(maxChars, end + 1));
        boolean pendingSpace = false;
        boolean truncated = false;
        for (int i = end; i >= 0; i--) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                pendingSpace = true;
            } else {
                if (pendingSpace) {
                    if (rev.length() + 1 >= maxChars) {
                        truncated = true;
                        break;
                    }
                    rev.append(' ');
                    pendingSpace = false;
                }
                if (rev.length() >= maxChars) {
                    truncated = true;
                    break;
                }
                rev.append(c);
            }
        }
        rev.reverse();
        if (!truncated && rev.length() == 4 && "null".contentEquals(rev)) {
            return "";
        }
        return truncated ? ("\u2026" + rev.toString()) : rev.toString();
    }

    /**
     * Collapses whitespace in O(maxChars) from the start of {@code text}, appending {@code "..."}
     * when later non-whitespace content was omitted.
     */
    public static String extractSingleLineHead(String text, int maxChars) {
        if (text == null || maxChars <= 0) return "";
        int n = text.length();
        int start = 0;
        while (start < n && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        if (start >= n) return "";

        StringBuilder sb = new StringBuilder(Math.min(maxChars, n - start));
        boolean pendingSpace = false;
        boolean truncated = false;
        for (int i = start; i < n; i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                pendingSpace = true;
            } else {
                if (pendingSpace) {
                    if (sb.length() + 1 >= maxChars) {
                        truncated = true;
                        break;
                    }
                    sb.append(' ');
                    pendingSpace = false;
                }
                if (sb.length() >= maxChars) {
                    truncated = true;
                    break;
                }
                sb.append(c);
            }
        }
        if (!truncated && sb.length() == 4 && "null".contentEquals(sb)) {
            return "";
        }
        return truncated ? (sb.toString() + "...") : sb.toString();
    }

    /** Normalizes line endings and trims takeover reasons while keeping intentional line breaks. */
    public static String normalizeMultilineReason(String reason) {
        if (reason == null) return "";
        String trimmed = reason.trim();
        if (trimmed.isEmpty() || "null".equals(trimmed)) return "";
        StringBuilder sb = new StringBuilder(trimmed.length());
        int consecutiveNewlines = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\r') {
                if (i + 1 < trimmed.length() && trimmed.charAt(i + 1) == '\n') {
                    i++;
                }
                c = '\n';
            }
            if (c == '\n') {
                consecutiveNewlines++;
                if (consecutiveNewlines <= 2) {
                    sb.append('\n');
                }
            } else {
                consecutiveNewlines = 0;
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static ActivitySummary summarizeUiItem(AcidSessionStore.UiMessageItem item) {
        if (item == null || item.type == null) {
            return new ActivitySummary("", "", ELLIPSIZE_END);
        }
        if (AcidSessionStore.TYPE_TOOL.equals(item.type)) {
            String raw;
            if (item.toolSummary != null
                    && (item.toolSummary.startsWith("生成参数中 (") || "正在准备参数...".equals(item.toolSummary))
                    && item.toolName != null && !item.toolName.trim().isEmpty()) {
                raw = item.toolName.trim() + " · " + item.toolSummary;
            } else {
                raw = (item.toolSummary != null && !item.toolSummary.trim().isEmpty())
                    ? item.toolSummary
                    : item.toolName;
            }
            String body = extractSingleLineHead(raw, HEAD_SUMMARY_MAX_CHARS);
            return body.isEmpty()
                ? new ActivitySummary("", "", ELLIPSIZE_END)
                : new ActivitySummary("[工具] ", body, ELLIPSIZE_END);
        }
        if (AcidSessionStore.TYPE_THINKING.equals(item.type)) {
            String body = extractSingleLineTail(item.text, TAIL_WINDOW_CHARS);
            return body.isEmpty()
                ? new ActivitySummary("", "思考中...", ELLIPSIZE_END)
                : new ActivitySummary("思考: ", body, ELLIPSIZE_START);
        }
        if (AcidSessionStore.TYPE_ASSISTANT.equals(item.type)) {
            String body = extractSingleLineTail(item.text, TAIL_WINDOW_CHARS);
            return body.isEmpty()
                ? new ActivitySummary("", "正在回答...", ELLIPSIZE_END)
                : new ActivitySummary("回答: ", body, ELLIPSIZE_START);
        }
        if (AcidSessionStore.TYPE_ERROR.equals(item.type)) {
            String body = extractSingleLineHead(item.text, HEAD_SUMMARY_MAX_CHARS);
            return body.isEmpty()
                ? new ActivitySummary("", "", ELLIPSIZE_END)
                : new ActivitySummary("异常: ", body, ELLIPSIZE_END);
        }
        return new ActivitySummary("", "", ELLIPSIZE_END);
    }

    private static boolean isStaleCompletedItem(String activeItemId, AcidSessionStore.UiMessageItem item) {
        return item != null && !item.isRunning
            && activeItemId != null && !activeItemId.isEmpty()
            && item.id != null && !activeItemId.equals(item.id);
    }

    /**
     * Prevents a late finalization update for an older bubble (e.g. closing thinking block)
     * from overwriting the banner once a newer active bubble has taken over.
     */
    public static boolean shouldAcceptItemUpdate(String activeItemId, AcidSessionStore.UiMessageItem item) {
        if (isStaleCompletedItem(activeItemId, item)) {
            return false;
        }
        return !summarizeUiItem(item).isEmpty();
    }

    public static BannerPresentation resolvePresentation(
        int controlMode,
        boolean agentRunning,
        boolean isDrivingCurrentTab,
        String takeoverReason,
        ActivitySummary latestSummary,
        boolean takeoverExpanded,
        boolean isDarkMode
    ) {
        if (controlMode != AgentOverlayView.MODE_AGENT
                && controlMode != AgentOverlayView.MODE_TAKEOVER_REQUESTED
                && !agentRunning) {
            return new BannerPresentation(
                false,
                AcidIcons.ICON_AGENT,
                AcidTheme.accent(isDarkMode),
                "",
                "",
                true,
                1,
                ELLIPSIZE_END,
                false,
                AcidIcons.ICON_AGENT,
                "",
                AcidTheme.accent(isDarkMode)
            );
        }

        int accent = AcidTheme.accent(isDarkMode);
        if (controlMode == AgentOverlayView.MODE_TAKEOVER_REQUESTED) {
            String cleanReason = normalizeMultilineReason(takeoverReason);
            String statusText = AcidLocale.tr("请人工协助: " + (cleanReason.isEmpty() ? "请完成页面操作" : cleanReason));
            return new BannerPresentation(
                true,
                AcidIcons.ICON_HUMAN,
                AcidTheme.COLOR_AMBER,
                "",
                statusText,
                false,
                takeoverExpanded ? Integer.MAX_VALUE : TAKEOVER_COLLAPSED_MAX_LINES,
                takeoverExpanded ? ELLIPSIZE_NONE : ELLIPSIZE_END,
                true,
                AcidIcons.ICON_AGENT,
                "交还AI",
                accent
            );
        }

        String prefixText = "";
        String statusText;
        int ellipsizeMode = ELLIPSIZE_END;
        if (latestSummary != null && !latestSummary.isEmpty()) {
            prefixText = latestSummary.prefix.isEmpty() ? "" : AcidLocale.tr(latestSummary.prefix);
            if (latestSummary.prefix.isEmpty() || "[工具] ".equals(latestSummary.prefix)) {
                statusText = AcidLocale.tr(latestSummary.body);
            } else {
                statusText = latestSummary.body;
            }
            ellipsizeMode = latestSummary.ellipsizeMode;
        } else if (controlMode == AgentOverlayView.MODE_AGENT) {
            statusText = AcidLocale.tr(
                isDrivingCurrentTab ? "自动化运行中 · 页面触控已锁定" : "后台自动化进行中 · 当前页可自由浏览"
            );
        } else {
            statusText = AcidLocale.tr("Agent 运行中 · 正在思考准备...");
        }

        int actionIconId;
        String actionLabel;
        int actionColor;
        if (controlMode == AgentOverlayView.MODE_AGENT) {
            actionIconId = isDrivingCurrentTab ? AcidIcons.ICON_HUMAN : AcidIcons.ICON_GLOBE;
            actionLabel = isDrivingCurrentTab ? "点此接管" : "点击查看";
            actionColor = isDrivingCurrentTab ? AcidTheme.COLOR_AMBER : accent;
        } else {
            actionIconId = isDrivingCurrentTab ? AcidIcons.ICON_CHAT_LIST : AcidIcons.ICON_GLOBE;
            actionLabel = isDrivingCurrentTab ? "查看对话" : "点击查看";
            actionColor = accent;
        }

        return new BannerPresentation(
            true,
            AcidIcons.ICON_AGENT,
            accent,
            prefixText,
            statusText,
            true,
            1,
            ellipsizeMode,
            false,
            actionIconId,
            actionLabel,
            actionColor
        );
    }

    public boolean onActivityItemAdded(AcidSessionStore.UiMessageItem item) {
        if (item != null && AcidSessionStore.TYPE_USER.equals(item.type)) {
            clearActivitySummary();
            return true;
        }
        ActivitySummary summary = summarizeUiItem(item);
        if (summary.isEmpty()) {
            return false;
        }
        mActiveItemId = item.id;
        mLatestSummary = summary;
        return true;
    }

    public boolean onActivityItemUpdated(AcidSessionStore.UiMessageItem item) {
        if (item == null || isStaleCompletedItem(mActiveItemId, item)) {
            return false;
        }
        ActivitySummary summary = summarizeUiItem(item);
        if (summary.isEmpty()) {
            return false;
        }
        mActiveItemId = item.id;
        mLatestSummary = summary;
        return true;
    }

    public void clearActivitySummary() {
        mActiveItemId = null;
        mLatestSummary = null;
    }

    public void applyTheme(boolean isDarkMode) {
        mIsDarkMode = isDarkMode;
        setBackgroundColor(AcidTheme.takeoverBg(isDarkMode));
    }

    /**
     * Synchronizes banner visibility, mode presentation, and typography.
     *
     * @return {@code true} when root banner visibility changed so outline overlays can re-measure.
     */
    public boolean render(
        int controlMode,
        boolean agentRunning,
        boolean isDrivingCurrentTab,
        String takeoverReason,
        boolean isDarkMode
    ) {
        if (mIsDarkMode != isDarkMode) {
            applyTheme(isDarkMode);
        }

        if (controlMode != AgentOverlayView.MODE_TAKEOVER_REQUESTED) {
            mTakeoverExpanded = false;
            mLastNormalizedTakeoverReason = "";
        } else {
            String normalizedReason = normalizeMultilineReason(takeoverReason);
            if (!normalizedReason.equals(mLastNormalizedTakeoverReason)) {
                mTakeoverExpanded = false;
                mLastNormalizedTakeoverReason = normalizedReason;
            }
        }
        mLastControlMode = controlMode;
        mLastAgentRunning = agentRunning;
        mLastIsDrivingCurrentTab = isDrivingCurrentTab;
        mLastRawTakeoverReason = takeoverReason != null ? takeoverReason : "";

        BannerPresentation spec = resolvePresentation(
            controlMode,
            agentRunning,
            isDrivingCurrentTab,
            mLastRawTakeoverReason,
            mLatestSummary,
            mTakeoverExpanded,
            isDarkMode
        );

        int desiredVisibility = spec.visible ? View.VISIBLE : View.GONE;
        boolean visibilityChanged = (getVisibility() != desiredVisibility);
        if (visibilityChanged) {
            setVisibility(desiredVisibility);
        }
        if (!spec.visible) {
            return visibilityChanged;
        }

        mIconView.setIcon(spec.iconId);
        mIconView.setColor(spec.bannerColor);

        if (spec.prefixText.isEmpty()) {
            if (mTvPrefix.getVisibility() != View.GONE) {
                mTvPrefix.setVisibility(View.GONE);
            }
        } else {
            if (mTvPrefix.getVisibility() != View.VISIBLE) {
                mTvPrefix.setVisibility(View.VISIBLE);
            }
            CharSequence curPrefix = mTvPrefix.getText();
            if (curPrefix == null || !spec.prefixText.contentEquals(curPrefix)) {
                mTvPrefix.setText(spec.prefixText);
            }
            if (mTvPrefix.getCurrentTextColor() != spec.bannerColor) {
                mTvPrefix.setTextColor(spec.bannerColor);
            }
        }

        ViewGroup.LayoutParams rawLp = mTvStatus.getLayoutParams();
        if (rawLp instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams statusLp = (LinearLayout.LayoutParams) rawLp;
            int desiredLeftMargin = spec.prefixText.isEmpty() ? AcidTheme.dp(getContext(), 6) : 0;
            if (statusLp.leftMargin != desiredLeftMargin) {
                statusLp.leftMargin = desiredLeftMargin;
                mTvStatus.setLayoutParams(statusLp);
            }
        }

        if (!mHasAppliedLineConfig || mAppliedSingleLine != spec.singleLine
                || mAppliedMaxLines != spec.maxLines
                || mAppliedEllipsizeMode != spec.ellipsizeMode) {
            mHasAppliedLineConfig = true;
            mAppliedSingleLine = spec.singleLine;
            mAppliedMaxLines = spec.maxLines;
            mAppliedEllipsizeMode = spec.ellipsizeMode;
            mTvStatus.setSingleLine(spec.singleLine);
            if (!spec.singleLine) {
                mTvStatus.setMaxLines(spec.maxLines);
            }
            mTvStatus.setLineSpacing(0f, spec.singleLine ? 1.0f : 1.15f);
            mTvStatus.setEllipsize(toTruncateAt(spec.ellipsizeMode));
        }

        mTvStatus.setClickable(spec.statusClickable);
        CharSequence curStatus = mTvStatus.getText();
        if (curStatus == null || !spec.statusText.contentEquals(curStatus)) {
            mTvStatus.setText(spec.statusText);
        }
        if (mTvStatus.getCurrentTextColor() != spec.bannerColor) {
            mTvStatus.setTextColor(spec.bannerColor);
        }

        mBtnAction.update(spec.actionIconId, spec.actionLabel, spec.actionColor);
        return visibilityChanged;
    }

    private boolean isTakeoverStatusTruncated() {
        Layout layout = mTvStatus.getLayout();
        if (layout == null) return false;
        int lines = layout.getLineCount();
        if (lines <= 0) return false;
        return lines > TAKEOVER_COLLAPSED_MAX_LINES || layout.getEllipsisCount(lines - 1) > 0;
    }

    private static TextUtils.TruncateAt toTruncateAt(int ellipsizeMode) {
        if (ellipsizeMode == ELLIPSIZE_START) return TextUtils.TruncateAt.START;
        if (ellipsizeMode == ELLIPSIZE_NONE) return null;
        return TextUtils.TruncateAt.END;
    }
}
