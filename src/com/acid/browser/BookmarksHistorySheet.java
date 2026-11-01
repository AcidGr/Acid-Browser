package com.acid.browser;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Bookmarks & Browsing History Sheet built on {@link AcidBottomSheetView}'s {@link SheetPage}
 * template, {@link AcidWidgets.ListRow}, {@link AcidContextMenu}, and {@link AcidDialogs}.
 *
 * <p>Supports:
 * <ul>
 *   <li>Real-time fuzzy search across both title and URL keywords.</li>
 *   <li>Smart time-bucketing for browsing history (Today, Yesterday, Past 7 Days, Earlier).</li>
 *   <li>Relative and absolute timestamps (e.g. "15:42", "昨天 20:15", "10-24 09:30").</li>
 * </ul>
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
    private String mSearchQuery = "";

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
        mSearchQuery = "";
        setRootPage(buildActiveTabPage());
    }

    public void prepareTab(int tab, String currentTitle, String currentUrl) {
        mActiveTab = tab;
        mCurrentPageTitle = currentTitle != null ? currentTitle : "";
        mCurrentPageUrl = currentUrl != null ? currentUrl : "";
        mSearchQuery = "";
    }

    private SheetPage buildActiveTabPage() {
        final Context ctx = getContext();

        final SheetPage page = new SheetPage("书签与历史", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, final LinearLayout container) {
                final boolean isBookmarks = (mActiveTab == TAB_BOOKMARKS);

                // 1. Pinned Header: Tabs switcher
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

                // 2. Search Box (Persistent focus: mutates container without tearing down pinned view)
                View spacer = new View(ctx);
                pinned.addView(spacer, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, AcidTheme.dp(ctx, 8)
                ));

                final EditText searchInput = new EditText(ctx);
                searchInput.setText(mSearchQuery);
                if (mSearchQuery != null && mSearchQuery.length() > 0) {
                    searchInput.setSelection(mSearchQuery.length());
                }
                searchInput.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                    @Override
                    public void afterTextChanged(Editable s) {
                        String q = s != null ? s.toString().trim() : "";
                        if (!mSearchQuery.equals(q)) {
                            mSearchQuery = q;
                            container.removeAllViews();
                            renderContainerEntries(container, mActiveTab == TAB_BOOKMARKS);
                        }
                    }
                });

                LinearLayout searchBox = AcidWidgets.searchBox(
                    ctx,
                    mIsDarkMode,
                    searchInput,
                    isBookmarks ? "搜索书签标题或网址..." : "搜索历史标题或网址..."
                );
                pinned.addView(searchBox);

                syncHeaderActionForActiveTab(isBookmarks);
                renderContainerEntries(container, isBookmarks);
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

    private void renderContainerEntries(LinearLayout container, boolean isBookmarks) {
        final Context ctx = getContext();
        List<BrowserDataStore.EntryItem> rawList = isBookmarks ? mDataStore.loadBookmarks() : mDataStore.loadHistory();

        // 1. Filter by Query (Title or URL keywords)
        String q = mSearchQuery.toLowerCase(Locale.ROOT).trim();
        List<BrowserDataStore.EntryItem> filteredList = new ArrayList<BrowserDataStore.EntryItem>();
        for (BrowserDataStore.EntryItem item : rawList) {
            if (item == null) continue;
            if (q.isEmpty()
                || item.title.toLowerCase(Locale.ROOT).contains(q)
                || item.url.toLowerCase(Locale.ROOT).contains(q)) {
                filteredList.add(item);
            }
        }

        // 2. Empty States
        if (filteredList.isEmpty()) {
            if (!q.isEmpty()) {
                container.addView(AcidWidgets.emptyState(
                    ctx,
                    mIsDarkMode,
                    AcidIcons.ICON_SEARCH,
                    "未找到与 \"" + mSearchQuery + "\" 相关的记录"
                ));
            } else {
                container.addView(AcidWidgets.emptyState(
                    ctx,
                    mIsDarkMode,
                    isBookmarks ? AcidIcons.ICON_BOOKMARK : AcidIcons.ICON_HISTORY,
                    isBookmarks ? "暂无书签收藏，点击右上角可收藏当前页" : "暂无历史浏览记录"
                ));
            }
            return;
        }

        // 3. Render Bookmarks or Search Results (Single flat list)
        if (isBookmarks || !q.isEmpty()) {
            String headerText;
            if (!q.isEmpty()) {
                headerText = "搜索结果 (" + filteredList.size() + ")";
            } else {
                headerText = "全部书签 (" + filteredList.size() + ")";
            }
            container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, headerText));

            for (int i = 0; i < filteredList.size(); i++) {
                final int index = i;
                final BrowserDataStore.EntryItem item = filteredList.get(i);
                renderEntryRow(container, item, index, isBookmarks);
            }
            return;
        }

        // 4. Render History with Smart Time-Bucketing (Today / Yesterday / Past 7 Days / Earlier)
        long now = System.currentTimeMillis();
        long startOfToday = getStartOfDay(now);
        long startOfYesterday = startOfToday - 86400000L;
        long startOfPast7Days = startOfToday - 6 * 86400000L;

        List<BrowserDataStore.EntryItem> todayList = new ArrayList<BrowserDataStore.EntryItem>();
        List<BrowserDataStore.EntryItem> yesterdayList = new ArrayList<BrowserDataStore.EntryItem>();
        List<BrowserDataStore.EntryItem> past7DaysList = new ArrayList<BrowserDataStore.EntryItem>();
        List<BrowserDataStore.EntryItem> earlierList = new ArrayList<BrowserDataStore.EntryItem>();

        for (BrowserDataStore.EntryItem item : filteredList) {
            if (item.time >= startOfToday) {
                todayList.add(item);
            } else if (item.time >= startOfYesterday) {
                yesterdayList.add(item);
            } else if (item.time >= startOfPast7Days) {
                past7DaysList.add(item);
            } else {
                earlierList.add(item);
            }
        }

        int globalIndex = 0;
        if (!todayList.isEmpty()) {
            container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "今天 (" + todayList.size() + ")"));
            for (BrowserDataStore.EntryItem item : todayList) {
                renderEntryRow(container, item, globalIndex++, false);
            }
        }

        if (!yesterdayList.isEmpty()) {
            container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "昨天 (" + yesterdayList.size() + ")"));
            for (BrowserDataStore.EntryItem item : yesterdayList) {
                renderEntryRow(container, item, globalIndex++, false);
            }
        }

        if (!past7DaysList.isEmpty()) {
            container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "最近 7 天 (" + past7DaysList.size() + ")"));
            for (BrowserDataStore.EntryItem item : past7DaysList) {
                renderEntryRow(container, item, globalIndex++, false);
            }
        }

        if (!earlierList.isEmpty()) {
            container.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, "更早以前 (" + earlierList.size() + ")"));
            for (BrowserDataStore.EntryItem item : earlierList) {
                renderEntryRow(container, item, globalIndex++, false);
            }
        }
    }

    private void renderEntryRow(
        LinearLayout container,
        final BrowserDataStore.EntryItem item,
        final int index,
        final boolean isBookmarks
    ) {
        final Context ctx = getContext();
        AcidWidgets.ListRow rowBuilder = new AcidWidgets.ListRow()
            .title(item.title)
            .subtitle(item.url);

        if (isBookmarks) {
            FaviconManager fm = FaviconManager.getInstance(ctx);
            Bitmap fastBmp = fm.getFaviconFast(item.url);
            final FrameLayout avatar = AcidWidgets.createFaviconAvatar(
                ctx, mIsDarkMode, fastBmp, item.title, item.url
            );
            rowBuilder.leadingCustomView(avatar);

            if (fastBmp == null) {
                fm.loadFaviconAsync(item.url, new FaviconManager.FaviconCallback() {
                    @Override
                    public void onFaviconLoaded(String host, Bitmap bitmap) {
                        if (bitmap != null && !bitmap.isRecycled()) {
                            AcidWidgets.updateFaviconAvatarView(
                                avatar, ctx, mIsDarkMode, bitmap, item.title, item.url
                            );
                        }
                    }
                });
            }
        } else {
            FaviconManager fm = FaviconManager.getInstance(ctx);
            Bitmap fastBmp = fm.getFaviconFast(item.url);
            if (fastBmp != null) {
                rowBuilder.leadingFavicon(ctx, mIsDarkMode, fastBmp, item.title, item.url);
            } else {
                rowBuilder.leadingIcon(AcidIcons.ICON_HISTORY, AcidTheme.COLOR_AMBER, 18);
            }
            if (item.time > 0) {
                rowBuilder.trailingText(ctx, mIsDarkMode, formatTimestamp(item.time));
            }
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

    private static long getStartOfDay(long timeMillis) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timeMillis);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private static String formatTimestamp(long timeMillis) {
        if (timeMillis <= 0) return "";
        long now = System.currentTimeMillis();
        long startOfToday = getStartOfDay(now);
        long startOfYesterday = startOfToday - 86400000L;

        if (timeMillis >= startOfToday) {
            return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(timeMillis));
        } else if (timeMillis >= startOfYesterday) {
            return "昨天 " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(timeMillis));
        } else {
            return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(timeMillis));
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
                AcidFeedback.ok(ctx, "已添加至书签收藏");
            }
        }));
        AcidContextMenu.showForAnchor(ctx, anchor, mIsDarkMode, item.title, actions);
    }

    private void showEditBookmarkDialog(final int index, final BrowserDataStore.EntryItem item) {
        final Context ctx = getContext();
        final EditText etTitle = AcidWidgets.inputField(ctx, mIsDarkMode, item.title, "网站标题", true);
        final EditText etUrl = AcidWidgets.inputField(ctx, mIsDarkMode, item.url, "网址 (URL)", true);
        etUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AcidTheme.dp(ctx, 16), AcidTheme.dp(ctx, 8), AcidTheme.dp(ctx, 16), AcidTheme.dp(ctx, 8));

        layout.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "网站标题"));
        layout.addView(etTitle);
        layout.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "网址 (URL)"));
        layout.addView(etUrl);

        new AcidDialog(ctx, mIsDarkMode)
            .setTitle("编辑书签")
            .setCustomView(layout)
            .setPositiveButton("保存", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    String t = etTitle.getText() != null ? etTitle.getText().toString().trim() : "";
                    String u = etUrl.getText() != null ? etUrl.getText().toString().trim() : "";
                    if (TextUtils.isEmpty(u)) {
                        AcidFeedback.warn(ctx, "网址不能为空");
                        return false;
                    }
                    mDataStore.updateBookmark(index, t, u);
                    refreshActivePage();
                    AcidFeedback.ok(ctx, "书签已更新");
                    return true;
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showDeleteBookmarkConfirm(final int index, final BrowserDataStore.EntryItem item) {
        final Context ctx = getContext();
        AcidDialogs.confirmDanger(
            ctx,
            mIsDarkMode,
            "删除书签",
            "确定要删除书签「" + item.title + "」吗？",
            "删除",
            new Runnable() {
                @Override
                public void run() {
                    mDataStore.removeBookmark(index);
                    refreshActivePage();
                    AcidFeedback.info(ctx, "已删除书签");
                }
            }
        );
    }

    private void confirmClearAllHistory() {
        final Context ctx = getContext();
        AcidDialogs.confirmDanger(
            ctx,
            mIsDarkMode,
            "清空历史记录",
            "确定要清空全部历史浏览记录吗？此操作无法撤销。",
            "清空全部",
            new Runnable() {
                @Override
                public void run() {
                    mDataStore.clearHistory();
                    refreshActivePage();
                    AcidFeedback.ok(ctx, "已清空历史浏览记录");
                }
            }
        );
    }
}
