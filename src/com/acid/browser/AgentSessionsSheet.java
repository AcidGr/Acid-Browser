package com.acid.browser;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Top-level Modal BottomSheet for managing and switching between Agent conversation sessions.
 * Placed at the root container level to ensure strict Z-order priority above floating drawers and toolbars.
 */
public class AgentSessionsSheet extends AcidBottomSheetView {

    public interface OnSessionActionListener {
        void onSessionSwitched(String sessionId);
        void onSessionCreated();
    }

    private final AcidAgentLoop mAgentLoop;
    private final OnSessionActionListener mListener;
    private boolean mIsDarkMode = false;

    public AgentSessionsSheet(Context context, AcidAgentLoop agentLoop, OnSessionActionListener listener) {
        super(context);
        mAgentLoop = agentLoop;
        mListener = listener;

        if (Build.VERSION.SDK_INT >= 21) {
            setElevation(AcidTheme.dp(context, 32));
        }
    }

    @Override
    public void applyTheme(boolean isDark) {
        super.applyTheme(isDark);
        mIsDarkMode = isDark;
    }

    public void showSessions() {
        if (mAgentLoop == null) return;
        setTitle("历史会话");
        int accent = AcidTheme.accent(mIsDarkMode);

        setAction(AcidIcons.ICON_PLUS, "新建对话", accent, new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                if (mAgentLoop != null) {
                    mAgentLoop.startNewSession();
                }
                if (mListener != null) {
                    mListener.onSessionCreated();
                }
            }
        });

        renderSessionListItems();
        show();
    }

    private void renderSessionListItems() {
        LinearLayout container = getContentContainer();
        container.removeAllViews();
        if (mAgentLoop == null || mAgentLoop.getSessionStore() == null) return;

        Context ctx = getContext();
        final List<AcidSessionStore.SessionMeta> sessions = mAgentLoop.getSessionStore().listSessions();
        final AcidSessionStore.AgentSession active = mAgentLoop.getActiveSession();

        if (sessions.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无历史会话记录，点击右上角 [+ 新建对话] 开始");
            empty.setTextColor(AcidTheme.muted(mIsDarkMode));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, AcidTheme.dp(ctx, 30), 0, AcidTheme.dp(ctx, 30));
            container.addView(empty);
            return;
        }

        for (int i = 0; i < sessions.size(); i++) {
            final AcidSessionStore.SessionMeta meta = sessions.get(i);
            boolean isCurrent = (active != null && active.id.equals(meta.id));

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int pad = AcidTheme.dp(ctx, 10);
            row.setPadding(pad, pad, pad, pad);
            int rowBg = isCurrent
                ? (mIsDarkMode ? Color.parseColor("#0C4A6E") : Color.parseColor("#E0F2FE"))
                : AcidTheme.menuItemBg(mIsDarkMode);
            int rowBorder = isCurrent ? AcidTheme.COLOR_TAB_ACTIVE_BORDER : 0;
            row.setBackground(AcidTheme.pillBg(ctx, rowBg, rowBorder, 8));
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rLp.bottomMargin = AcidTheme.dp(ctx, 6);

            AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(
                ctx, AcidIcons.ICON_CHAT_LIST, isCurrent ? AcidTheme.COLOR_TAB_ACTIVE_BORDER : AcidTheme.accent(mIsDarkMode), 17
            );
            row.addView(icon);

            LinearLayout textCol = new LinearLayout(ctx);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tLp.leftMargin = AcidTheme.dp(ctx, 8);
            tLp.rightMargin = AcidTheme.dp(ctx, 8);

            TextView title = new TextView(ctx);
            title.setText(meta.title + (isCurrent ? " (当前)" : ""));
            title.setTextColor(AcidTheme.text(mIsDarkMode));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            textCol.addView(title);

            TextView sub = new TextView(ctx);
            sub.setText(meta.getFormattedTime() + " · " + meta.messageCount + " 条记录");
            sub.setTextColor(AcidTheme.muted(mIsDarkMode));
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            textCol.addView(sub);
            row.addView(textCol, tLp);

            LinearLayout delBox = new LinearLayout(ctx);
            delBox.setGravity(Gravity.CENTER);
            delBox.setPadding(AcidTheme.dp(ctx, 6), AcidTheme.dp(ctx, 6), AcidTheme.dp(ctx, 6), AcidTheme.dp(ctx, 6));
            AcidIcons.SvgIconView delIcon = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_TRASH, AcidTheme.muted(mIsDarkMode), 14);
            delBox.addView(delIcon);
            delBox.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mAgentLoop.deleteSession(meta.id);
                    renderSessionListItems();
                }
            });
            row.addView(delBox);

            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (mAgentLoop != null) {
                        mAgentLoop.switchSession(meta.id);
                    }
                    if (mListener != null) {
                        mListener.onSessionSwitched(meta.id);
                    }
                }
            });

            container.addView(row, rLp);
        }
    }
}
