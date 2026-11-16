package com.acid.browser;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Bookmarks & Browsing History Sheet built on {@link AcidBottomSheetView}'s {@link SheetPage}
 * template, {@link AcidWidgets.ListRow}, {@link AcidContextMenu}, and {@link AcidDialogs}.
 *
 * <p>Features incremental batch rendering ({@link #INITIAL_BATCH_SIZE} / {@link #LOAD_MORE_BATCH_SIZE}),
 * non-blocking Favicon resolution, single-pass JSON loading, and debounced search across both tabs.
 */
public class BookmarksHistorySheet extends AcidBottomSheetView {

    public interface OnNavigateListener {
        void onNavigateUrl(String url);
    }

    public static final int TAB_BOOKMARKS = 0;
    public static final int TAB_HISTORY = 1;
    public static final int INITIAL_BATCH_SIZE = 40;
    public static final int LOAD_MORE_BATCH_SIZE = 40;
    private static final long SEARCH_DEBOUNCE_MS = 100L;

    public static final class PreparedEntry {
        public final String sectionHeader;
        public final BrowserDataStore.EntryItem item;
        public final int originalIndex;

        public PreparedEntry(String sectionHeader, BrowserDataStore.EntryItem item, int originalIndex) {
            this.sectionHeader = sectionHeader;
            this.item = item;
            this.originalIndex = originalIndex;
        }
    }

    private final BrowserDataStore mDataStore;
    private final BrowserController mController;
    private final OnNavigateListener mNavigateListener;

    private int mActiveTab = TAB_BOOKMARKS;
    private String mCurrentPageTitle = "";
    private String mCurrentPageUrl = "";
    private String mSearchQuery = "";

    private List<BrowserDataStore.EntryItem> mCachedTabEntries = Collections.emptyList();
    private List<PreparedEntry> mPreparedEntries = Collections.emptyList();
    private LinearLayout mActiveListContainer = null;
    private boolean mActiveContainerIsBookmarks = false;
    private int mRenderedCount = 0;
    private String mLastRenderedSection = null;
    private View mLoadMoreFooterView = null;
    private int mRenderGeneration = 0;
    private boolean mAppendingBatch = false;
    private boolean mPendingLoadMorePosted = false;
    private Runnable mPendingSearchRunnable = null;

    private long mRenderStartOfToday = 0L;
    private long mRenderStartOfYesterday = 0L;
    private SimpleDateFormat mTimeOnlyFmt = null;
    private SimpleDateFormat mMonthDayTimeFmt = null;

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
        setOnScrollNearBottomListener(new OnScrollNearBottomListener() {
            @Override
            public void onScrollNearBottom() {
                scheduleLoadMoreBatch();
            }
        });
        setRootPage(buildActiveTabPage());
    }

    @Override
    protected void onResetToRootPage() {
        cancelPendingSearch();
        mSearchQuery = "";
        setRootPage(buildActiveTabPage());
    }

    public void prepareTab(int tab, String currentTitle, String currentUrl) {
        cancelPendingSearch();
        mActiveTab = tab;
        mCurrentPageTitle = currentTitle != null ? currentTitle : "";
        mCurrentPageUrl = currentUrl != null ? currentUrl : "";
        mSearchQuery = "";
    }

    private void cancelPendingSearch() {
        if (mPendingSearchRunnable != null) {
            removeCallbacks(mPendingSearchRunnable);
            mPendingSearchRunnable = null;
        }
    }

    private SheetPage buildActiveTabPage() {
        final Context ctx = getContext();

        final SheetPage page = new SheetPage("书签与历史", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, final LinearLayout container) {
                cancelPendingSearch();
                final boolean isBookmarks = (mActiveTab == TAB_BOOKMARKS);
                mCachedTabEntries = isBookmarks ? mDataStore.loadBookmarks() : mDataStore.loadHistory();

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
                                cancelPendingSearch();
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
                        final String q = s != null ? s.toString().trim() : "";
                        if (mSearchQuery.equals(q)) return;
                        cancelPendingSearch();
                        final Runnable task = new Runnable() {
                            @Override
                            public void run() {
                                if (mPendingSearchRunnable != this || !isShowing()) return;
                                mPendingSearchRunnable = null;
                                mSearchQuery = q;
                                getScrollView().scrollTo(0, 0);
                                renderContainerEntries(container, mActiveTab == TAB_BOOKMARKS);
                            }
                        };
                        mPendingSearchRunnable = task;
                        postDelayed(task, SEARCH_DEBOUNCE_MS);
                    }
                });

                LinearLayout searchBox = AcidWidgets.searchBox(
                    ctx,
                    mIsDarkMode,
                    searchInput,
                    isBookmarks ? "搜索书签标题或网址..." : "搜索历史标题或网址..."
                );
                pinned.addView(searchBox);

                syncHeaderActionForActiveTab(isBookmarks, mCachedTabEntries);
                renderContainerEntries(container, isBookmarks);
            }
        }).withOnPause(new Runnable() {
            @Override
            public void run() {
                cancelPendingSearch();
                mRenderGeneration++;
            }
        });
        return page;
    }

    private void syncHeaderActionForActiveTab(boolean isBookmarks, List<BrowserDataStore.EntryItem> entries) {
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
            if (entries != null && !entries.isEmpty()) {
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

    /**
     * Prepares ordered entries with section headers in a lightweight POJO pass without inflating views.
     */
    public static List<PreparedEntry> buildPreparedEntries(
        List<BrowserDataStore.EntryItem> rawList,
        boolean isBookmarks,
        String searchQuery,
        long nowMillis
    ) {
        if (rawList == null || rawList.isEmpty()) {
            return Collections.emptyList();
        }
        String q = searchQuery != null ? searchQuery.toLowerCase(Locale.ROOT).trim() : "";
        List<BrowserDataStore.EntryItem> filteredList = new ArrayList<BrowserDataStore.EntryItem>();
        List<Integer> originalIndices = new ArrayList<Integer>();

        for (int rawIdx = 0; rawIdx < rawList.size(); rawIdx++) {
            BrowserDataStore.EntryItem item = rawList.get(rawIdx);
            if (item == null) continue;
            if (q.isEmpty()
                || (item.title != null && item.title.toLowerCase(Locale.ROOT).contains(q))
                || (item.url != null && item.url.toLowerCase(Locale.ROOT).contains(q))) {
                filteredList.add(item);
                originalIndices.add(Integer.valueOf(rawIdx));
            }
        }
        if (filteredList.isEmpty()) {
            return Collections.emptyList();
        }

        List<PreparedEntry> prepared = new ArrayList<PreparedEntry>(filteredList.size());
        if (isBookmarks || !q.isEmpty()) {
            String headerText = !q.isEmpty()
                ? ("搜索结果 (" + filteredList.size() + ")")
                : ("全部书签 (" + filteredList.size() + ")");
            for (int i = 0; i < filteredList.size(); i++) {
                prepared.add(new PreparedEntry(
                    headerText,
                    filteredList.get(i),
                    originalIndices.get(i).intValue()
                ));
            }
            return prepared;
        }

        // History smart time-bucketing (Today / Yesterday / Past 7 Days / Earlier)
        long startOfToday = getStartOfDay(nowMillis);
        long startOfYesterday = startOfToday - 86400000L;
        long startOfPast7Days = startOfToday - 6 * 86400000L;

        List<PreparedEntry> todayList = new ArrayList<PreparedEntry>();
        List<PreparedEntry> yesterdayList = new ArrayList<PreparedEntry>();
        List<PreparedEntry> past7DaysList = new ArrayList<PreparedEntry>();
        List<PreparedEntry> earlierList = new ArrayList<PreparedEntry>();

        for (int i = 0; i < filteredList.size(); i++) {
            BrowserDataStore.EntryItem item = filteredList.get(i);
            int origIdx = originalIndices.get(i).intValue();
            if (item.time >= startOfToday) {
                todayList.add(new PreparedEntry(null, item, origIdx));
            } else if (item.time >= startOfYesterday) {
                yesterdayList.add(new PreparedEntry(null, item, origIdx));
            } else if (item.time >= startOfPast7Days) {
                past7DaysList.add(new PreparedEntry(null, item, origIdx));
            } else {
                earlierList.add(new PreparedEntry(null, item, origIdx));
            }
        }

        appendBucketWithHeader(prepared, todayList, "今天");
        appendBucketWithHeader(prepared, yesterdayList, "昨天");
        appendBucketWithHeader(prepared, past7DaysList, "最近 7 天");
        appendBucketWithHeader(prepared, earlierList, "更早以前");
        return prepared;
    }

    private static void appendBucketWithHeader(
        List<PreparedEntry> out,
        List<PreparedEntry> bucket,
        String bucketTitle
    ) {
        if (bucket.isEmpty()) return;
        String header = bucketTitle + " (" + bucket.size() + ")";
        for (PreparedEntry entry : bucket) {
            out.add(new PreparedEntry(header, entry.item, entry.originalIndex));
        }
    }

    private void renderContainerEntries(LinearLayout container, boolean isBookmarks) {
        final Context ctx = getContext();
        mRenderGeneration++;
        mActiveListContainer = container;
        mActiveContainerIsBookmarks = isBookmarks;
        mRenderedCount = 0;
        mLastRenderedSection = null;
        mLoadMoreFooterView = null;
        mPendingLoadMorePosted = false;
        container.removeAllViews();

        long now = System.currentTimeMillis();
        mRenderStartOfToday = getStartOfDay(now);
        mRenderStartOfYesterday = mRenderStartOfToday - 86400000L;
        mTimeOnlyFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        mMonthDayTimeFmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

        List<BrowserDataStore.EntryItem> rawList = mCachedTabEntries != null
            ? mCachedTabEntries
            : Collections.<BrowserDataStore.EntryItem>emptyList();
        mPreparedEntries = buildPreparedEntries(rawList, isBookmarks, mSearchQuery, now);

        // Empty States
        if (mPreparedEntries.isEmpty()) {
            if (!mSearchQuery.trim().isEmpty()) {
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

        appendNextBatch(INITIAL_BATCH_SIZE);
    }

    private void scheduleLoadMoreBatch() {
        if (mPendingLoadMorePosted || mAppendingBatch) return;
        if (mActiveListContainer == null || mRenderedCount >= mPreparedEntries.size()) return;
        mPendingLoadMorePosted = true;
        final int expectedGen = mRenderGeneration;
        post(new Runnable() {
            @Override
            public void run() {
                mPendingLoadMorePosted = false;
                if (expectedGen == mRenderGeneration && isShowing()) {
                    appendNextBatch(LOAD_MORE_BATCH_SIZE);
                }
            }
        });
    }

    private void appendNextBatch(int batchSize) {
        if (mAppendingBatch || mActiveListContainer == null) return;
        if (mRenderedCount >= mPreparedEntries.size()) return;
        mAppendingBatch = true;
        try {
            final Context ctx = getContext();
            if (mLoadMoreFooterView != null) {
                mActiveListContainer.removeView(mLoadMoreFooterView);
                mLoadMoreFooterView = null;
            }
            int targetEnd = Math.min(mPreparedEntries.size(), mRenderedCount + Math.max(1, batchSize));
            for (int i = mRenderedCount; i < targetEnd; i++) {
                PreparedEntry entry = mPreparedEntries.get(i);
                if (entry.sectionHeader != null && !entry.sectionHeader.equals(mLastRenderedSection)) {
                    mActiveListContainer.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, entry.sectionHeader));
                    mLastRenderedSection = entry.sectionHeader;
                }
                renderEntryRow(mActiveListContainer, entry.item, entry.originalIndex, mActiveContainerIsBookmarks);
                mRenderedCount++;
            }

            if (mRenderedCount < mPreparedEntries.size()) {
                mLoadMoreFooterView = buildLoadMoreFooter(mRenderedCount, mPreparedEntries.size());
                mActiveListContainer.addView(mLoadMoreFooterView);
            }
        } finally {
            mAppendingBatch = false;
        }
    }

    private View buildLoadMoreFooter(int shown, int total) {
        Context ctx = getContext();
        TextView tv = new TextView(ctx);
        tv.setText(AcidLocale.tr("继续加载更多") + " (" + shown + " / " + total + ")");
        tv.setTextColor(AcidTheme.accent(mIsDarkMode));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(AcidTheme.dp(ctx, 12), AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 12), AcidTheme.dp(ctx, 10));
        tv.setBackground(AcidTheme.cardBorderBg(
            ctx, AcidTheme.subtle(mIsDarkMode), AcidTheme.subtleBorder(mIsDarkMode), 1, AcidTheme.RADIUS_CONTROL
        ));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = AcidTheme.dp(ctx, 8);
        lp.bottomMargin = AcidTheme.dp(ctx, 6);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                appendNextBatch(LOAD_MORE_BATCH_SIZE);
            }
        });
        return tv;
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

        bindRowFavicon(ctx, rowBuilder, item, isBookmarks);
        if (!isBookmarks && item.time > 0) {
            rowBuilder.trailingText(ctx, mIsDarkMode, formatTimestamp(item.time));
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

    private void bindRowFavicon(
        final Context ctx,
        AcidWidgets.ListRow rowBuilder,
        final BrowserDataStore.EntryItem item,
        boolean allowNetworkProbe
    ) {
        FaviconManager fm = FaviconManager.getInstance(ctx);
        Bitmap memBmp = fm.getFaviconInMemory(item.url);
        final FrameLayout avatar = AcidWidgets.createFaviconAvatar(
            ctx, mIsDarkMode, memBmp, item.title, item.url
        );
        rowBuilder.leadingCustomView(avatar);

        if (memBmp == null) {
            final int gen = mRenderGeneration;
            FaviconManager.FaviconCallback cb = new FaviconManager.FaviconCallback() {
                @Override
                public void onFaviconLoaded(String host, Bitmap bitmap) {
                    if (gen == mRenderGeneration && isShowing() && bitmap != null && !bitmap.isRecycled()) {
                        AcidWidgets.updateFaviconAvatarView(
                            avatar, ctx, mIsDarkMode, bitmap, item.title, item.url
                        );
                    }
                }
            };
            if (allowNetworkProbe) {
                fm.loadFaviconAsync(item.url, cb);
            } else {
                fm.loadCachedFaviconAsync(item.url, cb);
            }
        }
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

    private String formatTimestamp(long timeMillis) {
        if (timeMillis <= 0) return "";
        Date d = new Date(timeMillis);
        if (timeMillis >= mRenderStartOfToday) {
            return mTimeOnlyFmt.format(d);
        } else if (timeMillis >= mRenderStartOfYesterday) {
            return "昨天 " + mTimeOnlyFmt.format(d);
        } else {
            return mMonthDayTimeFmt.format(d);
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
