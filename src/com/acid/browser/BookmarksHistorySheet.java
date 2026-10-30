package com.acid.browser;

import android.app.Dialog;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Bookmarks & Browsing History Sheet built on {@link AcidBottomSheetView}'s {@link SheetPage}
 * template, {@link AcidWidgets.ListRow}, {@link AcidContextMenu}, and {@link AcidDialogs}.
 */
public class BookmarksHistorySheet extends AcidBottomSheetView {

    public interface OnNavigateListener {
        void onNavigateUrl(String url);
    }

    public static final int TAB_BOOKMARKS = 0;
    public static final int TAB_HISTORY = 1;

    private final BrowserDataStore mDataStore;
    private final BrowserController mController;
    private final OnNavigateListener mNavigateListener;

    private int mActiveTab = TAB_BOOKMARKS;
    private String mCurrentPageTitle = "";
    private String mCurrentPageUrl = "";

    public BookmarksHistorySheet(
        Context context,
        BrowserDataStore dataStore,
        BrowserController controller,
        OnNavigateListener navigateListener
    ) {
        super(context);
        mDataStore = dataStore;
        mController = controller;
        mNavigateListener = navigateListener;
        setRootPage(buildActiveTabPage());
    }

    @Override
    protected void onResetToRootPage() {
        setRootPage(buildActiveTabPage());
    }

    public void prepareTab(int tab, String currentTitle, String currentUrl) {
        mActiveTab = tab;
        mCurrentPageTitle = currentTitle != null ? currentTitle : "";
        mCurrentPageUrl = currentUrl != null ? currentUrl : "";
    }

    public void showBookmarks(String currentTitle, String currentUrl) {
        prepareTab(TAB_BOOKMARKS, currentTitle, currentUrl);
        setRootPage(buildActiveTabPage());
        show();
    }

    public void showHistory(String currentTitle, String currentUrl) {
        prepareTab(TAB_HISTORY, currentTitle, currentUrl);
        setRootPage(buildActiveTabPage());
        show();
    }

    private SheetPage buildActiveTabPage() {
        final Context ctx = getContext();

        final SheetPage page = new SheetPage("书签与历史", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, final LinearLayout container) {
                pinned.addView(AcidWidgets.segmentedTabs(
                    ctx,
                    mIsDarkMode,
                    new String[]{"书签收藏", "历史浏览"},
                    mActiveTab,
                    AcidTheme.accent(mIsDarkMode),
                    new AcidWidgets.OnTabSelectedListener() {
                        @Override
                        public void onTabSelected(int index) {
                            if (mActiveTab != index) {
                                mActiveTab = index;
                                refreshActivePage();
                            }
                        }
                    }
                ));
                boolean isBookmarks = (mActiveTab == TAB_BOOKMARKS);
                syncHeaderActionForActiveTab(isBookmarks);
                renderEntriesList(
                    container,
                    isBookmarks ? mDataStore.loadBookmarks() : mDataStore.loadHistory(),
                    isBookmarks
                );
            }
        });
        return page;
    }

    private void syncHeaderActionForActiveTab(boolean isBookmarks) {
        final Context ctx = getContext();
        if (isBookmarks) {
            if (!TextUtils.isEmpty(mCurrentPageUrl)) {
                setAction(AcidIcons.ICON_PLUS, "收藏当前页", 0, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mDataStore.addBookmark(mCurrentPageTitle, mCurrentPageUrl);
                        refreshActivePage();
                        AcidFeedback.ok(ctx, "已收藏当前网页");
                    }
                });
            } else {
                hideAction();
            }
        } else {
            if (!mDataStore.loadHistory().isEmpty()) {
                setAction(AcidIcons.ICON_TRASH, "清空历史", AcidTheme.COLOR_RED, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        confirmClearAllHistory();
                    }
                });
            } else {
                hideAction();
            }
        }
    }

    private void renderEntriesList(
        LinearLayout container,
        List<BrowserDataStore.EntryItem> list,
        final boolean isBookmarks
    ) {
        final Context ctx = getContext();
        if (list.isEmpty()) {
            container.addView(AcidWidgets.emptyState(
                ctx,
                mIsDarkMode,
                isBookmarks ? AcidIcons.ICON_BOOKMARK : AcidIcons.ICON_HISTORY,
                isBookmarks ? "暂无书签收藏，点击右上角可收藏当前页" : "暂无历史浏览记录"
            ));
            return;
        }

        container.addView(AcidWidgets.sectionHeader(
            ctx,
            mIsDarkMode,
            (isBookmarks ? "全部书签 (" : "最近浏览 (") + list.size() + ")"
        ));

        for (int i = 0; i < list.size(); i++) {
            final int index = i;
            final BrowserDataStore.EntryItem item = list.get(i);

            AcidWidgets.ListRow rowBuilder = new AcidWidgets.ListRow()
                .title(item.title)
                .subtitle(item.url);

            if (isBookmarks) {
                rowBuilder.leadingAvatar(ctx, item.title, item.url);
            } else {
                rowBuilder.leadingIcon(AcidIcons.ICON_HISTORY, AcidTheme.COLOR_AMBER, 18);
            }

            final LinearLayout row = rowBuilder.build(ctx, mIsDarkMode);
            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeEntireModalFlow();
                    if (mNavigateListener != null) {
                        mNavigateListener.onNavigateUrl(item.url);
                    }
                }
            });
            row.setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (isBookmarks) {
                        showBookmarkContextMenu(row, index, item);
                    } else {
                        showHistoryContextMenu(row, item);
                    }
                    return true;
                }
            });
            container.addView(row);
        }
    }

    private void showBookmarkContextMenu(View anchor, final int index, final BrowserDataStore.EntryItem item) {
        final Context ctx = getContext();
        List<AcidContextMenu.ActionItem> actions = new ArrayList<AcidContextMenu.ActionItem>();
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_TABS, "新标签页打开", new Runnable() {
            @Override
            public void run() {
                closeEntireModalFlow();
                if (mController != null) mController.createNewTab(item.url, true);
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_PLUS, "后台打开", new Runnable() {
            @Override
            public void run() {
                closeEntireModalFlow();
                if (mController != null) mController.createNewTab(item.url, false);
                AcidFeedback.info(ctx, "已在后台打开新标签");
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_EDIT, "编辑书签", new Runnable() {
            @Override
            public void run() {
                showEditBookmarkDialog(index, item);
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_TRASH, "删除书签", true, new Runnable() {
            @Override
            public void run() {
                showDeleteBookmarkConfirm(index, item);
            }
        }));
        AcidContextMenu.showForAnchor(ctx, anchor, mIsDarkMode, item.title, actions);
    }

    private void showHistoryContextMenu(View anchor, final BrowserDataStore.EntryItem item) {
        final Context ctx = getContext();
        List<AcidContextMenu.ActionItem> actions = new ArrayList<AcidContextMenu.ActionItem>();
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_TABS, "新标签页打开", new Runnable() {
            @Override
            public void run() {
                closeEntireModalFlow();
                if (mController != null) mController.createNewTab(item.url, true);
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_PLUS, "后台打开", new Runnable() {
            @Override
            public void run() {
                closeEntireModalFlow();
                if (mController != null) mController.createNewTab(item.url, false);
                AcidFeedback.info(ctx, "已在后台打开新标签");
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_BOOKMARK, "添加到书签", new Runnable() {
            @Override
            public void run() {
                mDataStore.addBookmark(item.title, item.url);
                AcidFeedback.ok(ctx, "已添加到书签收藏");
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_FILE, "复制链接", new Runnable() {
            @Override
            public void run() {
                if (mController != null) mController.copyToClipboard(item.url);
            }
        }));
        AcidContextMenu.showForAnchor(ctx, anchor, mIsDarkMode, item.title, actions);
    }

    private void showEditBookmarkDialog(final int index, final BrowserDataStore.EntryItem item) {
        final Context ctx = getContext();
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);

        form.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "显示名称"));
        final EditText etTitle = AcidWidgets.inputField(
            ctx, mIsDarkMode, item.title, "留空则使用网址", true
        );
        form.addView(etTitle);

        form.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "网页地址"));
        final EditText etUrl = AcidWidgets.inputField(
            ctx, mIsDarkMode, item.url, "https://example.com", true
        );
        etUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(etUrl);

        new AcidDialog(ctx, mIsDarkMode)
            .setTitle("编辑书签")
            .setCustomView(form)
            .setPositiveButton("保存", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    String newTitle = etTitle.getText().toString().trim();
                    String newUrl = etUrl.getText().toString().trim();
                    if (TextUtils.isEmpty(newUrl)) {
                        AcidFeedback.warn(ctx, "网址不能为空");
                        return false;
                    }
                    if (mDataStore.updateBookmark(index, newTitle, newUrl)) {
                        refreshActivePage();
                        AcidFeedback.ok(ctx, "书签已更新");
                    } else {
                        AcidFeedback.warn(ctx, "更新失败，书签可能已被删除");
                    }
                    return true;
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showDeleteBookmarkConfirm(final int index, final BrowserDataStore.EntryItem item) {
        final Context ctx = getContext();
        AcidDialogs.confirmDanger(
            ctx, mIsDarkMode,
            "删除书签",
            "确定删除书签「" + item.title + "」吗？",
            "删除",
            new Runnable() {
                @Override
                public void run() {
                    mDataStore.removeBookmark(index);
                    refreshActivePage();
                    AcidFeedback.info(ctx, "书签已删除");
                }
            }
        );
    }

    private void confirmClearAllHistory() {
        final Context ctx = getContext();
        AcidDialogs.confirmDanger(
            ctx, mIsDarkMode,
            "清空浏览历史",
            "确定清空全部历史浏览记录吗？此操作无法撤销。",
            "立即清空",
            new Runnable() {
                @Override
                public void run() {
                    mDataStore.clearHistory();
                    if (mController != null) {
                        mController.clearAllHistory();
                    }
                    setRootPage(buildActiveTabPage());
                    AcidFeedback.info(ctx, "历史浏览记录已清空");
                }
            }
        );
    }
}
