package com.acid.browser;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Agent Conversation Sessions Sheet built on {@link AcidBottomSheetView}, {@link AcidWidgets},
 * and {@link AcidDialog}.
 */
public class AgentSessionsSheet extends AcidBottomSheetView {

    public interface OnSessionActionListener {
        void onSessionSwitched(String sessionId);
        void onSessionCreated();
    }

    private final AcidAgentLoop mAgentLoop;
    private final OnSessionActionListener mListener;

    public AgentSessionsSheet(Context context, AcidAgentLoop agentLoop, OnSessionActionListener listener) {
        super(context);
        mAgentLoop = agentLoop;
        mListener = listener;
    }

    public void showSessions() {
        if (mAgentLoop == null) return;
        renderSessionListItems();
        show();
    }

    @Override
    protected void onThemeChanged(boolean isDark) {
        if (isShowing()) {
            renderSessionListItems();
        }
    }

    private void renderSessionListItems() {
        if (mAgentLoop == null || mAgentLoop.getSessionStore() == null) return;
        setTitle("历史会话");
        setAction(AcidIcons.ICON_PLUS, "新建对话", AcidTheme.accent(mIsDarkMode), new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                mAgentLoop.startNewSession();
                if (mListener != null) {
                    mListener.onSessionCreated();
                }
            }
        });

        clearPinnedHeader();
        LinearLayout container = getContentContainer();
        container.removeAllViews();

        final Context ctx = getContext();
        final List<AcidSessionStore.SessionMeta> sessions = mAgentLoop.getSessionStore().listSessions();
        final AcidSessionStore.AgentSession active = mAgentLoop.getActiveSession();

        if (sessions.isEmpty()) {
            container.addView(AcidWidgets.emptyState(
                ctx, mIsDarkMode, AcidIcons.ICON_CHAT_LIST, "暂无历史对话，点击右上角「+ 新建对话」开始"
            ));
            return;
        }

        container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "全部会话 (" + sessions.size() + ")"));

        for (int i = 0; i < sessions.size(); i++) {
            final AcidSessionStore.SessionMeta meta = sessions.get(i);
            boolean isCurrent = (active != null && active.id.equals(meta.id));

            LinearLayout row = AcidWidgets.listItemCard(
                ctx, mIsDarkMode, isCurrent, AcidTheme.COLOR_TAB_ACTIVE_BORDER
            );
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            AcidIcons.SvgIconView icon = new AcidIcons.SvgIconView(
                ctx,
                AcidIcons.ICON_CHAT_LIST,
                isCurrent ? AcidTheme.COLOR_TAB_ACTIVE_BORDER : AcidTheme.accent(mIsDarkMode),
                18
            );
            row.addView(icon);

            LinearLayout textCol = new LinearLayout(ctx);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            );
            tLp.leftMargin = AcidTheme.dp(ctx, 10);
            tLp.rightMargin = AcidTheme.dp(ctx, 8);

            TextView title = new TextView(ctx);
            title.setText(meta.title + (isCurrent ? " (当前)" : ""));
            title.setTextColor(AcidTheme.text(mIsDarkMode));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_H4_ITEM);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            textCol.addView(title);

            TextView sub = new TextView(ctx);
            sub.setText(meta.getFormattedTime() + " · " + meta.messageCount + " 条消息");
            sub.setTextColor(AcidTheme.muted(mIsDarkMode));
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
            sub.setPadding(0, AcidTheme.dp(ctx, 2), 0, 0);
            textCol.addView(sub);
            row.addView(textCol, tLp);

            LinearLayout delBox = new LinearLayout(ctx);
            delBox.setGravity(Gravity.CENTER);
            int delSize = AcidTheme.dp(ctx, 32);
            delBox.setLayoutParams(new LinearLayout.LayoutParams(delSize, delSize));
            delBox.setBackground(AcidTheme.interactiveBg(
                ctx, Color.TRANSPARENT, AcidTheme.rowPressed(mIsDarkMode), 0, AcidTheme.RADIUS_BADGE
            ));
            AcidIcons.SvgIconView delIcon = new AcidIcons.SvgIconView(
                ctx, AcidIcons.ICON_TRASH, AcidTheme.muted(mIsDarkMode), 15
            );
            delBox.addView(delIcon);
            delBox.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDeleteSession(meta);
                }
            });
            row.addView(delBox);

            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    mAgentLoop.switchSession(meta.id);
                    if (mListener != null) {
                        mListener.onSessionSwitched(meta.id);
                    }
                }
            });

            container.addView(row);
        }
    }

    private void confirmDeleteSession(final AcidSessionStore.SessionMeta meta) {
        final Context ctx = getContext();
        new AcidDialog(ctx, mIsDarkMode)
            .setTitle("删除对话记录")
            .setMessage("确定删除对话「" + meta.title + "」吗？删除后无法恢复。")
            .setDangerButton("删除", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    mAgentLoop.deleteSession(meta.id);
                    renderSessionListItems();
                    if (mListener != null && mAgentLoop.getActiveSession() != null) {
                        mListener.onSessionSwitched(mAgentLoop.getActiveSession().id);
                    }
                    Toast.makeText(ctx, "已删除对话", Toast.LENGTH_SHORT).show();
                    return true;
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }
}
