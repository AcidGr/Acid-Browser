package com.acid.browser;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DSH-Style Floating Agent Conversation Window with live thinking blocks,
 * tool call cards, markdown responses, session management, and vertical drag repositioning.
 */
public class AgentFloatingView extends FrameLayout implements AcidOverlay {

    private static final String TAG = "AgentFloatingView";

    public interface AgentHostCallback {
        void onUnreadStateChanged(boolean hasUnread, boolean running, boolean pausedForTakeover);
        void onOpenModelSelector();
        void onOpenSessionsSheet();
        void onFloatingShown();
    }

    public interface HeightProvider {
        int getAvailableHeight();
    }

    private final AcidAgentLoop mAgentLoop;
    private final AgentHostCallback mHostCallback;
    private HeightProvider mHeightProvider;

    private LinearLayout mDrawerPanel;
    private LinearLayout mAgentHeaderBar;
    private View mHeaderDivider;
    private View mInputDivider;
    private TextView mTvAgentSessionTitle;
    private TextView mTvAgentLoopStatus;
    private ScrollView mChatScrollView;
    private LinearLayout mChatStreamContainer;
    private LinearLayout mAgentInputBar;
    private EditText mEtAgentTaskInput;
    private AcidIcons.BadgeButton mBtnAgentSendStop;
    private AcidIcons.BadgeButton mBtnSessions;
    private AcidIcons.BadgeButton mBtnNewSession;
    private View mBtnModelConfig;
    private AcidIcons.SvgIconView mIconModelConfig;
    private AcidIcons.SvgIconView mIconCloseDrawer;
    private LinearLayout mContextUsagePill;
    private TextView mTvContextUsageText;
    private int mLastTotalTokens = 0;
    private int mLastPromptTokens = 0;
    private int mLastCachedTokens = 0;
    private final Map<String, ToolCardHolder> mToolCardHolders = new HashMap<String, ToolCardHolder>();
    private final Map<String, ThinkingViewHolder> mThinkingHolders = new HashMap<String, ThinkingViewHolder>();
    private final Map<String, AssistantViewHolder> mAssistantHolders = new HashMap<String, AssistantViewHolder>();

    private static final int INITIAL_RECENT_ITEMS_COUNT = 15;
    private static final int LOAD_MORE_BATCH_SIZE = 25;
    private int mRenderedStartIndex = 0;
    private AcidSessionStore.AgentSession mCurrentRenderedSession = null;
    private View mLoadEarlierHeaderCard = null;
    private TextView mTvLoadEarlierSummary = null;

    private float mUserTargetY = -1;
    private boolean mHasUnreadAgentMessage = false;
    private boolean mIsDarkMode = false;

    public AgentFloatingView(Context context, AcidAgentLoop agentLoop, AgentHostCallback hostCallback) {
        super(context);
        mAgentLoop = agentLoop;
        mHostCallback = hostCallback;
        setVisibility(View.GONE);

        // 1. Build Floating Inspector Panel
        mDrawerPanel = buildInspectorDrawer();
        FrameLayout.LayoutParams dLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, AcidTheme.dp(context, 480), Gravity.TOP | Gravity.CENTER_HORIZONTAL
        );
        int padH = AcidTheme.dp(context, 10);
        dLp.leftMargin = padH;
        dLp.rightMargin = padH;
        addView(mDrawerPanel, dLp);

        applyTheme(false);
    }

    public void setHeightProvider(HeightProvider provider) {
        mHeightProvider = provider;
    }

    @Override
    public boolean handleBackPressed() {
        if (isShowing()) {
            dismiss();
            return true;
        }
        return false;
    }

    public boolean isShowing() {
        return getVisibility() == View.VISIBLE;
    }

    @Override
    public void show() {
        mHasUnreadAgentMessage = false;
        if (mHostCallback != null) {
            mHostCallback.onUnreadStateChanged(
                false,
                mAgentLoop != null && mAgentLoop.isRunning(),
                mAgentLoop != null && mAgentLoop.isPausedForTakeover()
            );
            mHostCallback.onFloatingShown();
        }
        setVisibility(View.VISIBLE);
        bringToFront();
        updateFloatingWindowPosition(false);
        post(new Runnable() {
            @Override
            public void run() {
                updateFloatingWindowPosition(false);
                scrollChatToBottom();
            }
        });
    }

    @Override
    public void dismiss() {
        hideKeyboard();
        setVisibility(View.GONE);
    }

    public void toggle() {
        if (isShowing()) {
            dismiss();
        } else {
            show();
        }
    }

    public boolean hasUnread() {
        return mHasUnreadAgentMessage;
    }

    public void setHasUnread(boolean hasUnread) {
        mHasUnreadAgentMessage = hasUnread;
        if (mHostCallback != null) {
            mHostCallback.onUnreadStateChanged(
                mHasUnreadAgentMessage,
                mAgentLoop != null && mAgentLoop.isRunning(),
                mAgentLoop != null && mAgentLoop.isPausedForTakeover()
            );
        }
    }

    public void updateFloatingWindowPosition(boolean animated) {
        if (mDrawerPanel == null) return;
        int centerH = mHeightProvider != null ? mHeightProvider.getAvailableHeight() : getHeight();
        if (centerH <= 0) return;

        Context ctx = getContext();
        int topPadding = AcidTheme.dp(ctx, 8);
        int bottomPadding = AcidTheme.dp(ctx, 8);
        int minY = topPadding;
        int minCardH = AcidTheme.dp(ctx, 180);
        int defaultCardH = Math.min(AcidTheme.dp(ctx, 480), Math.max(minCardH, Math.round(centerH * 0.65f)));

        final float finalY;
        final int targetCardH;

        if (mUserTargetY < 0) {
            // Default: Resting comfortably at bottom
            targetCardH = defaultCardH;
            finalY = Math.max(minY, centerH - targetCardH - bottomPadding);
        } else {
            // User explicitly anchored card top:
            // Keep top anchored, and stretch bottom all the way to centerH - bottomPadding!
            // When IME pops up, centerH decreases; card height smoothly adapts with zero bottom gap.
            float maxAllowedY = Math.max(minY, centerH - minCardH - bottomPadding);
            finalY = Math.max(minY, Math.min(mUserTargetY, maxAllowedY));
            targetCardH = Math.max(minCardH, Math.round((centerH - bottomPadding) - finalY));
        }

        ViewGroup.LayoutParams lp = mDrawerPanel.getLayoutParams();
        if (lp != null && lp.height != targetCardH) {
            lp.height = targetCardH;
            mDrawerPanel.setLayoutParams(lp);
        }

        if (animated && Build.VERSION.SDK_INT >= 12) {
            mDrawerPanel.animate()
                .translationY(finalY)
                .setDuration(180)
                .start();
        } else {
            mDrawerPanel.setTranslationY(finalY);
        }
    }

    private LinearLayout buildInspectorDrawer() {
        final Context ctx = getContext();
        final LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        int floatBg = AcidTheme.panelBg(mIsDarkMode);
        int floatBorder = AcidTheme.panelBorder(mIsDarkMode);
        panel.setBackground(AcidTheme.cardBorderBg(ctx, floatBg, floatBorder, 2, AcidTheme.RADIUS_SHEET));
        if (Build.VERSION.SDK_INT >= 21) {
            panel.setElevation(AcidTheme.dp(ctx, 16));
        }
        panel.setClickable(true);

        // 1. Header Bar
        LinearLayout headerRow = new LinearLayout(ctx);
        mAgentHeaderBar = headerRow;
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        int padH = AcidTheme.dp(ctx, 12);
        int padV = AcidTheme.dp(ctx, 8);
        headerRow.setPadding(padH, padV, AcidTheme.dp(ctx, 8), padV);

        headerRow.setOnTouchListener(new OnTouchListener() {
            private float downRawY;
            private float startTransY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawY = event.getRawY();
                        startTransY = panel.getTranslationY();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = event.getRawY() - downRawY;
                        if (Math.abs(dy) > AcidTheme.dp(ctx, 6)) {
                            dragging = true;
                        }
                        if (dragging) {
                            int centerH = mHeightProvider != null ? mHeightProvider.getAvailableHeight() : getHeight();
                            int topP = AcidTheme.dp(ctx, 8);
                            int botP = AcidTheme.dp(ctx, 8);
                            int minH = AcidTheme.dp(ctx, 180);
                            float minY = topP;
                            float maxY = Math.max(minY, centerH - minH - botP);
                            float nextY = Math.min(maxY, Math.max(minY, startTransY + dy));
                            panel.setTranslationY(nextY);

                            int stretchH = Math.max(minH, Math.round((centerH - botP) - nextY));
                            ViewGroup.LayoutParams lp = panel.getLayoutParams();
                            if (lp != null && lp.height != stretchH) {
                                lp.height = stretchH;
                                panel.setLayoutParams(lp);
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragging) {
                            if (mHostCallback != null) {
                                mHostCallback.onOpenSessionsSheet();
                            }
                        } else {
                            mUserTargetY = panel.getTranslationY();
                            updateFloatingWindowPosition(false);
                        }
                        return true;
                }
                return false;
            }
        });

        AcidIcons.SvgIconView agentIcon = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_AGENT, AcidTheme.LIGHT_ACCENT, 18);
        headerRow.addView(agentIcon);

        LinearLayout titleCol = new LinearLayout(ctx);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleColLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleColLp.leftMargin = AcidTheme.dp(ctx, 8);
        titleColLp.rightMargin = AcidTheme.dp(ctx, 6);

        mTvAgentSessionTitle = new TextView(ctx);
        mTvAgentSessionTitle.setText("新对话");
        mTvAgentSessionTitle.setTextColor(AcidTheme.LIGHT_TEXT);
        mTvAgentSessionTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_ITEM);
        mTvAgentSessionTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mTvAgentSessionTitle.setSingleLine(true);
        mTvAgentSessionTitle.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(mTvAgentSessionTitle);

        mTvAgentLoopStatus = new TextView(ctx);
        mTvAgentLoopStatus.setText("DeepSeek / DeepSeek-V3 (Chat)");
        mTvAgentLoopStatus.setTextColor(AcidTheme.LIGHT_MUTED);
        mTvAgentLoopStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BADGE);
        mTvAgentLoopStatus.setSingleLine(true);
        mTvAgentLoopStatus.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(mTvAgentLoopStatus);

        headerRow.addView(titleCol, titleColLp);

        // Button: Sessions
        mBtnSessions = new AcidIcons.BadgeButton(
            ctx, AcidIcons.ICON_CHAT_LIST, "会话", AcidTheme.COLOR_BLUE, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mHostCallback != null) {
                        mHostCallback.onOpenSessionsSheet();
                    }
                }
            }
        );
        headerRow.addView(mBtnSessions);

        // Button: New Session (+)
        mBtnNewSession = new AcidIcons.BadgeButton(
            ctx, AcidIcons.ICON_PLUS, "新对话", AcidTheme.LIGHT_ACCENT, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mAgentLoop != null) {
                        mAgentLoop.startNewSession();
                    }
                }
            }
        );
        headerRow.addView(mBtnNewSession);

        // Button: Model Selector
        LinearLayout modelBtnBox = AcidIcons.createToolbarIconBtn(ctx, AcidIcons.ICON_MODEL, AcidTheme.LIGHT_MUTED, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mHostCallback != null) {
                    mHostCallback.onOpenModelSelector();
                }
            }
        });
        mBtnModelConfig = modelBtnBox;
        mIconModelConfig = (AcidIcons.SvgIconView) modelBtnBox.getChildAt(0);
        headerRow.addView(mBtnModelConfig);

        // Context Usage & Cache Hit Rate Pill (Displayed when Agent is running/working)
        mContextUsagePill = new LinearLayout(ctx);
        mContextUsagePill.setOrientation(LinearLayout.HORIZONTAL);
        mContextUsagePill.setGravity(Gravity.CENTER_VERTICAL);
        int pillPadH = AcidTheme.dp(ctx, 8);
        int pillPadV = AcidTheme.dp(ctx, 4);
        mContextUsagePill.setPadding(pillPadH, pillPadV, pillPadH, pillPadV);
        mContextUsagePill.setBackground(AcidTheme.cardBorderBg(
            ctx, AcidTheme.softTint(AcidTheme.COLOR_BLUE, 32), AcidTheme.COLOR_BLUE, 1, AcidTheme.RADIUS_CONTROL
        ));
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.rightMargin = AcidTheme.dp(ctx, 6);
        mContextUsagePill.setLayoutParams(pillLp);
        mContextUsagePill.setVisibility(View.GONE);

        mTvContextUsageText = new TextView(ctx);
        mTvContextUsageText.setText("0k/1M (0.0%) · 缓存 0%");
        mTvContextUsageText.setTextColor(AcidTheme.COLOR_BLUE);
        mTvContextUsageText.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BADGE);
        mTvContextUsageText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mContextUsagePill.addView(mTvContextUsageText);

        mContextUsagePill.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                float pct = (mLastTotalTokens / 1000000.0f) * 100.0f;
                float hitRate = mLastPromptTokens > 0 ? ((mLastCachedTokens * 100.0f) / mLastPromptTokens) : 0f;
                AcidFeedback.info(
                    getContext(),
                    "上下文窗口: " + mLastTotalTokens + " / 1,000,000 (" + String.format(java.util.Locale.US, "%.2f%%", pct) + ")\n提示词缓存命中: " + String.format(java.util.Locale.US, "%.1f%%", hitRate) + " (" + mLastCachedTokens + " tokens)"
                );
            }
        });
        headerRow.addView(mContextUsagePill);

        // Button: Close
        LinearLayout btnCloseDrawer = AcidIcons.createToolbarIconBtn(ctx, AcidIcons.ICON_CLOSE, AcidTheme.LIGHT_MUTED, new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        mIconCloseDrawer = (AcidIcons.SvgIconView) btnCloseDrawer.getChildAt(0);
        headerRow.addView(btnCloseDrawer);

        panel.addView(headerRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mHeaderDivider = new View(ctx);
        mHeaderDivider.setBackgroundColor(AcidTheme.LIGHT_BORDER);
        panel.addView(mHeaderDivider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AcidTheme.dp(ctx, 1)));

        // 2. Chat Stream ScrollView
        mChatScrollView = new ScrollView(ctx);
        mChatStreamContainer = new LinearLayout(ctx);
        mChatStreamContainer.setOrientation(LinearLayout.VERTICAL);
        int padC = AcidTheme.dp(ctx, 12);
        int padCv = AcidTheme.dp(ctx, 10);
        mChatStreamContainer.setPadding(padC, padCv, padC, padCv);
        mChatScrollView.addView(mChatStreamContainer, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        panel.addView(mChatScrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mInputDivider = new View(ctx);
        mInputDivider.setBackgroundColor(AcidTheme.LIGHT_BORDER);
        panel.addView(mInputDivider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AcidTheme.dp(ctx, 1)));

        // 3. Input Bar
        mAgentInputBar = new LinearLayout(ctx);
        mAgentInputBar.setOrientation(LinearLayout.HORIZONTAL);
        mAgentInputBar.setGravity(Gravity.CENTER_VERTICAL);
        mAgentInputBar.setPadding(AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 6), AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 6));

        mEtAgentTaskInput = new EditText(ctx);
        mEtAgentTaskInput.setHint("向 Acid Agent 发送网页或沙盒任务...");
        mEtAgentTaskInput.setHintTextColor(AcidTheme.LIGHT_MUTED);
        mEtAgentTaskInput.setTextColor(AcidTheme.LIGHT_TEXT);
        mEtAgentTaskInput.setSingleLine(true);
        mEtAgentTaskInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
        mEtAgentTaskInput.setPadding(AcidTheme.dp(ctx, 12), AcidTheme.dp(ctx, 6), AcidTheme.dp(ctx, 12), AcidTheme.dp(ctx, 6));
        mEtAgentTaskInput.setBackground(AcidTheme.pillBg(ctx, AcidTheme.LIGHT_SEARCH_BG, AcidTheme.LIGHT_SEARCH_BORDER, AcidTheme.RADIUS_PILL));
        mEtAgentTaskInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        mEtAgentTaskInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    triggerAgentSubmitOrStop();
                    return true;
                }
                return false;
            }
        });
        LinearLayout.LayoutParams promptInputLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        promptInputLp.rightMargin = AcidTheme.dp(ctx, 6);
        mAgentInputBar.addView(mEtAgentTaskInput, promptInputLp);

        mBtnAgentSendStop = new AcidIcons.BadgeButton(ctx, AcidIcons.ICON_SEND, "发送", AcidTheme.LIGHT_ACCENT, new OnClickListener() {
            @Override
            public void onClick(View v) {
                triggerAgentSubmitOrStop();
            }
        });
        mAgentInputBar.addView(mBtnAgentSendStop);

        panel.addView(mAgentInputBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return panel;
    }

    public void renderSessionChatStream(AcidSessionStore.AgentSession session) {
        if (mChatStreamContainer == null) return;
        mChatStreamContainer.removeAllViews();
        mToolCardHolders.clear();
        mThinkingHolders.clear();
        mAssistantHolders.clear();
        mLoadEarlierHeaderCard = null;
        mTvLoadEarlierSummary = null;
        mCurrentRenderedSession = session;

        if (session != null && mTvAgentSessionTitle != null) {
            mTvAgentSessionTitle.setText(session.title);
        }
        if (mTvAgentLoopStatus != null && mAgentLoop != null && !mAgentLoop.isRunning()) {
            mTvAgentLoopStatus.setText(mAgentLoop.getModelStatusDisplay());
        }
        if (session != null) {
            int tt = session.lastTotalTokens > 0 ? session.lastTotalTokens : (mAgentLoop != null ? mAgentLoop.computeEstimatedTokens(session) : 0);
            updateContextUsage(tt, session.lastPromptTokens, session.lastCachedTokens);
        }

        if (session == null || session.uiItems.isEmpty()) {
            mRenderedStartIndex = 0;
            addEmptyChatWelcomeCard();
            return;
        }

        int total = session.uiItems.size();
        if (total <= INITIAL_RECENT_ITEMS_COUNT) {
            mRenderedStartIndex = 0;
            for (int i = 0; i < total; i++) {
                mChatStreamContainer.addView(createUiMessageItemView(session.uiItems.get(i)));
            }
        } else {
            mRenderedStartIndex = total - INITIAL_RECENT_ITEMS_COUNT;
            mChatStreamContainer.addView(buildLoadEarlierCard(mRenderedStartIndex));
            for (int i = mRenderedStartIndex; i < total; i++) {
                mChatStreamContainer.addView(createUiMessageItemView(session.uiItems.get(i)));
            }
        }
        scrollChatToBottom();
    }

    private View buildLoadEarlierCard(final int foldedCount) {
        Context ctx = getContext();
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int bg = AcidTheme.subtle(mIsDarkMode);
        int border = AcidTheme.subtleBorder(mIsDarkMode);
        int textCol = AcidTheme.accent(mIsDarkMode);
        int mutedCol = AcidTheme.muted(mIsDarkMode);

        card.setBackground(AcidTheme.cardBorderBg(ctx, bg, border, 1, AcidTheme.RADIUS_CONTROL));
        int padH = AcidTheme.dp(ctx, 12);
        int padV = AcidTheme.dp(ctx, 7);
        card.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = AcidTheme.dp(ctx, 2);
        lp.bottomMargin = AcidTheme.dp(ctx, 8);
        card.setLayoutParams(lp);

        AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_HISTORY, textCol, 15);
        card.addView(icon);

        mTvLoadEarlierSummary = new TextView(ctx);
        mTvLoadEarlierSummary.setText("已折叠 " + foldedCount + " 条更早历史记录 · 点击加载更多");
        mTvLoadEarlierSummary.setTextColor(mutedCol);
        mTvLoadEarlierSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_FIELD_LABEL);
        mTvLoadEarlierSummary.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = AcidTheme.dp(ctx, 8);
        card.addView(mTvLoadEarlierSummary, tLp);

        TextView btnAll = new TextView(ctx);
        btnAll.setText("全部展开");
        btnAll.setTextColor(AcidTheme.COLOR_BLUE);
        btnAll.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BADGE);
        int btnPadH = AcidTheme.dp(ctx, 6);
        int btnPadV = AcidTheme.dp(ctx, 2);
        btnAll.setPadding(btnPadH, btnPadV, btnPadH, btnPadV);
        btnAll.setBackground(AcidTheme.pillBg(
            ctx, AcidTheme.softTint(AcidTheme.COLOR_BLUE, 24), AcidTheme.COLOR_BLUE, AcidTheme.RADIUS_BADGE
        ));
        btnAll.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                loadEarlierMessages(true);
            }
        });
        card.addView(btnAll);

        card.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                loadEarlierMessages(false);
            }
        });

        mLoadEarlierHeaderCard = card;
        return card;
    }

    private void loadEarlierMessages(boolean loadAll) {
        if (mCurrentRenderedSession == null || mRenderedStartIndex <= 0) return;
        int nextStart = loadAll ? 0 : Math.max(0, mRenderedStartIndex - LOAD_MORE_BATCH_SIZE);
        int loadCount = mRenderedStartIndex - nextStart;
        if (loadCount <= 0) return;

        List<View> viewsToInsert = new ArrayList<View>();
        for (int i = nextStart; i < mRenderedStartIndex; i++) {
            AcidSessionStore.UiMessageItem item = mCurrentRenderedSession.uiItems.get(i);
            viewsToInsert.add(createUiMessageItemView(item));
        }

        int insertIndex = (mLoadEarlierHeaderCard != null) ? 1 : 0;
        for (View v : viewsToInsert) {
            mChatStreamContainer.addView(v, insertIndex++);
        }

        mRenderedStartIndex = nextStart;

        if (mRenderedStartIndex == 0) {
            if (mLoadEarlierHeaderCard != null) {
                mChatStreamContainer.removeView(mLoadEarlierHeaderCard);
                mLoadEarlierHeaderCard = null;
                mTvLoadEarlierSummary = null;
            }
        } else if (mTvLoadEarlierSummary != null) {
            mTvLoadEarlierSummary.setText("已折叠 " + mRenderedStartIndex + " 条更早历史记录 · 点击加载更多");
        }
    }

    public void updateToolCard(AcidSessionStore.UiMessageItem item) {
        updateUiMessageItem(item);
    }

    public void updateUiMessageItem(AcidSessionStore.UiMessageItem item) {
        if (item == null) return;
        String type = item.type != null ? item.type : "";
        if (AcidSessionStore.TYPE_TOOL.equals(type)) {
            if (item.toolCallId != null) {
                ToolCardHolder holder = mToolCardHolders.get(item.toolCallId);
                if (holder != null) {
                    holder.bind(item, mIsDarkMode);
                }
            }
        } else if (AcidSessionStore.TYPE_THINKING.equals(type)) {
            if (item.id != null) {
                ThinkingViewHolder holder = mThinkingHolders.get(item.id);
                if (holder != null) {
                    holder.update(item, mIsDarkMode);
                }
            }
        } else if (AcidSessionStore.TYPE_ASSISTANT.equals(type)) {
            if (item.id != null) {
                AssistantViewHolder holder = mAssistantHolders.get(item.id);
                if (holder != null) {
                    holder.update(item, mIsDarkMode);
                }
            }
        }
        scrollChatToBottom();
    }

    private void addEmptyChatWelcomeCard() {
        Context ctx = getContext();
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        int cardBg = AcidTheme.raised(mIsDarkMode);
        int borderCol = AcidTheme.raisedBorder(mIsDarkMode);
        int textCol = AcidTheme.text(mIsDarkMode);
        int mutedCol = AcidTheme.muted(mIsDarkMode);

        card.setBackground(AcidTheme.cardBorderBg(ctx, cardBg, borderCol, 1, AcidTheme.RADIUS_CARD));
        int pad = AcidTheme.dp(ctx, 14);
        int padV = AcidTheme.dp(ctx, 12);
        card.setPadding(pad, padV, pad, padV);

        TextView title = new TextView(ctx);
        title.setText("Acid Agent 自主智能体");
        title.setTextColor(textCol);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_HERO);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        TextView desc = new TextView(ctx);
        desc.setText("内置 browser (网页感知与操控)、read、write、edit、bash 五大沙盒工具。您可以直接发送指令让它跨网页调研、填写表单、编写油猴脚本或生成本地数据报告。");
        desc.setTextColor(mutedCol);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
        desc.setPadding(0, AcidTheme.dp(ctx, 4), 0, AcidTheme.dp(ctx, 8));
        card.addView(desc);

        // 3 Quick Start chips
        String[] samples = new String[]{
            "打开 Hacker News 总结前 3 条科技新闻",
            "在当前网页提取所有带链接的标题",
            "在沙盒中编写一个解除网页复制限制的 userscript"
        };
        for (final String sample : samples) {
            TextView chip = new TextView(ctx);
            chip.setText("💡 " + sample);
            chip.setTextColor(textCol);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
            int pPad = AcidTheme.dp(ctx, 8);
            chip.setPadding(pPad, pPad, pPad, pPad);
            chip.setBackground(AcidTheme.pillBg(ctx, mIsDarkMode ? AcidTheme.DARK_BG : AcidTheme.LIGHT_SEARCH_BG, borderCol, AcidTheme.RADIUS_CONTROL));
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            cLp.topMargin = AcidTheme.dp(ctx, 4);
            chip.setLayoutParams(cLp);
            chip.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mEtAgentTaskInput.setText(sample);
                    triggerAgentSubmitOrStop();
                }
            });
            card.addView(chip);
        }

        mChatStreamContainer.addView(card);
    }

    public void appendUiMessageItemView(AcidSessionStore.UiMessageItem item, boolean autoScroll) {
        if (mChatStreamContainer == null || item == null) return;
        if (mChatStreamContainer.getChildCount() == 1 && mAgentLoop != null &&
            mAgentLoop.getActiveSession() != null && mAgentLoop.getActiveSession().uiItems.size() == 1) {
            mChatStreamContainer.removeAllViews();
        }

        mChatStreamContainer.addView(createUiMessageItemView(item));

        if (autoScroll) {
            scrollChatToBottom();
        }
    }

    private View createUiMessageItemView(AcidSessionStore.UiMessageItem item) {
        String type = item.type != null ? item.type : AcidSessionStore.TYPE_ASSISTANT;
        if (AcidSessionStore.TYPE_USER.equals(type)) {
            return buildUserBubbleView(item);
        } else if (AcidSessionStore.TYPE_THINKING.equals(type)) {
            ThinkingViewHolder holder = buildThinkingCardViewHolder(item);
            if (item.id != null && !item.id.isEmpty()) {
                mThinkingHolders.put(item.id, holder);
            }
            return holder.rootView;
        } else if (AcidSessionStore.TYPE_TOOL.equals(type)) {
            ToolCardHolder holder = new ToolCardHolder(getContext());
            holder.bind(item, mIsDarkMode);
            if (item.toolCallId != null && !item.toolCallId.isEmpty()) {
                mToolCardHolders.put(item.toolCallId, holder);
            }
            return holder.rootView;
        } else if (AcidSessionStore.TYPE_ERROR.equals(type)) {
            return buildErrorBubbleView(item);
        } else {
            AssistantViewHolder holder = buildAssistantViewHolder(item);
            if (item.id != null && !item.id.isEmpty()) {
                mAssistantHolders.put(item.id, holder);
            }
            return holder.cardView;
        }
    }

    private View buildUserBubbleView(AcidSessionStore.UiMessageItem item) {
        Context ctx = getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rowLp.topMargin = AcidTheme.dp(ctx, 6);
        rowLp.bottomMargin = AcidTheme.dp(ctx, 6);
        row.setLayoutParams(rowLp);

        TextView bubble = new TextView(ctx);
        bubble.setText(item.text);
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
        int padH = AcidTheme.dp(ctx, 12);
        int padV = AcidTheme.dp(ctx, 8);
        bubble.setPadding(padH, padV, padH, padV);
        bubble.setBackground(AcidTheme.pillBg(ctx, AcidTheme.COLOR_BLUE, 0, AcidTheme.RADIUS_CARD));
        bubble.setMaxWidth(AcidTheme.dp(ctx, 280));
        bubble.setTextIsSelectable(true);

        row.addView(bubble, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return row;
    }

    private ThinkingViewHolder buildThinkingCardViewHolder(AcidSessionStore.UiMessageItem item) {
        Context ctx = getContext();
        final LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        int bg = AcidTheme.thinkingBg(mIsDarkMode);
        int border = AcidTheme.thinkingBorder(mIsDarkMode);
        int muted = AcidTheme.muted(mIsDarkMode);

        card.setBackground(AcidTheme.cardBorderBg(ctx, bg, border, 1, AcidTheme.RADIUS_CONTROL));
        int padH = AcidTheme.dp(ctx, 10);
        int padV = AcidTheme.dp(ctx, 6);
        card.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardLp.bottomMargin = AcidTheme.dp(ctx, 5);
        card.setLayoutParams(cardLp);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_AGENT, muted, 13);
        head.addView(icon);

        final TextView title = new TextView(ctx);
        String preview = item.text != null ? item.text.replace("\n", " ").trim() : "";
        if ("null".equals(preview)) preview = "";
        if (preview.length() > 36) preview = preview.substring(0, 36) + "...";
        title.setText(item.isRunning ? ("思考中 · " + preview) : ("思考过程 · " + preview));
        title.setTextColor(muted);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = AcidTheme.dp(ctx, 6);
        head.addView(title, tLp);

        final AcidIcons.SvgIconView chevron = new AcidIcons.SvgIconView(
            ctx,
            item.isRunning ? AcidIcons.ICON_CHEVRON_UP : AcidIcons.ICON_CHEVRON_DOWN,
            muted,
            14
        );
        head.addView(chevron);
        card.addView(head);

        String bodyText = item.text != null ? item.text : "";
        if ("null".equals(bodyText)) bodyText = "";
        final TextView body = new TextView(ctx);
        body.setText(bodyText);
        body.setTextColor(muted);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_FIELD_LABEL);
        body.setPadding(0, AcidTheme.dp(ctx, 6), 0, AcidTheme.dp(ctx, 2));
        body.setTextIsSelectable(true);
        body.setVisibility(item.isRunning ? View.VISIBLE : View.GONE);
        card.addView(body);

        if (!item.isRunning && (bodyText.trim().isEmpty() || "null".equals(bodyText.trim()))) {
            card.setVisibility(View.GONE);
        }

        head.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean expand = (body.getVisibility() != View.VISIBLE);
                body.setVisibility(expand ? View.VISIBLE : View.GONE);
                chevron.setIcon(expand ? AcidIcons.ICON_CHEVRON_UP : AcidIcons.ICON_CHEVRON_DOWN);
            }
        });

        return new ThinkingViewHolder(card, title, body, chevron);
    }

    private AssistantViewHolder buildAssistantViewHolder(AcidSessionStore.UiMessageItem item) {
        Context ctx = getContext();
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        int bg = AcidTheme.raised(mIsDarkMode);
        int border = AcidTheme.raisedBorder(mIsDarkMode);

        card.setBackground(AcidTheme.cardBorderBg(ctx, bg, border, 1, AcidTheme.RADIUS_CARD));
        int padH = AcidTheme.dp(ctx, 12);
        int padTop = AcidTheme.dp(ctx, 10);
        int padBot = AcidTheme.dp(ctx, 8);
        card.setPadding(padH, padTop, padH, padBot);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = AcidTheme.dp(ctx, 4);
        lp.bottomMargin = AcidTheme.dp(ctx, 6);
        card.setLayoutParams(lp);

        TextView streamingTv = new TextView(ctx);
        streamingTv.setTextColor(AcidTheme.markdownText(mIsDarkMode));
        streamingTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
        streamingTv.setLineSpacing(0f, 1.25f);
        streamingTv.setTextIsSelectable(true);
        streamingTv.setText(item.text != null ? item.text : "");

        if (item.isRunning) {
            card.addView(streamingTv);
            return new AssistantViewHolder(card, streamingTv, false);
        } else {
            AcidMarkdownView.renderInto(card, item.text != null ? item.text : "", mIsDarkMode);
            return new AssistantViewHolder(card, streamingTv, true);
        }
    }

    private View buildErrorBubbleView(AcidSessionStore.UiMessageItem item) {
        Context ctx = getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int bg = AcidTheme.errorBg(mIsDarkMode);
        int border = AcidTheme.COLOR_RED;
        row.setBackground(AcidTheme.cardBorderBg(ctx, bg, border, 1, AcidTheme.RADIUS_CONTROL));
        int pad = AcidTheme.dp(ctx, 10);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rLp.topMargin = AcidTheme.dp(ctx, 6);
        rLp.bottomMargin = AcidTheme.dp(ctx, 6);
        row.setLayoutParams(rLp);

        AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_CLOSE, AcidTheme.COLOR_RED, 16);
        row.addView(icon);

        TextView errTv = new TextView(ctx);
        errTv.setText(item.text);
        errTv.setTextColor(AcidTheme.COLOR_RED);
        errTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BODY);
        errTv.setTextIsSelectable(true);
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        eLp.leftMargin = AcidTheme.dp(ctx, 8);
        row.addView(errTv, eLp);

        return row;
    }

    private static class ThinkingViewHolder {
        public final View rootView;
        private final TextView mTitleView;
        private final TextView mBodyView;
        private final AcidIcons.SvgIconView mChevron;

        public ThinkingViewHolder(View root, TextView title, TextView body, AcidIcons.SvgIconView chevron) {
            this.rootView = root;
            this.mTitleView = title;
            this.mBodyView = body;
            this.mChevron = chevron;
        }

        public void update(AcidSessionStore.UiMessageItem item, boolean isDarkMode) {
            String text = item.text != null ? item.text : "";
            if (text.trim().isEmpty() || "null".equals(text.trim())) {
                rootView.setVisibility(View.GONE);
                return;
            }
            rootView.setVisibility(View.VISIBLE);
            mBodyView.setText(text);
            String preview = text.replace("\n", " ").trim();
            if (preview.length() > 36) preview = preview.substring(0, 36) + "...";
            if (item.isRunning) {
                mTitleView.setText("思考中 · " + preview);
                if (mBodyView.getVisibility() != View.VISIBLE) {
                    mBodyView.setVisibility(View.VISIBLE);
                    mChevron.setIcon(AcidIcons.ICON_CHEVRON_UP);
                }
            } else {
                mTitleView.setText("思考过程 · " + preview);
            }
        }
    }

    private static class AssistantViewHolder {
        public final LinearLayout cardView;
        private final TextView mStreamingTextView;
        private boolean mRenderedFinalMarkdown;

        public AssistantViewHolder(LinearLayout card, TextView streamingTv, boolean isFinal) {
            this.cardView = card;
            this.mStreamingTextView = streamingTv;
            this.mRenderedFinalMarkdown = isFinal;
        }

        public void update(AcidSessionStore.UiMessageItem item, boolean isDarkMode) {
            String text = item.text != null ? item.text : "";
            if (item.isRunning) {
                mRenderedFinalMarkdown = false;
                if (mStreamingTextView != null) {
                    mStreamingTextView.setText(text);
                    mStreamingTextView.setVisibility(View.VISIBLE);
                }
            } else {
                if (!mRenderedFinalMarkdown) {
                    mRenderedFinalMarkdown = true;
                    AcidMarkdownView.renderInto(cardView, text, isDarkMode);
                }
            }
        }
    }

    private class ToolCardHolder {
        public final LinearLayout rootView;
        private final AcidIcons.SvgIconView mToolIcon;
        private final TextView mTvToolName;
        private final TextView mTvSummary;
        private final TextView mTvStatusBadge;
        private final AcidIcons.SvgIconView mChevron;
        private final LinearLayout mDetailContainer;
        private final TextView mLblArgs;
        private final TextView mLblRes;
        private final TextView mTvArgsCode;
        private final TextView mTvResultCode;

        public ToolCardHolder(Context ctx) {
            rootView = new LinearLayout(ctx);
            rootView.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams rootLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rootLp.bottomMargin = AcidTheme.dp(ctx, 5);
            rootView.setLayoutParams(rootLp);

            LinearLayout header = new LinearLayout(ctx);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            int padH = AcidTheme.dp(ctx, 10);
            int padV = AcidTheme.dp(ctx, 7);
            header.setPadding(padH, padV, padH, padV);

            mToolIcon = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_GLOBE, AcidTheme.LIGHT_ACCENT, 14);
            header.addView(mToolIcon);

            mTvToolName = new TextView(ctx);
            mTvToolName.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            mTvToolName.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            nameLp.leftMargin = AcidTheme.dp(ctx, 5);
            header.addView(mTvToolName, nameLp);

            mTvSummary = new TextView(ctx);
            mTvSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
            mTvSummary.setSingleLine(true);
            mTvSummary.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            sumLp.leftMargin = AcidTheme.dp(ctx, 6);
            sumLp.rightMargin = AcidTheme.dp(ctx, 6);
            header.addView(mTvSummary, sumLp);

            mTvStatusBadge = new TextView(ctx);
            mTvStatusBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_MICRO);
            mTvStatusBadge.setTypeface(Typeface.DEFAULT_BOLD);
            int pPadH = AcidTheme.dp(ctx, 6);
            int pPadV = AcidTheme.dp(ctx, 2);
            mTvStatusBadge.setPadding(pPadH, pPadV, pPadH, pPadV);
            header.addView(mTvStatusBadge);

            mChevron = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_CHEVRON_DOWN, AcidTheme.LIGHT_MUTED, 14);
            LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(AcidTheme.dp(ctx, 14), AcidTheme.dp(ctx, 14));
            chLp.leftMargin = AcidTheme.dp(ctx, 4);
            header.addView(mChevron, chLp);

            rootView.addView(header);

            // Expandable detail box
            mDetailContainer = new LinearLayout(ctx);
            mDetailContainer.setOrientation(LinearLayout.VERTICAL);
            mDetailContainer.setPadding(AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 4), AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 8));
            mDetailContainer.setVisibility(View.GONE);

            mLblArgs = new TextView(ctx);
            mLblArgs.setText("调用参数 (Arguments)");
            mLblArgs.setTextColor(AcidTheme.LIGHT_MUTED);
            mLblArgs.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BADGE);
            mLblArgs.setTypeface(Typeface.DEFAULT_BOLD);
            mDetailContainer.addView(mLblArgs);

            mTvArgsCode = new TextView(ctx);
            mTvArgsCode.setTypeface(Typeface.MONOSPACE);
            mTvArgsCode.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BADGE);
            mTvArgsCode.setTextColor(AcidTheme.DARK_CODE_TEXT);
            mTvArgsCode.setPadding(AcidTheme.dp(ctx, 8), AcidTheme.dp(ctx, 6), AcidTheme.dp(ctx, 8), AcidTheme.dp(ctx, 6));
            mTvArgsCode.setTextIsSelectable(true);
            LinearLayout.LayoutParams argsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            argsLp.topMargin = AcidTheme.dp(ctx, 3);
            argsLp.bottomMargin = AcidTheme.dp(ctx, 6);
            mDetailContainer.addView(mTvArgsCode, argsLp);

            mLblRes = new TextView(ctx);
            mLblRes.setText("返回结果 (Output)");
            mLblRes.setTextColor(AcidTheme.LIGHT_MUTED);
            mLblRes.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BADGE);
            mLblRes.setTypeface(Typeface.DEFAULT_BOLD);
            mDetailContainer.addView(mLblRes);

            mTvResultCode = new TextView(ctx);
            mTvResultCode.setTypeface(Typeface.MONOSPACE);
            mTvResultCode.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BADGE);
            mTvResultCode.setTextColor(AcidTheme.COLOR_CODE_SUCCESS);
            mTvResultCode.setPadding(AcidTheme.dp(ctx, 8), AcidTheme.dp(ctx, 6), AcidTheme.dp(ctx, 8), AcidTheme.dp(ctx, 6));
            mTvResultCode.setTextIsSelectable(true);
            LinearLayout.LayoutParams resLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            resLp.topMargin = AcidTheme.dp(ctx, 3);
            mDetailContainer.addView(mTvResultCode, resLp);

            rootView.addView(mDetailContainer);

            header.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean expand = (mDetailContainer.getVisibility() != View.VISIBLE);
                    mDetailContainer.setVisibility(expand ? View.VISIBLE : View.GONE);
                    mChevron.setIcon(expand ? AcidIcons.ICON_CHEVRON_UP : AcidIcons.ICON_CHEVRON_DOWN);
                }
            });
        }

        public void bind(AcidSessionStore.UiMessageItem item, boolean dark) {
            Context ctx = getContext();
            int cardBg = AcidTheme.raised(dark);
            int borderCol = AcidTheme.raisedBorder(dark);
            int textCol = AcidTheme.text(dark);
            int mutedCol = AcidTheme.muted(dark);
            int accent = AcidTheme.accent(dark);

            rootView.setBackground(AcidTheme.cardBorderBg(ctx, cardBg, borderCol, 1, AcidTheme.RADIUS_CONTROL));

            String tName = item.toolName != null ? item.toolName : "tool";
            int iconId = AcidIcons.ICON_GLOBE;
            int toolColor = accent;
            if ("bash".equals(tName)) {
                iconId = AcidIcons.ICON_TERMINAL;
                toolColor = AcidTheme.COLOR_PURPLE;
            } else if ("read".equals(tName) || "write".equals(tName) || "edit".equals(tName)) {
                iconId = AcidIcons.ICON_FILE;
                toolColor = AcidTheme.COLOR_BLUE;
            }
            mToolIcon.setIcon(iconId);
            mToolIcon.setColor(toolColor);

            mTvToolName.setText(tName);
            mTvToolName.setTextColor(toolColor);

            mTvSummary.setText(item.toolSummary != null ? item.toolSummary : "");
            mTvSummary.setTextColor(textCol);

            if (item.isRunning) {
                mTvStatusBadge.setText("执行中");
                mTvStatusBadge.setTextColor(AcidTheme.COLOR_AMBER);
                mTvStatusBadge.setBackground(AcidTheme.pillBg(
                    ctx, AcidTheme.softTint(AcidTheme.COLOR_AMBER, 30), AcidTheme.COLOR_AMBER, AcidTheme.RADIUS_BADGE
                ));
            } else if (item.isError) {
                int errCol = AcidTheme.COLOR_RED;
                mTvStatusBadge.setText("失败");
                mTvStatusBadge.setTextColor(errCol);
                mTvStatusBadge.setBackground(AcidTheme.pillBg(
                    ctx, AcidTheme.softTint(errCol, 30), errCol, AcidTheme.RADIUS_BADGE
                ));
            } else {
                mTvStatusBadge.setText("完成");
                mTvStatusBadge.setTextColor(accent);
                mTvStatusBadge.setBackground(AcidTheme.pillBg(
                    ctx, AcidTheme.softTint(accent, 26), 0, AcidTheme.RADIUS_BADGE
                ));
            }

            mChevron.setColor(mutedCol);
            mLblArgs.setTextColor(mutedCol);
            mLblRes.setTextColor(mutedCol);
            mTvArgsCode.setBackground(AcidTheme.pillBg(ctx, AcidTheme.codeBlockBg(dark), AcidTheme.codeBlockBorder(dark), AcidTheme.RADIUS_BADGE));
            mTvResultCode.setBackground(AcidTheme.pillBg(ctx, AcidTheme.codeBlockBg(dark), AcidTheme.codeBlockBorder(dark), AcidTheme.RADIUS_BADGE));
            mTvArgsCode.setText(item.toolArgsJson != null ? item.toolArgsJson : "{}");
            String resText = item.toolResult != null ? item.toolResult : "";
            if (resText.length() > 3500) {
                resText = resText.substring(0, 3500) + "\n... [内容已截断，共 " + item.toolResult.length() + " 字符]";
            }
            mTvResultCode.setText(resText);
        }
    }

    private void scrollChatToBottom() {
        if (mChatScrollView == null) return;
        mChatScrollView.post(new Runnable() {
            @Override
            public void run() {
                mChatScrollView.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    public void onLoopStateChanged(boolean running, boolean pausedForTakeover, int step) {
        int accent = AcidTheme.accent(mIsDarkMode);
        int muted = AcidTheme.muted(mIsDarkMode);
        int yellow = AcidTheme.COLOR_AMBER;

        if (mTvAgentLoopStatus != null) {
            if (running) {
                mTvAgentLoopStatus.setText("运行中 · Step " + step);
                mTvAgentLoopStatus.setTextColor(yellow);
            } else if (pausedForTakeover) {
                mTvAgentLoopStatus.setText("等待人工接管协助");
                mTvAgentLoopStatus.setTextColor(AcidTheme.COLOR_AMBER);
            } else {
                mTvAgentLoopStatus.setText(mAgentLoop != null ? mAgentLoop.getModelStatusDisplay() : "");
                mTvAgentLoopStatus.setTextColor(muted);
            }
        }
        if (mTvAgentSessionTitle != null && mAgentLoop != null && mAgentLoop.getActiveSession() != null) {
            mTvAgentSessionTitle.setText(mAgentLoop.getActiveSession().title);
        }
        if (mBtnAgentSendStop != null) {
            if (running) {
                mBtnAgentSendStop.update(AcidIcons.ICON_CLOSE, "停止", AcidTheme.COLOR_RED);
            } else if (pausedForTakeover) {
                mBtnAgentSendStop.update(AcidIcons.ICON_AGENT, "继续", accent);
            } else {
                mBtnAgentSendStop.update(AcidIcons.ICON_SEND, "发送", accent);
            }
        }

        boolean isWorking = running || pausedForTakeover;
        if (mBtnSessions != null) mBtnSessions.setVisibility(isWorking ? View.GONE : View.VISIBLE);
        if (mBtnNewSession != null) mBtnNewSession.setVisibility(isWorking ? View.GONE : View.VISIBLE);
        if (mBtnModelConfig != null) mBtnModelConfig.setVisibility(isWorking ? View.GONE : View.VISIBLE);
        if (mContextUsagePill != null) {
            mContextUsagePill.setVisibility(isWorking ? View.VISIBLE : View.GONE);
            if (isWorking && mAgentLoop != null && mAgentLoop.getActiveSession() != null) {
                AcidSessionStore.AgentSession s = mAgentLoop.getActiveSession();
                int tt = s.lastTotalTokens > 0 ? s.lastTotalTokens : mAgentLoop.computeEstimatedTokens(s);
                updateContextUsage(tt, s.lastPromptTokens, s.lastCachedTokens);
            }
        }
    }

    public void updateContextUsage(int totalTokens, int promptTokens, int cachedTokens) {
        mLastTotalTokens = totalTokens;
        mLastPromptTokens = promptTokens;
        mLastCachedTokens = cachedTokens;

        if (mTvContextUsageText == null) return;
        float pct = (totalTokens / 1000000.0f) * 100.0f;
        String tokenStr = totalTokens >= 1000
            ? String.format(java.util.Locale.US, "%.1fk", totalTokens / 1000.0)
            : String.valueOf(totalTokens);

        float hitRate = promptTokens > 0 ? ((cachedTokens * 100.0f) / promptTokens) : 0f;
        String hitStr = String.format(java.util.Locale.US, "%.0f%%", hitRate);

        String display = tokenStr + "/1M (" + String.format(java.util.Locale.US, "%.1f%%", pct) + ") · 缓存 " + hitStr;
        mTvContextUsageText.setText(display);
    }

    public void triggerAgentSubmitOrStop() {
        if (mAgentLoop == null) return;
        if (mAgentLoop.isRunning()) {
            mAgentLoop.stopLoop();
            setHasUnread(false);
            AcidFeedback.info(getContext(), "已停止当前 Agent 任务");
            return;
        }
        if (mAgentLoop.isPausedForTakeover()) {
            setHasUnread(false);
            mAgentLoop.resumeFromTakeover();
            return;
        }
        String task = mEtAgentTaskInput.getText().toString().trim();
        if (task.isEmpty()) {
            AcidFeedback.warn(getContext(), "请输入要让 Agent 执行的任务指令");
            return;
        }
        mEtAgentTaskInput.setText("");
        setHasUnread(false);
        hideKeyboard();
        mAgentLoop.submitUserTask(task);
    }

    public void refreshAgentStatusLabel() {
        if (mTvAgentLoopStatus != null && mAgentLoop != null && !mAgentLoop.isRunning() && !mAgentLoop.isPausedForTakeover()) {
            mTvAgentLoopStatus.setText(mAgentLoop.getModelStatusDisplay());
        }
    }

    public void applyTheme(boolean isDark) {
        mIsDarkMode = isDark;
        Context ctx = getContext();
        int text = AcidTheme.text(isDark);
        int muted = AcidTheme.muted(isDark);
        int searchBg = AcidTheme.searchBg(isDark);
        int searchBorder = AcidTheme.searchBorder(isDark);

        if (mDrawerPanel != null) {
            int floatBg = AcidTheme.panelBg(isDark);
            int floatBorder = AcidTheme.panelBorder(isDark);
            mDrawerPanel.setBackground(AcidTheme.cardBorderBg(ctx, floatBg, floatBorder, 2, AcidTheme.RADIUS_SHEET));
        }
        if (mAgentHeaderBar != null) {
            GradientDrawable headGd = new GradientDrawable();
            headGd.setColor(AcidTheme.sheetBg(isDark));
            float hr = AcidTheme.dp(ctx, AcidTheme.RADIUS_CARD);
            headGd.setCornerRadii(new float[]{hr, hr, hr, hr, 0, 0, 0, 0});
            mAgentHeaderBar.setBackground(headGd);
        }
        if (mHeaderDivider != null) mHeaderDivider.setBackgroundColor(AcidTheme.border(isDark));
        if (mInputDivider != null) mInputDivider.setBackgroundColor(AcidTheme.border(isDark));
        if (mIconModelConfig != null) mIconModelConfig.setColor(muted);
        if (mIconCloseDrawer != null) mIconCloseDrawer.setColor(muted);
        if (mTvAgentSessionTitle != null) {
            mTvAgentSessionTitle.setTextColor(text);
        }
        if (mAgentInputBar != null) {
            GradientDrawable inGd = new GradientDrawable();
            inGd.setColor(AcidTheme.sheetBg(isDark));
            float ir = AcidTheme.dp(ctx, AcidTheme.RADIUS_CARD);
            inGd.setCornerRadii(new float[]{0, 0, 0, 0, ir, ir, ir, ir});
            mAgentInputBar.setBackground(inGd);
        }
        if (mEtAgentTaskInput != null) {
            mEtAgentTaskInput.setBackground(AcidTheme.pillBg(ctx, searchBg, searchBorder, AcidTheme.RADIUS_PILL));
            mEtAgentTaskInput.setTextColor(text);
            mEtAgentTaskInput.setHintTextColor(muted);
        }
        if (mAgentLoop != null && mAgentLoop.getActiveSession() != null) {
            renderSessionChatStream(mAgentLoop.getActiveSession());
        }
    }

    private void hideKeyboard() {
        AcidTheme.hideKeyboard(this);
    }
}
