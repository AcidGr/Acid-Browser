package com.acid.browser;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    public interface BookmarkIoListener {
        void onImportBookmarksRequested();
        void onExportBookmarksRequested();
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
        public final String folderKey;

        public PreparedEntry(String sectionHeader, BrowserDataStore.EntryItem item, int originalIndex) {
            this(sectionHeader, item, originalIndex, null);
        }

        public PreparedEntry(
            String sectionHeader,
            BrowserDataStore.EntryItem item,
            int originalIndex,
            String folderKey
        ) {
            this.sectionHeader = sectionHeader;
            this.item = item;
            this.originalIndex = originalIndex;
            this.folderKey = folderKey;
        }
    }

    private static final class RowSelectHolder {
        final LinearLayout rootCard;
        final AcidWidgets.CheckIndicator indicator;

        RowSelectHolder(LinearLayout rootCard, AcidWidgets.CheckIndicator indicator) {
            this.rootCard = rootCard;
            this.indicator = indicator;
        }
    }

    private final BrowserDataStore mDataStore;
    private final BrowserController mController;
    private final OnNavigateListener mNavigateListener;
    private BookmarkIoListener mBookmarkIoListener = null;

    private int mActiveTab = TAB_BOOKMARKS;
    private String mCurrentPageTitle = "";
    private String mCurrentPageUrl = "";
    private String mSearchQuery = "";
    private String mActiveFolderFilter = null; // null = All, "" = Uncategorized, otherwise exact folder path

    private boolean mMultiSelectMode = false;
    private final Set<Integer> mSelectedBookmarkIndices = new LinkedHashSet<Integer>();
    private final Set<String> mSelectedHistoryUrls = new LinkedHashSet<String>();
    private final Map<Integer, RowSelectHolder> mBookmarkSelectHolders = new LinkedHashMap<Integer, RowSelectHolder>();
    private final Map<String, List<RowSelectHolder>> mHistorySelectHolders = new LinkedHashMap<String, List<RowSelectHolder>>();
    private List<BrowserDataStore.EntryItem> mMultiSelectBookmarkSnapshot = null;
    private AcidWidgets.MultiSelectBar mMultiSelectBar = null;
    private boolean mSuppressStoreCallback = false;

    private List<BrowserDataStore.EntryItem> mCachedTabEntries = Collections.emptyList();
    private List<PreparedEntry> mPreparedEntries = Collections.emptyList();
    private LinearLayout mActiveListContainer = null;
    private boolean mActiveContainerIsBookmarks = false;
    private int mRenderedCount = 0;
    private String mLastRenderedSectionKey = null;
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

    public void setBookmarkIoListener(BookmarkIoListener listener) {
        mBookmarkIoListener = listener;
    }

    @Override
    protected void onResetToRootPage() {
        cancelPendingSearch();
        resetMultiSelectState();
        mSearchQuery = "";
        mActiveFolderFilter = null;
        setRootPage(buildActiveTabPage());
    }

    @Override
    public void dismiss() {
        resetMultiSelectState();
        super.dismiss();
    }

    @Override
    public boolean handleBackPressed() {
        if (mMultiSelectMode) {
            exitMultiSelectMode();
            return true;
        }
        return super.handleBackPressed();
    }

    public void prepareTab(int tab, String currentTitle, String currentUrl) {
        cancelPendingSearch();
        resetMultiSelectState();
        mActiveTab = tab;
        mCurrentPageTitle = currentTitle != null ? currentTitle : "";
        mCurrentPageUrl = currentUrl != null ? currentUrl : "";
        mSearchQuery = "";
        mActiveFolderFilter = null;
    }

    private void resetMultiSelectState() {
        mMultiSelectMode = false;
        mSelectedBookmarkIndices.clear();
        mSelectedHistoryUrls.clear();
        mBookmarkSelectHolders.clear();
        mHistorySelectHolders.clear();
        mMultiSelectBookmarkSnapshot = null;
        mMultiSelectBar = null;
    }

    public void onBookmarksStoreChanged() {
        if (mSuppressStoreCallback || !isShowing() || mActiveTab != TAB_BOOKMARKS) return;
        resetMultiSelectState();
        refreshActivePage();
    }

    public void onHistoryStoreChanged() {
        if (mSuppressStoreCallback || !isShowing() || mActiveTab != TAB_HISTORY) return;
        final int prevRendered = Math.max(INITIAL_BATCH_SIZE, mRenderedCount);
        final int scrollY = getScrollView() != null ? getScrollView().getScrollY() : 0;
        mCachedTabEntries = mDataStore.loadHistory();
        pruneStaleSelection(false);
        if (mActiveListContainer != null) {
            renderContainerEntries(mActiveListContainer, false, prevRendered);
        }
        syncMultiSelectChrome(false);
        if (scrollY > 0 && getScrollView() != null) {
            getScrollView().post(new Runnable() {
                @Override
                public void run() {
                    if (getScrollView() != null) {
                        getScrollView().scrollTo(0, scrollY);
                    }
                }
            });
        }
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
                mMultiSelectBar = null;
                final boolean isBookmarks = (mActiveTab == TAB_BOOKMARKS);
                mCachedTabEntries = isBookmarks ? mDataStore.loadBookmarks() : mDataStore.loadHistory();
                pruneStaleSelection(isBookmarks);

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
                                resetMultiSelectState();
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

                if (isBookmarks) {
                    buildBookmarkFolderFilterBar(ctx, pinned, container);
                }

                renderContainerEntries(container, isBookmarks);
                syncMultiSelectChrome(isBookmarks);
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

    private void buildBookmarkFolderFilterBar(
        final Context ctx,
        LinearLayout pinned,
        final LinearLayout container
    ) {
        Set<String> folderSet = new LinkedHashSet<String>();
        boolean hasUncategorized = false;
        if (mCachedTabEntries != null) {
            for (int i = 0; i < mCachedTabEntries.size(); i++) {
                BrowserDataStore.EntryItem item = mCachedTabEntries.get(i);
                if (item == null) continue;
                if (item.folder != null && !item.folder.isEmpty()) {
                    folderSet.add(item.folder);
                } else {
                    hasUncategorized = true;
                }
            }
        }
        if (folderSet.isEmpty()) {
            mActiveFolderFilter = null;
            return;
        }

        final List<String> folders = new ArrayList<String>(folderSet);
        if (mActiveFolderFilter != null) {
            if (mActiveFolderFilter.isEmpty()) {
                if (!hasUncategorized) mActiveFolderFilter = null;
            } else if (!folders.contains(mActiveFolderFilter)) {
                mActiveFolderFilter = null;
            }
        }

        final List<String> filterValues = new ArrayList<String>();
        final List<String> labels = new ArrayList<String>();
        filterValues.add(null);
        labels.add("全部");
        if (hasUncategorized) {
            filterValues.add("");
            labels.add("未分类");
        }
        for (int i = 0; i < folders.size(); i++) {
            String f = folders.get(i);
            filterValues.add(f);
            labels.add(f);
        }

        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams hsvLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        hsvLp.topMargin = AcidTheme.dp(ctx, 8);

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] chips = new TextView[labels.size()];
        final Runnable styleChips = new Runnable() {
            @Override
            public void run() {
                int accent = AcidTheme.accent(mIsDarkMode);
                for (int i = 0; i < chips.length; i++) {
                    String val = filterValues.get(i);
                    boolean sel = (val == null && mActiveFolderFilter == null)
                        || (val != null && val.equals(mActiveFolderFilter));
                    TextView chip = chips[i];
                    if (sel) {
                        chip.setBackground(AcidTheme.interactiveBg(
                            ctx, accent, AcidTheme.adjustPressFill(accent, mIsDarkMode), 0, AcidTheme.RADIUS_PILL
                        ));
                        chip.setTextColor(AcidTheme.onColor(accent));
                    } else {
                        chip.setBackground(AcidTheme.interactiveBg(
                            ctx,
                            AcidTheme.menuItemBg(mIsDarkMode),
                            AcidTheme.rowPressed(mIsDarkMode),
                            AcidTheme.border(mIsDarkMode),
                            AcidTheme.RADIUS_PILL
                        ));
                        chip.setTextColor(AcidTheme.muted(mIsDarkMode));
                    }
                }
            }
        };

        for (int i = 0; i < labels.size(); i++) {
            final String targetVal = filterValues.get(i);
            final TextView chip = new TextView(ctx);
            chip.setText(targetVal == null || targetVal.isEmpty() ? AcidLocale.tr(labels.get(i)) : labels.get(i));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            int pH = AcidTheme.dp(ctx, 12);
            int pV = AcidTheme.dp(ctx, 5);
            chip.setPadding(pH, pV, pH, pV);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean same = (targetVal == null && mActiveFolderFilter == null)
                        || (targetVal != null && targetVal.equals(mActiveFolderFilter));
                    if (!same) {
                        mActiveFolderFilter = targetVal;
                        styleChips.run();
                        getScrollView().scrollTo(0, 0);
                        renderContainerEntries(container, true);
                    }
                }
            });
            if (targetVal != null && !targetVal.isEmpty()) {
                chip.setOnLongClickListener(new OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        if (mMultiSelectMode) return true;
                        showFolderManageMenu(chip, targetVal);
                        return true;
                    }
                });
            }
            chips[i] = chip;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            if (i > 0) lp.leftMargin = AcidTheme.dp(ctx, 6);
            bar.addView(chip, lp);
        }
        styleChips.run();
        hsv.addView(bar);
        pinned.addView(hsv, hsvLp);
    }

    private boolean isCurrentPageBookmarkable() {
        return !TextUtils.isEmpty(mCurrentPageUrl)
            && !BrowserController.isLocalHomeUrl(mCurrentPageUrl)
            && NetscapeBookmarkCodec.isSafeBookmarkUrl(mCurrentPageUrl);
    }

    private static boolean sameBookmarkIdentity(BrowserDataStore.EntryItem a, BrowserDataStore.EntryItem b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        String urlA = a.url != null ? a.url : "";
        String urlB = b.url != null ? b.url : "";
        String folderA = a.folder != null ? a.folder : "";
        String folderB = b.folder != null ? b.folder : "";
        return urlA.equals(urlB) && folderA.equals(folderB);
    }

    private void pruneStaleSelection(boolean isBookmarks) {
        if (!mMultiSelectMode) return;
        if (mCachedTabEntries == null || mCachedTabEntries.isEmpty()) {
            resetMultiSelectState();
            return;
        }
        if (isBookmarks) {
            if (mMultiSelectBookmarkSnapshot == null
                || mMultiSelectBookmarkSnapshot.size() != mCachedTabEntries.size()) {
                resetMultiSelectState();
                return;
            }
            int max = mCachedTabEntries.size();
            for (Integer idx : mSelectedBookmarkIndices) {
                if (idx == null || idx.intValue() < 0 || idx.intValue() >= max) {
                    resetMultiSelectState();
                    return;
                }
                int i = idx.intValue();
                if (!sameBookmarkIdentity(mMultiSelectBookmarkSnapshot.get(i), mCachedTabEntries.get(i))) {
                    resetMultiSelectState();
                    return;
                }
            }
        } else {
            Set<String> liveUrls = new LinkedHashSet<String>();
            for (int i = 0; i < mCachedTabEntries.size(); i++) {
                BrowserDataStore.EntryItem item = mCachedTabEntries.get(i);
                if (item != null && !TextUtils.isEmpty(item.url)) {
                    liveUrls.add(item.url);
                }
            }
            mSelectedHistoryUrls.retainAll(liveUrls);
        }
    }

    private int getSelectedCount(boolean isBookmarks) {
        return isBookmarks ? mSelectedBookmarkIndices.size() : mSelectedHistoryUrls.size();
    }

    private void syncHeaderActionForActiveTab(boolean isBookmarks) {
        if (isBookmarks) {
            String label = isCurrentPageBookmarkable() ? "收藏 / 管理" : "添加 / 导入";
            setAction(AcidIcons.ICON_PLUS, label, 0, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    showBookmarksHeaderActionMenu(v);
                }
            });
        } else {
            if (mCachedTabEntries != null && !mCachedTabEntries.isEmpty()) {
                setAction(AcidIcons.ICON_MENU, "管理 / 清空", 0, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showHistoryHeaderActionMenu(v);
                    }
                });
            } else {
                hideAction();
            }
        }
    }

    private void syncMultiSelectChrome(final boolean isBookmarks) {
        final Context ctx = getContext();
        if (mMultiSelectMode) {
            setTitle("已选择 " + getSelectedCount(isBookmarks) + " 项");
            setAction(AcidIcons.ICON_CHECK, "完成", 0, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    exitMultiSelectMode();
                }
            });
            LinearLayout footer = getPinnedFooterContainer();
            if (mMultiSelectBar == null || footer.getChildCount() == 0) {
                footer.removeAllViews();
                mMultiSelectBar = new AcidWidgets.MultiSelectBar(
                    ctx,
                    mIsDarkMode,
                    new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            toggleSelectAllPrepared(isBookmarks);
                        }
                    },
                    isBookmarks ? "移至文件夹" : null,
                    isBookmarks ? new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            showBatchMoveBookmarksDialog();
                        }
                    } : null,
                    new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (isBookmarks) {
                                confirmBatchDeleteBookmarks();
                            } else {
                                confirmBatchDeleteHistory();
                            }
                        }
                    }
                );
                footer.addView(mMultiSelectBar.view);
            }
            updateMultiSelectBarState(isBookmarks);
            refreshScrollCeiling();
        } else {
            setTitle("书签与历史");
            clearPinnedFooter();
            mMultiSelectBar = null;
            refreshScrollCeiling();
            syncHeaderActionForActiveTab(isBookmarks);
        }
    }

    private void updateMultiSelectBarState(boolean isBookmarks) {
        if (mMultiSelectBar == null) return;
        boolean hasVisible = !mPreparedEntries.isEmpty();
        boolean allVisibleSelected = hasVisible && areAllPreparedSelected(isBookmarks);
        mMultiSelectBar.updateState(getSelectedCount(isBookmarks), allVisibleSelected, hasVisible);
    }

    private boolean areAllPreparedSelected(boolean isBookmarks) {
        if (mPreparedEntries.isEmpty()) return false;
        for (int i = 0; i < mPreparedEntries.size(); i++) {
            PreparedEntry pe = mPreparedEntries.get(i);
            if (isBookmarks) {
                if (!mSelectedBookmarkIndices.contains(Integer.valueOf(pe.originalIndex))) {
                    return false;
                }
            } else {
                if (!mSelectedHistoryUrls.contains(pe.item.url)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void enterMultiSelectMode(int preselectBookmarkIndex, String preselectHistoryUrl) {
        if (mCachedTabEntries == null || mCachedTabEntries.isEmpty()) {
            AcidFeedback.info(getContext(), mActiveTab == TAB_BOOKMARKS ? "暂无书签收藏" : "暂无历史浏览记录");
            return;
        }
        final boolean isBookmarks = (mActiveTab == TAB_BOOKMARKS);
        final int prevRendered = Math.max(INITIAL_BATCH_SIZE, mRenderedCount);
        final int scrollY = getScrollView() != null ? getScrollView().getScrollY() : 0;

        mMultiSelectMode = true;
        mSelectedBookmarkIndices.clear();
        mSelectedHistoryUrls.clear();
        mMultiSelectBookmarkSnapshot = isBookmarks
            ? new ArrayList<BrowserDataStore.EntryItem>(mCachedTabEntries)
            : null;
        if (isBookmarks && preselectBookmarkIndex >= 0 && preselectBookmarkIndex < mCachedTabEntries.size()) {
            mSelectedBookmarkIndices.add(Integer.valueOf(preselectBookmarkIndex));
        } else if (!isBookmarks && !TextUtils.isEmpty(preselectHistoryUrl)) {
            mSelectedHistoryUrls.add(preselectHistoryUrl);
        }
        mMultiSelectBar = null;

        if (mActiveListContainer != null) {
            renderContainerEntries(mActiveListContainer, isBookmarks, prevRendered);
        }
        syncMultiSelectChrome(isBookmarks);
        if (scrollY > 0 && getScrollView() != null) {
            getScrollView().post(new Runnable() {
                @Override
                public void run() {
                    if (getScrollView() != null) {
                        getScrollView().scrollTo(0, scrollY);
                    }
                }
            });
        }
    }

    private void exitMultiSelectMode() {
        if (!mMultiSelectMode) return;
        final boolean isBookmarks = (mActiveTab == TAB_BOOKMARKS);
        final int prevRendered = Math.max(INITIAL_BATCH_SIZE, mRenderedCount);
        final int scrollY = getScrollView() != null ? getScrollView().getScrollY() : 0;

        resetMultiSelectState();
        syncMultiSelectChrome(isBookmarks);
        if (mActiveListContainer != null) {
            renderContainerEntries(mActiveListContainer, isBookmarks, prevRendered);
        }
        if (scrollY > 0 && getScrollView() != null) {
            getScrollView().post(new Runnable() {
                @Override
                public void run() {
                    if (getScrollView() != null) {
                        getScrollView().scrollTo(0, scrollY);
                    }
                }
            });
        }
    }

    private void toggleEntrySelection(int index, BrowserDataStore.EntryItem item, boolean isBookmarks) {
        if (!mMultiSelectMode || item == null) return;
        final Context ctx = getContext();
        int accent = AcidTheme.accent(mIsDarkMode);
        if (isBookmarks) {
            Integer key = Integer.valueOf(index);
            boolean nextSelected = !mSelectedBookmarkIndices.contains(key);
            if (nextSelected) {
                mSelectedBookmarkIndices.add(key);
            } else {
                mSelectedBookmarkIndices.remove(key);
            }
            RowSelectHolder holder = mBookmarkSelectHolders.get(key);
            if (holder != null) {
                holder.indicator.setChecked(ctx, mIsDarkMode, nextSelected);
                AcidWidgets.updateListItemCardSelection(ctx, mIsDarkMode, holder.rootCard, nextSelected, accent);
            }
        } else {
            boolean nextSelected = !mSelectedHistoryUrls.contains(item.url);
            if (nextSelected) {
                mSelectedHistoryUrls.add(item.url);
            } else {
                mSelectedHistoryUrls.remove(item.url);
            }
            List<RowSelectHolder> holders = mHistorySelectHolders.get(item.url);
            if (holders != null) {
                for (int i = 0; i < holders.size(); i++) {
                    RowSelectHolder holder = holders.get(i);
                    holder.indicator.setChecked(ctx, mIsDarkMode, nextSelected);
                    AcidWidgets.updateListItemCardSelection(ctx, mIsDarkMode, holder.rootCard, nextSelected, accent);
                }
            }
        }
        setTitle("已选择 " + getSelectedCount(isBookmarks) + " 项");
        updateMultiSelectBarState(isBookmarks);
    }

    private void toggleSelectAllPrepared(boolean isBookmarks) {
        if (!mMultiSelectMode || mPreparedEntries.isEmpty()) return;
        final Context ctx = getContext();
        int accent = AcidTheme.accent(mIsDarkMode);
        boolean selectTarget = !areAllPreparedSelected(isBookmarks);

        for (int i = 0; i < mPreparedEntries.size(); i++) {
            PreparedEntry pe = mPreparedEntries.get(i);
            if (isBookmarks) {
                Integer key = Integer.valueOf(pe.originalIndex);
                if (selectTarget) {
                    mSelectedBookmarkIndices.add(key);
                } else {
                    mSelectedBookmarkIndices.remove(key);
                }
                RowSelectHolder holder = mBookmarkSelectHolders.get(key);
                if (holder != null) {
                    holder.indicator.setChecked(ctx, mIsDarkMode, selectTarget);
                    AcidWidgets.updateListItemCardSelection(ctx, mIsDarkMode, holder.rootCard, selectTarget, accent);
                }
            } else {
                if (selectTarget) {
                    mSelectedHistoryUrls.add(pe.item.url);
                } else {
                    mSelectedHistoryUrls.remove(pe.item.url);
                }
                List<RowSelectHolder> holders = mHistorySelectHolders.get(pe.item.url);
                if (holders != null) {
                    for (int h = 0; h < holders.size(); h++) {
                        RowSelectHolder holder = holders.get(h);
                        holder.indicator.setChecked(ctx, mIsDarkMode, selectTarget);
                        AcidWidgets.updateListItemCardSelection(ctx, mIsDarkMode, holder.rootCard, selectTarget, accent);
                    }
                }
            }
        }
        setTitle("已选择 " + getSelectedCount(isBookmarks) + " 项");
        updateMultiSelectBarState(isBookmarks);
    }

    private String defaultFolderForAdd() {
        return (mActiveFolderFilter != null && !mActiveFolderFilter.isEmpty()) ? mActiveFolderFilter : "";
    }

    private void showHistoryHeaderActionMenu(View anchor) {
        final Context ctx = getContext();
        List<AcidContextMenu.ActionItem> actions = new ArrayList<AcidContextMenu.ActionItem>();
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_CHECK, "批量选择", new Runnable() {
            @Override
            public void run() {
                enterMultiSelectMode(-1, null);
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_TRASH, "清空全部历史", true, new Runnable() {
            @Override
            public void run() {
                confirmClearAllHistory();
            }
        }));
        AcidContextMenu.showForAnchor(ctx, anchor, mIsDarkMode, AcidLocale.tr("历史操作"), actions);
    }

    private void showBookmarksHeaderActionMenu(View anchor) {
        final Context ctx = getContext();
        List<AcidContextMenu.ActionItem> actions = new ArrayList<AcidContextMenu.ActionItem>();
        if (isCurrentPageBookmarkable()) {
            actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_BOOKMARK, "收藏当前页", new Runnable() {
                @Override
                public void run() {
                    showBookmarkFormDialog(-1, mCurrentPageTitle, mCurrentPageUrl, defaultFolderForAdd());
                }
            }));
        }
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_PLUS, "手动添加书签", new Runnable() {
            @Override
            public void run() {
                showBookmarkFormDialog(-1, "", "", defaultFolderForAdd());
            }
        }));
        if (mCachedTabEntries != null && !mCachedTabEntries.isEmpty()) {
            actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_CHECK, "批量选择", new Runnable() {
                @Override
                public void run() {
                    enterMultiSelectMode(-1, null);
                }
            }));
        }
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_FILE, "导入书签 (.html / .json)", new Runnable() {
            @Override
            public void run() {
                if (mBookmarkIoListener != null) {
                    mBookmarkIoListener.onImportBookmarksRequested();
                }
            }
        }));
        if (mCachedTabEntries != null && !mCachedTabEntries.isEmpty()) {
            actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_DOWNLOAD, "导出书签 (.html)", new Runnable() {
                @Override
                public void run() {
                    if (mBookmarkIoListener != null) {
                        mBookmarkIoListener.onExportBookmarksRequested();
                    }
                }
            }));
        }
        AcidContextMenu.showForAnchor(ctx, anchor, mIsDarkMode, AcidLocale.tr("书签操作"), actions);
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
        return buildPreparedEntries(rawList, isBookmarks, searchQuery, null, nowMillis);
    }

    public static List<PreparedEntry> buildPreparedEntries(
        List<BrowserDataStore.EntryItem> rawList,
        boolean isBookmarks,
        String searchQuery,
        String folderFilter,
        long nowMillis
    ) {
        if (rawList == null || rawList.isEmpty()) {
            return Collections.emptyList();
        }
        boolean hasAnyFolder = false;
        if (isBookmarks) {
            for (int i = 0; i < rawList.size(); i++) {
                BrowserDataStore.EntryItem item = rawList.get(i);
                if (item != null && item.folder != null && !item.folder.isEmpty()) {
                    hasAnyFolder = true;
                    break;
                }
            }
        }
        String normFolderFilter = folderFilter;
        if (normFolderFilter != null && !normFolderFilter.isEmpty()) {
            normFolderFilter = NetscapeBookmarkCodec.normalizeFolderPath(normFolderFilter);
        }

        String q = searchQuery != null ? searchQuery.toLowerCase(Locale.ROOT).trim() : "";
        List<BrowserDataStore.EntryItem> filteredList = new ArrayList<BrowserDataStore.EntryItem>();
        List<Integer> originalIndices = new ArrayList<Integer>();

        for (int rawIdx = 0; rawIdx < rawList.size(); rawIdx++) {
            BrowserDataStore.EntryItem item = rawList.get(rawIdx);
            if (item == null) continue;
            if (isBookmarks && q.isEmpty() && normFolderFilter != null) {
                String f = item.folder != null ? item.folder : "";
                if (!normFolderFilter.equals(f)) {
                    continue;
                }
            }
            if (q.isEmpty()
                || (item.title != null && item.title.toLowerCase(Locale.ROOT).contains(q))
                || (item.url != null && item.url.toLowerCase(Locale.ROOT).contains(q))
                || (isBookmarks && item.folder != null && item.folder.toLowerCase(Locale.ROOT).contains(q))) {
                filteredList.add(item);
                originalIndices.add(Integer.valueOf(rawIdx));
            }
        }
        if (filteredList.isEmpty()) {
            return Collections.emptyList();
        }

        List<PreparedEntry> prepared = new ArrayList<PreparedEntry>(filteredList.size());
        if (!q.isEmpty()) {
            String headerText = "搜索结果 (" + filteredList.size() + ")";
            for (int i = 0; i < filteredList.size(); i++) {
                prepared.add(new PreparedEntry(
                    headerText,
                    filteredList.get(i),
                    originalIndices.get(i).intValue(),
                    null
                ));
            }
            return prepared;
        }

        if (isBookmarks) {
            if (!hasAnyFolder && normFolderFilter == null) {
                String headerText = "全部书签 (" + filteredList.size() + ")";
                for (int i = 0; i < filteredList.size(); i++) {
                    prepared.add(new PreparedEntry(
                        headerText,
                        filteredList.get(i),
                        originalIndices.get(i).intValue(),
                        null
                    ));
                }
                return prepared;
            }
            if (normFolderFilter != null) {
                String headerText = normFolderFilter.isEmpty()
                    ? ("未分类 (" + filteredList.size() + ")")
                    : (normFolderFilter + " (" + filteredList.size() + ")");
                String fKey = normFolderFilter.isEmpty() ? null : normFolderFilter;
                for (int i = 0; i < filteredList.size(); i++) {
                    prepared.add(new PreparedEntry(
                        headerText,
                        filteredList.get(i),
                        originalIndices.get(i).intValue(),
                        fKey
                    ));
                }
                return prepared;
            }

            List<PreparedEntry> uncategorized = new ArrayList<PreparedEntry>();
            Map<String, List<PreparedEntry>> grouped = new LinkedHashMap<String, List<PreparedEntry>>();
            for (int i = 0; i < filteredList.size(); i++) {
                BrowserDataStore.EntryItem item = filteredList.get(i);
                int origIdx = originalIndices.get(i).intValue();
                if (item.folder == null || item.folder.isEmpty()) {
                    uncategorized.add(new PreparedEntry(null, item, origIdx, null));
                } else {
                    List<PreparedEntry> bucket = grouped.get(item.folder);
                    if (bucket == null) {
                        bucket = new ArrayList<PreparedEntry>();
                        grouped.put(item.folder, bucket);
                    }
                    bucket.add(new PreparedEntry(null, item, origIdx, item.folder));
                }
            }
            if (!uncategorized.isEmpty()) {
                String uncHeader = "未分类 (" + uncategorized.size() + ")";
                for (int i = 0; i < uncategorized.size(); i++) {
                    PreparedEntry pe = uncategorized.get(i);
                    prepared.add(new PreparedEntry(uncHeader, pe.item, pe.originalIndex, null));
                }
            }
            for (Map.Entry<String, List<PreparedEntry>> entry : grouped.entrySet()) {
                String folderName = entry.getKey();
                List<PreparedEntry> bucket = entry.getValue();
                String header = folderName + " (" + bucket.size() + ")";
                for (int i = 0; i < bucket.size(); i++) {
                    PreparedEntry pe = bucket.get(i);
                    prepared.add(new PreparedEntry(header, pe.item, pe.originalIndex, folderName));
                }
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
        renderContainerEntries(container, isBookmarks, INITIAL_BATCH_SIZE);
    }

    private void renderContainerEntries(LinearLayout container, boolean isBookmarks, int initialBatchSize) {
        final Context ctx = getContext();
        mRenderGeneration++;
        mActiveListContainer = container;
        mActiveContainerIsBookmarks = isBookmarks;
        mRenderedCount = 0;
        mLastRenderedSectionKey = null;
        mLoadMoreFooterView = null;
        mPendingLoadMorePosted = false;
        mBookmarkSelectHolders.clear();
        mHistorySelectHolders.clear();
        container.removeAllViews();

        long now = System.currentTimeMillis();
        mRenderStartOfToday = getStartOfDay(now);
        mRenderStartOfYesterday = mRenderStartOfToday - 86400000L;
        mTimeOnlyFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        mMonthDayTimeFmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

        List<BrowserDataStore.EntryItem> rawList = mCachedTabEntries != null
            ? mCachedTabEntries
            : Collections.<BrowserDataStore.EntryItem>emptyList();
        mPreparedEntries = buildPreparedEntries(rawList, isBookmarks, mSearchQuery, isBookmarks ? mActiveFolderFilter : null, now);

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
            if (mMultiSelectMode) {
                updateMultiSelectBarState(isBookmarks);
            }
            return;
        }

        appendNextBatch(Math.max(INITIAL_BATCH_SIZE, initialBatchSize));
        if (mMultiSelectMode) {
            updateMultiSelectBarState(isBookmarks);
        }
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
                final PreparedEntry entry = mPreparedEntries.get(i);
                String sectionKey = entry.folderKey != null
                    ? ("F:" + entry.folderKey)
                    : ("S:" + entry.sectionHeader);
                if (entry.sectionHeader != null && !sectionKey.equals(mLastRenderedSectionKey)) {
                    if (!mMultiSelectMode && entry.folderKey != null && !entry.folderKey.isEmpty()) {
                        final LinearLayout headerRow = new LinearLayout(ctx);
                        headerRow.setOrientation(LinearLayout.HORIZONTAL);
                        headerRow.setGravity(Gravity.CENTER_VERTICAL);
                        TextView tvHeader = AcidWidgets.sectionHeader(ctx, mIsDarkMode, entry.sectionHeader);
                        tvHeader.setSingleLine(true);
                        tvHeader.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                        headerRow.addView(tvHeader, new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                        ));
                        TextView manageBtn = AcidWidgets.textAction(
                            ctx, mIsDarkMode, "管理", AcidTheme.accent(mIsDarkMode),
                            new OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    showFolderManageMenu(v, entry.folderKey);
                                }
                            }
                        );
                        headerRow.addView(manageBtn);
                        headerRow.setOnLongClickListener(new OnLongClickListener() {
                            @Override
                            public boolean onLongClick(View v) {
                                showFolderManageMenu(headerRow, entry.folderKey);
                                return true;
                            }
                        });
                        mActiveListContainer.addView(headerRow);
                    } else {
                        mActiveListContainer.addView(AcidWidgets.sectionHeader(ctx, mIsDarkMode, entry.sectionHeader));
                    }
                    mLastRenderedSectionKey = sectionKey;
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
        String subtitle = item.url;
        if (isBookmarks && !mSearchQuery.trim().isEmpty() && item.folder != null && !item.folder.isEmpty()) {
            subtitle = "[" + item.folder + "] " + item.url;
        }
        AcidWidgets.ListRow rowBuilder = new AcidWidgets.ListRow()
            .title(item.title)
            .subtitle(subtitle);

        bindRowFavicon(ctx, rowBuilder, item, isBookmarks);

        final AcidWidgets.CheckIndicator checkIndicator;
        if (mMultiSelectMode) {
            boolean selected = isBookmarks
                ? mSelectedBookmarkIndices.contains(Integer.valueOf(index))
                : mSelectedHistoryUrls.contains(item.url);
            checkIndicator = new AcidWidgets.CheckIndicator(ctx, mIsDarkMode, selected);
            rowBuilder.selected(selected, AcidTheme.accent(mIsDarkMode))
                .trailingView(checkIndicator.view);
        } else {
            checkIndicator = null;
            if (!isBookmarks && item.time > 0) {
                rowBuilder.trailingText(ctx, mIsDarkMode, formatTimestamp(item.time));
            }
        }

        final LinearLayout row = rowBuilder.build(ctx, mIsDarkMode);
        if (mMultiSelectMode && checkIndicator != null) {
            RowSelectHolder holder = new RowSelectHolder(row, checkIndicator);
            if (isBookmarks) {
                mBookmarkSelectHolders.put(Integer.valueOf(index), holder);
            } else {
                List<RowSelectHolder> list = mHistorySelectHolders.get(item.url);
                if (list == null) {
                    list = new ArrayList<RowSelectHolder>(1);
                    mHistorySelectHolders.put(item.url, list);
                }
                list.add(holder);
            }
        }

        row.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mMultiSelectMode) {
                    toggleEntrySelection(index, item, isBookmarks);
                    return;
                }
                closeEntireModalFlow();
                if (mNavigateListener != null) {
                    mNavigateListener.onNavigateUrl(item.url);
                }
            }
        });
        row.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (mMultiSelectMode) {
                    toggleEntrySelection(index, item, isBookmarks);
                    return true;
                }
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
                showBookmarkFormDialog(index, item.title, item.url, item.folder);
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_CHECK, "批量选择", new Runnable() {
            @Override
            public void run() {
                enterMultiSelectMode(index, null);
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
                showBookmarkFormDialog(-1, item.title, item.url, "");
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_CHECK, "批量选择", new Runnable() {
            @Override
            public void run() {
                enterMultiSelectMode(-1, item.url);
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_TRASH, "删除此条记录", true, new Runnable() {
            @Override
            public void run() {
                mSuppressStoreCallback = true;
                try {
                    mDataStore.removeHistoryItem(item.url);
                } finally {
                    mSuppressStoreCallback = false;
                }
                refreshActivePage();
                AcidFeedback.info(ctx, "已删除历史记录");
            }
        }));
        AcidContextMenu.showForAnchor(ctx, anchor, mIsDarkMode, item.title, actions);
    }

    private void addFolderQuickPickChips(
        final Context ctx,
        LinearLayout layout,
        final EditText etFolder
    ) {
        List<String> existingFolders = mDataStore.listBookmarkFolders();
        if (existingFolders.isEmpty()) return;

        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(ctx);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);

        List<String> pickers = new ArrayList<String>(existingFolders.size() + 1);
        pickers.add("");
        pickers.addAll(existingFolders);
        for (int i = 0; i < pickers.size(); i++) {
            final String val = pickers.get(i);
            TextView pill = new TextView(ctx);
            pill.setText(val.isEmpty() ? AcidLocale.tr("未分类") : val);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_BADGE);
            pill.setTypeface(Typeface.DEFAULT_BOLD);
            pill.setTextColor(AcidTheme.muted(mIsDarkMode));
            pill.setPadding(AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 4), AcidTheme.dp(ctx, 10), AcidTheme.dp(ctx, 4));
            pill.setBackground(AcidTheme.interactiveBg(
                ctx,
                AcidTheme.subtle(mIsDarkMode),
                AcidTheme.rowPressed(mIsDarkMode),
                AcidTheme.border(mIsDarkMode),
                AcidTheme.RADIUS_PILL
            ));
            pill.setClickable(true);
            pill.setFocusable(true);
            pill.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    etFolder.setText(val);
                    if (!val.isEmpty()) {
                        etFolder.setSelection(val.length());
                    }
                }
            });
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            if (i > 0) pLp.leftMargin = AcidTheme.dp(ctx, 6);
            chipRow.addView(pill, pLp);
        }
        hsv.addView(chipRow);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        hLp.bottomMargin = AcidTheme.dp(ctx, 6);
        layout.addView(hsv, hLp);
    }

    private void showBookmarkFormDialog(
        final int editIndex,
        String initTitle,
        String initUrl,
        String initFolder
    ) {
        final boolean isEdit = (editIndex >= 0);
        final Context ctx = getContext();
        final EditText etTitle = AcidWidgets.inputField(ctx, mIsDarkMode, initTitle, "网站标题", true);
        final EditText etUrl = AcidWidgets.inputField(ctx, mIsDarkMode, initUrl, "网址 (URL)", true);
        etUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText etFolder = AcidWidgets.inputField(
            ctx,
            mIsDarkMode,
            NetscapeBookmarkCodec.normalizeFolderPath(initFolder),
            "留空为未分类，支持多级如 开发/开源",
            true
        );

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AcidTheme.dp(ctx, 4), AcidTheme.dp(ctx, 4), AcidTheme.dp(ctx, 4), AcidTheme.dp(ctx, 4));

        layout.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "网站标题"));
        layout.addView(etTitle);
        layout.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "网址 (URL)"));
        layout.addView(etUrl);
        layout.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "分类文件夹 (选填)"));
        layout.addView(etFolder);
        addFolderQuickPickChips(ctx, layout, etFolder);

        new AcidDialog(ctx, mIsDarkMode)
            .setTitle(isEdit ? "编辑书签" : "添加书签")
            .setCustomView(layout)
            .setPositiveButton(isEdit ? "保存" : "添加", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    String t = etTitle.getText() != null ? etTitle.getText().toString().trim() : "";
                    String u = etUrl.getText() != null ? etUrl.getText().toString().trim() : "";
                    String f = etFolder.getText() != null ? etFolder.getText().toString().trim() : "";
                    String validUrl = NetscapeBookmarkCodec.normalizeUserBookmarkUrl(u);
                    if (validUrl == null) {
                        AcidFeedback.warn(ctx, TextUtils.isEmpty(u) ? "网址不能为空" : "请输入有效的网址");
                        return false;
                    }
                    String normFolder = NetscapeBookmarkCodec.normalizeFolderPath(f);
                    if (isEdit) {
                        mDataStore.updateBookmark(editIndex, t, validUrl, normFolder);
                    } else {
                        mDataStore.addBookmark(t, validUrl, normFolder);
                    }
                    if (mActiveTab == TAB_BOOKMARKS) {
                        if (!normFolder.isEmpty() && mActiveFolderFilter != null && !mActiveFolderFilter.equals(normFolder)) {
                            mActiveFolderFilter = normFolder;
                        }
                        refreshActivePage();
                    }
                    AcidFeedback.ok(ctx, isEdit ? "书签已更新" : "已添加至书签收藏");
                    return true;
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void updateActiveFilterOnRename(String oldFolder, String newFolder) {
        if (mActiveFolderFilter == null || oldFolder == null) return;
        if (oldFolder.equals(mActiveFolderFilter)) {
            mActiveFolderFilter = newFolder;
        } else if (mActiveFolderFilter.startsWith(oldFolder + "/")) {
            mActiveFolderFilter = NetscapeBookmarkCodec.normalizeFolderPath(
                newFolder + mActiveFolderFilter.substring(oldFolder.length())
            );
        }
    }

    private void clearActiveFilterIfMatches(String targetFolder) {
        if (mActiveFolderFilter == null || targetFolder == null) return;
        if (targetFolder.equals(mActiveFolderFilter)
            || mActiveFolderFilter.startsWith(targetFolder + "/")) {
            mActiveFolderFilter = null;
        }
    }

    private void showFolderManageMenu(View anchor, final String folder) {
        if (TextUtils.isEmpty(folder)) return;
        final Context ctx = getContext();
        List<AcidContextMenu.ActionItem> actions = new ArrayList<AcidContextMenu.ActionItem>();
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_EDIT, "重命名文件夹", new Runnable() {
            @Override
            public void run() {
                AcidDialogs.inputSingleLine(
                    ctx,
                    mIsDarkMode,
                    "重命名文件夹",
                    "文件夹名称",
                    folder,
                    "输入新文件夹名称 (支持 A/B)",
                    0,
                    "保存",
                    "文件夹名称不能为空",
                    new AcidDialogs.InputSubmitListener() {
                        @Override
                        public boolean onSubmit(String trimmedValue) {
                            String next = NetscapeBookmarkCodec.normalizeFolderPath(trimmedValue);
                            if (next.isEmpty()) {
                                AcidFeedback.warn(ctx, "文件夹名称不能为空");
                                return false;
                            }
                            if (!next.equals(folder)) {
                                int count = mDataStore.renameBookmarkFolder(folder, next);
                                updateActiveFilterOnRename(folder, next);
                                refreshActivePage();
                                AcidFeedback.ok(ctx, "已重命名文件夹 (" + count + " 条书签)");
                            }
                            return true;
                        }
                    }
                );
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_RETURN, "解散文件夹 (移至未分类)", new Runnable() {
            @Override
            public void run() {
                AcidDialogs.confirm(
                    ctx,
                    mIsDarkMode,
                    "解散文件夹 (移至未分类)",
                    "确定解散文件夹「" + folder + "」吗？其中的书签将移至上级或未分类，不会被删除。",
                    "解散",
                    new Runnable() {
                        @Override
                        public void run() {
                            int count = mDataStore.dissolveBookmarkFolder(folder);
                            clearActiveFilterIfMatches(folder);
                            refreshActivePage();
                            AcidFeedback.ok(ctx, "已解散文件夹 (" + count + " 条书签)");
                        }
                    }
                );
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_TRASH, "删除文件夹及书签", true, new Runnable() {
            @Override
            public void run() {
                AcidDialogs.confirmDanger(
                    ctx,
                    mIsDarkMode,
                    "删除文件夹及书签",
                    "确定要删除文件夹「" + folder + "」及其中的全部书签吗？此操作无法撤销。",
                    "删除",
                    new Runnable() {
                        @Override
                        public void run() {
                            int count = mDataStore.deleteBookmarkFolder(folder);
                            clearActiveFilterIfMatches(folder);
                            refreshActivePage();
                            AcidFeedback.info(ctx, "已删除文件夹及 " + count + " 条书签");
                        }
                    }
                );
            }
        }));
        AcidContextMenu.showForAnchor(ctx, anchor, mIsDarkMode, folder, actions);
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

    private void showBatchMoveBookmarksDialog() {
        if (mSelectedBookmarkIndices.isEmpty()) return;
        final Set<Integer> targets = new LinkedHashSet<Integer>(mSelectedBookmarkIndices);
        final Context ctx = getContext();

        final EditText etFolder = AcidWidgets.inputField(
            ctx,
            mIsDarkMode,
            defaultFolderForAdd(),
            "留空为未分类，支持多级如 开发/开源",
            true
        );

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AcidTheme.dp(ctx, 4), AcidTheme.dp(ctx, 4), AcidTheme.dp(ctx, 4), AcidTheme.dp(ctx, 4));
        layout.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "分类文件夹 (选填)"));
        layout.addView(etFolder);
        addFolderQuickPickChips(ctx, layout, etFolder);

        new AcidDialog(ctx, mIsDarkMode)
            .setTitle("批量移至文件夹")
            .setMessage("将选中的 " + targets.size() + " 条书签移至目标文件夹（留空则移至未分类）：")
            .setCustomView(layout)
            .setPositiveButton("移动", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    String rawFolder = etFolder.getText() != null ? etFolder.getText().toString().trim() : "";
                    String normFolder = NetscapeBookmarkCodec.normalizeFolderPath(rawFolder);
                    int moved = mDataStore.moveBookmarksToFolder(targets, normFolder);
                    resetMultiSelectState();
                    if (!normFolder.isEmpty() && mActiveFolderFilter != null && !mActiveFolderFilter.equals(normFolder)) {
                        mActiveFolderFilter = normFolder;
                    }
                    refreshActivePage();
                    AcidFeedback.ok(
                        ctx,
                        normFolder.isEmpty()
                            ? ("已将 " + moved + " 条书签移至未分类")
                            : ("已将 " + moved + " 条书签移至「" + normFolder + "」")
                    );
                    return true;
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void confirmBatchDeleteBookmarks() {
        if (mSelectedBookmarkIndices.isEmpty()) return;
        final Set<Integer> targets = new LinkedHashSet<Integer>(mSelectedBookmarkIndices);
        final Context ctx = getContext();
        AcidDialogs.confirmDanger(
            ctx,
            mIsDarkMode,
            "批量删除书签",
            "确定要删除选中的 " + targets.size() + " 条书签吗？此操作无法撤销。",
            "删除",
            new Runnable() {
                @Override
                public void run() {
                    int removed = mDataStore.removeBookmarks(targets);
                    resetMultiSelectState();
                    refreshActivePage();
                    AcidFeedback.info(ctx, "已删除 " + removed + " 条书签");
                }
            }
        );
    }

    private void confirmBatchDeleteHistory() {
        if (mSelectedHistoryUrls.isEmpty()) return;
        final Set<String> targets = new LinkedHashSet<String>(mSelectedHistoryUrls);
        final Context ctx = getContext();
        AcidDialogs.confirmDanger(
            ctx,
            mIsDarkMode,
            "批量删除历史",
            "确定要删除选中的 " + targets.size() + " 条历史浏览记录吗？",
            "删除",
            new Runnable() {
                @Override
                public void run() {
                    int removed;
                    resetMultiSelectState();
                    mSuppressStoreCallback = true;
                    try {
                        removed = mDataStore.removeHistoryItems(targets);
                    } finally {
                        mSuppressStoreCallback = false;
                    }
                    refreshActivePage();
                    AcidFeedback.info(ctx, "已删除 " + removed + " 条历史记录");
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
                    resetMultiSelectState();
                    mSuppressStoreCallback = true;
                    try {
                        mDataStore.clearHistory();
                    } finally {
                        mSuppressStoreCallback = false;
                    }
                    refreshActivePage();
                    AcidFeedback.ok(ctx, "已清空历史浏览记录");
                }
            }
        );
    }
}
