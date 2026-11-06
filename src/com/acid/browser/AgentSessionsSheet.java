package com.acid.browser;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import java.util.List;

/**
 * Agent Conversation Sessions Sheet built on {@link AcidBottomSheetView}'s {@link SheetPage}
 * template, {@link AcidWidgets.ListRow}, and {@link AcidDialogs}.
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
        setRootPage(buildSessionsPage());
    }

    @Override
    protected void onResetToRootPage() {
        setRootPage(buildSessionsPage());
    }

    @Override
    public void show() {
        if (getPageDepth() == 0) {
            setRootPage(buildSessionsPage());
        }
        super.show();
    }

    private SheetPage buildSessionsPage() {
        return new SheetPage("历史会话", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                if (mAgentLoop == null || mAgentLoop.getSessionStore() == null) return;

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
                int accent = AcidTheme.accent(mIsDarkMode);

                for (int i = 0; i < sessions.size(); i++) {
                    final AcidSessionStore.SessionMeta meta = sessions.get(i);
                    boolean isCurrent = (active != null && active.id.equals(meta.id));

                    String baseTitle = "新对话".equals(meta.title) ? AcidLocale.tr("新对话") : meta.title;
                    String displayTitle = baseTitle + (isCurrent ? (AcidLocale.isEn() ? " (Current)" : " (当前)") : "");
                    LinearLayout row = new AcidWidgets.ListRow()
                        .leadingIcon(AcidIcons.ICON_CHAT_LIST, accent, 18)
                        .title(displayTitle)
                        .subtitle(meta.getFormattedTime() + " · " + meta.messageCount + " 条消息")
                        .selected(isCurrent, accent)
                        .trailingIconAction(ctx, mIsDarkMode, AcidIcons.ICON_TRASH, AcidTheme.muted(mIsDarkMode), new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                confirmDeleteSession(meta);
                            }
                        })
                        .onClick(new OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                closeEntireModalFlow();
                                mAgentLoop.switchSession(meta.id);
                                if (mListener != null) {
                                    mListener.onSessionSwitched(meta.id);
                                }
                            }
                        })
                        .build(ctx, mIsDarkMode);

                    container.addView(row);
                }
            }
        }).withAction(AcidIcons.ICON_PLUS, "新建对话", new OnClickListener() {
            @Override
            public void onClick(View v) {
                closeEntireModalFlow();
                if (mAgentLoop != null) {
                    mAgentLoop.startNewSession();
                }
                if (mListener != null) {
                    mListener.onSessionCreated();
                }
            }
        });
    }

    private void confirmDeleteSession(final AcidSessionStore.SessionMeta meta) {
        final Context ctx = getContext();
        AcidDialogs.confirmDanger(
            ctx, mIsDarkMode,
            "删除对话记录",
            "确定删除对话「" + meta.title + "」吗？删除后无法恢复。",
            "删除",
            new Runnable() {
                @Override
                public void run() {
                    mAgentLoop.deleteSession(meta.id);
                    refreshActivePage();
                    if (mListener != null && mAgentLoop.getActiveSession() != null) {
                        mListener.onSessionSwitched(mAgentLoop.getActiveSession().id);
                    }
                    AcidFeedback.info(ctx, "已删除对话");
                }
            }
        );
    }
}
