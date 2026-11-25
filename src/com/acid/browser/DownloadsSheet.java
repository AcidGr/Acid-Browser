package com.acid.browser;

import android.app.Dialog;
import android.app.DownloadManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.acid.browser.download.DownloadCoordinator;
import com.acid.browser.download.DownloadItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Download Manager Sheet built on {@link AcidBottomSheetView}'s {@link SheetPage} template,
 * {@link AcidWidgets#filterChipBar}, {@link AcidWidgets.ListRow}, and {@link AcidContextMenu}.
 */
public class DownloadsSheet extends AcidBottomSheetView {

    private static final int[] CAT_IDS = new int[]{
        DownloadItem.CAT_ALL,
        DownloadItem.CAT_APK,
        DownloadItem.CAT_ARCHIVE,
        DownloadItem.CAT_DOC,
        DownloadItem.CAT_IMAGE,
        DownloadItem.CAT_VIDEO,
        DownloadItem.CAT_AUDIO,
        DownloadItem.CAT_OTHER
    };

    private static final String[] CAT_LABELS = new String[]{
        "全部", "安装包", "压缩包", "文档", "图片", "视频", "音频", "其它"
    };

    private static final class DownloadRowHolder {
        final LinearLayout rootCard;
        final TextView infoView;
        final ProgressBar progressBar;
        final AcidIcons.SvgIconView iconView;
        final AcidWidgets.CheckIndicator checkIndicator;

        DownloadRowHolder(
            LinearLayout rootCard,
            TextView infoView,
            ProgressBar progressBar,
            AcidIcons.SvgIconView iconView,
            AcidWidgets.CheckIndicator checkIndicator
        ) {
            this.rootCard = rootCard;
            this.infoView = infoView;
            this.progressBar = progressBar;
            this.iconView = iconView;
            this.checkIndicator = checkIndicator;
        }
    }

    private static long rowKey(DownloadItem it) {
        return it.downloadId > 0 ? it.downloadId : -Math.abs(it.timestamp);
    }

    private static int categoryIcon(DownloadItem it) {
        switch (it.getCategory()) {
            case DownloadItem.CAT_APK: return AcidIcons.ICON_FILE_APK;
            case DownloadItem.CAT_ARCHIVE: return AcidIcons.ICON_FILE_ARCHIVE;
            case DownloadItem.CAT_DOC: return AcidIcons.ICON_FILE_DOC;
            case DownloadItem.CAT_IMAGE: return AcidIcons.ICON_FILE_IMAGE;
            case DownloadItem.CAT_VIDEO: return AcidIcons.ICON_FILE_VIDEO;
            case DownloadItem.CAT_AUDIO: return AcidIcons.ICON_FILE_AUDIO;
            default: return AcidIcons.ICON_FILE_OTHER;
        }
    }

    private final DownloadCoordinator mDownloads;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private Runnable mProgressPoller = null;
    /** Interval between download-status polls while the sheet is open. */
    private static final long PROGRESS_POLL_INTERVAL_MS = 500L;

    private int mSelectedCategoryIndex = 0;
    private String mSearchKeyword = "";
    private final Map<Long, DownloadRowHolder> mDownloadRows = new LinkedHashMap<Long, DownloadRowHolder>();

    private boolean mMultiSelectMode = false;
    private final Set<Long> mSelectedDownloadIds = new LinkedHashSet<Long>();
    private List<DownloadItem> mCurrentVisibleDownloads = new ArrayList<DownloadItem>();
    private AcidWidgets.MultiSelectBar mMultiSelectBar = null;

    public DownloadsSheet(Context context, DownloadCoordinator downloads) {
        super(context);
        mDownloads = downloads;
        setRootPage(buildDownloadsPage());
    }

    @Override
    public void dismiss() {
        stopProgressPolling();
        resetMultiSelectState();
        super.dismiss();
    }

    @Override
    protected void onResetToRootPage() {
        resetMultiSelectState();
        setRootPage(buildDownloadsPage());
    }

    @Override
    public boolean handleBackPressed() {
        if (mMultiSelectMode) {
            exitMultiSelectMode();
            return true;
        }
        return super.handleBackPressed();
    }

    private void resetMultiSelectState() {
        mMultiSelectMode = false;
        mSelectedDownloadIds.clear();
        mMultiSelectBar = null;
    }

    @Override
    public void show() {
        if (getPageDepth() == 0) {
            setRootPage(buildDownloadsPage());
        }
        super.show();
        startProgressPolling();
    }

    public void refreshDownloads() {
        if (isShowing()) {
            updateDownloadListRows();
        }
    }

    private SheetPage buildDownloadsPage() {
        return new SheetPage("下载管理", new PageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container) {
                final Context ctx = getContext();
                mMultiSelectBar = null;

                // 1. Pinned Search Bar
                final EditText etSearch = new EditText(ctx);
                etSearch.setText(mSearchKeyword);
                LinearLayout searchBox = AcidWidgets.searchBox(ctx, mIsDarkMode, etSearch, "搜索下载");
                LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                sbLp.bottomMargin = AcidTheme.dp(ctx, 8);
                pinned.addView(searchBox, sbLp);

                // 2. Pinned Category Filter Chips (using AcidTheme.RADIUS_PILL & onColor contrast)
                pinned.addView(AcidWidgets.filterChipBar(
                    ctx, mIsDarkMode, CAT_LABELS, mSelectedCategoryIndex,
                    new AcidWidgets.OnTabSelectedListener() {
                        @Override
                        public void onTabSelected(int index) {
                            mSelectedCategoryIndex = index;
                            if (getScrollView() != null) getScrollView().scrollTo(0, 0);
                            updateDownloadListRows();
                        }
                    }
                ), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ));

                etSearch.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                    @Override
                    public void afterTextChanged(Editable s) {
                        String nextKw = s != null ? s.toString().trim() : "";
                        if (mSearchKeyword.equals(nextKw)) return;
                        mSearchKeyword = nextKw;
                        if (getScrollView() != null) getScrollView().scrollTo(0, 0);
                        updateDownloadListRows();
                    }
                });

                updateDownloadListRows();
            }
        })
            .withMinContentHeightDp(280f)
            .withOnPause(new Runnable() {
                @Override
                public void run() {
                    stopProgressPolling();
                }
            });
    }

    private void startProgressPolling() {
        stopProgressPolling();
        mProgressPoller = new Runnable() {
            @Override
            public void run() {
                if (!isShowing() || mDownloads == null) {
                    stopProgressPolling();
                    return;
                }
                // Self-scheduling poll: the next tick is armed here, before the sync is requested,
                // so it no longer depends on the callback arriving. DownloadCoordinator drops the
                // callback when a sync is already in flight (guarding against stacked
                // DownloadManager passes), and the old callback-driven chain stopped forever when
                // that happened — typically right as a download completed, freezing the list.
                mMainHandler.postDelayed(mProgressPoller, PROGRESS_POLL_INTERVAL_MS);
                mDownloads.syncDownloadStatusAsync(new DownloadCoordinator.DownloadSyncCallback() {
                    @Override
                    public void onDownloadSyncDone(boolean anyActive) {
                        if (!isShowing()) return;
                        refreshDownloadProgressInPlace();
                        if (!anyActive) {
                            // Nothing running any more: do one full rebuild so finished rows pick
                            // up their final state, then let the timer wind down.
                            updateDownloadListRows();
                            stopProgressPolling();
                        }
                    }
                });
            }
        };
        mMainHandler.postDelayed(mProgressPoller, PROGRESS_POLL_INTERVAL_MS);
    }

    private void pauseDownloadWithFeedback(Context ctx, DownloadItem it) {
        if (mDownloads == null || it == null) return;
        if (mDownloads.pauseDownload(it.downloadId)) {
            AcidFeedback.info(ctx, "已暂停: " + it.fileName);
        } else {
            AcidFeedback.info(ctx, "系统下载器任务暂不支持手动暂停");
        }
        updateDownloadListRows();
    }

    private void resumeDownloadWithFeedback(Context ctx, DownloadItem it, boolean isRetry) {
        if (mDownloads == null || it == null) return;
        if (mDownloads.resumeDownload(it.downloadId)) {
            if (isRetry) {
                AcidFeedback.info(ctx, "正在重试下载: " + it.fileName);
            } else {
                AcidFeedback.ok(ctx, "已恢复下载: " + it.fileName);
            }
            updateDownloadListRows();
            startProgressPolling();
        } else {
            AcidFeedback.info(ctx, "该任务由系统下载器托管或已完成");
            updateDownloadListRows();
        }
    }

    private void stopProgressPolling() {
        if (mProgressPoller != null) {
            mMainHandler.removeCallbacks(mProgressPoller);
            mProgressPoller = null;
        }
    }

    private void updateDownloadListRows() {
        LinearLayout itemsContainer = getContentContainer();
        if (itemsContainer == null) return;

        final int scrollY = getScrollView() != null ? getScrollView().getScrollY() : 0;
        itemsContainer.removeAllViews();
        mDownloadRows.clear();

        final Context ctx = getContext();
        int selectedCat = (mSelectedCategoryIndex >= 0 && mSelectedCategoryIndex < CAT_IDS.length)
            ? CAT_IDS[mSelectedCategoryIndex]
            : DownloadItem.CAT_ALL;

        List<DownloadItem> allList = mDownloads != null ? mDownloads.getDownloads() : null;
        if (mMultiSelectMode) {
            if (allList == null || allList.isEmpty()) {
                resetMultiSelectState();
            } else {
                Set<Long> liveIds = new LinkedHashSet<Long>();
                for (int i = 0; i < allList.size(); i++) {
                    DownloadItem it = allList.get(i);
                    if (it != null) {
                        liveIds.add(Long.valueOf(it.downloadId));
                    }
                }
                mSelectedDownloadIds.retainAll(liveIds);
            }
        }

        List<DownloadItem> list = new ArrayList<DownloadItem>();
        if (allList != null) {
            for (DownloadItem it : allList) {
                if (selectedCat != DownloadItem.CAT_ALL && it.getCategory() != selectedCat) {
                    continue;
                }
                if (!mSearchKeyword.isEmpty()) {
                    String fn = it.fileName != null ? it.fileName.toLowerCase() : "";
                    if (!fn.contains(mSearchKeyword.toLowerCase())) {
                        continue;
                    }
                }
                list.add(it);
            }
        }
        mCurrentVisibleDownloads = list;
        syncDownloadsChrome(allList);

        if (list.isEmpty()) {
            itemsContainer.addView(AcidWidgets.emptyState(
                ctx,
                mIsDarkMode,
                AcidIcons.ICON_DOWNLOAD,
                allList == null || allList.isEmpty() ? "暂无下载记录" : "未找到匹配任务"
            ));
            return;
        }

        for (final DownloadItem it : list) {
            int iconTint = downloadIconTint(it);

            ProgressBar pb = null;
            if (it.status == DownloadManager.STATUS_RUNNING || it.status == DownloadManager.STATUS_PENDING) {
                pb = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
                pb.setMax(100);
                pb.setProgress(it.getProgressPercent());
                pb.setIndeterminate(it.contentLength <= 0);
            }

            AcidWidgets.ListRow rowBuilder = new AcidWidgets.ListRow()
                .leadingIcon(categoryIcon(it), iconTint, 20)
                .title(it.fileName)
                .subtitle(buildDownloadSubtitle(it))
                .subtitleColor(downloadSubtitleColor(it));

            final AcidWidgets.CheckIndicator checkIndicator;
            if (mMultiSelectMode) {
                boolean selected = mSelectedDownloadIds.contains(Long.valueOf(it.downloadId));
                checkIndicator = new AcidWidgets.CheckIndicator(ctx, mIsDarkMode, selected);
                rowBuilder.selected(selected, AcidTheme.accent(mIsDarkMode))
                    .trailingView(checkIndicator.view);
            } else {
                checkIndicator = null;
            }

            if (pb != null) {
                rowBuilder.bottomView(pb, 30f);
            }

            final LinearLayout row = rowBuilder.build(ctx, mIsDarkMode);
            mDownloadRows.put(
                rowKey(it),
                new DownloadRowHolder(row, rowBuilder.subtitleView, pb, rowBuilder.iconView, checkIndicator)
            );

            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mMultiSelectMode) {
                        toggleDownloadSelection(it);
                        return;
                    }
                    if (mDownloads == null) return;
                    if (it.status == DownloadManager.STATUS_RUNNING) {
                        String speed = it.getFormattedSpeed();
                        String speedInfo = (speed != null && !speed.isEmpty()) ? (" · " + speed) : "";
                        String rem = it.getRemainingTime();
                        String remInfo = (rem != null && !rem.isEmpty()) ? (" · 剩余 " + rem) : "";
                        AcidFeedback.info(ctx, "正在多线程下载: " + it.fileName + " (" + it.getProgressPercent() + "%)" + speedInfo + remInfo);
                    } else if (it.status == DownloadItem.STATUS_SAVED) {
                        mDownloads.startDownload(it.url, it.fileName, null, null, it.mimeType, it.contentLength);
                        updateDownloadListRows();
                        startProgressPolling();
                    } else if (it.status == DownloadManager.STATUS_PAUSED) {
                        resumeDownloadWithFeedback(ctx, it, false);
                    } else if (it.status == DownloadManager.STATUS_FAILED) {
                        resumeDownloadWithFeedback(ctx, it, true);
                    } else {
                        mDownloads.openDownloadedFile(it);
                    }
                }
            });

            row.setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (mMultiSelectMode) {
                        toggleDownloadSelection(it);
                        return true;
                    }
                    showDownloadContextMenu(row, it);
                    return true;
                }
            });

            itemsContainer.addView(row);
        }

        if (scrollY > 0 && getScrollView() != null) {
            getScrollView().post(new Runnable() {
                @Override
                public void run() {
                    getScrollView().scrollTo(0, scrollY);
                }
            });
        }
    }

    private void syncDownloadsChrome(List<DownloadItem> allList) {
        final Context ctx = getContext();
        if (mMultiSelectMode) {
            setTitle("已选择 " + mSelectedDownloadIds.size() + " 项");
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
                            toggleSelectAllVisible();
                        }
                    },
                    null,
                    null,
                    new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (!mSelectedDownloadIds.isEmpty()) {
                                showDeleteDownloadsConfirmDialog(
                                    new ArrayList<Long>(mSelectedDownloadIds),
                                    null
                                );
                            }
                        }
                    }
                );
                footer.addView(mMultiSelectBar.view);
            }
            updateMultiSelectBarState();
            refreshScrollCeiling();
        } else {
            setTitle("下载管理");
            clearPinnedFooter();
            mMultiSelectBar = null;
            refreshScrollCeiling();
            if (allList != null && !allList.isEmpty()) {
                setAction(AcidIcons.ICON_MENU, "管理 / 目录", 0, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showDownloadsHeaderActionMenu(v);
                    }
                });
            } else {
                setAction(AcidIcons.ICON_FILE, "打开目录", 0, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (mDownloads != null) {
                            mDownloads.openDownloadFolder();
                        }
                    }
                });
            }
        }
    }

    private void showDownloadsHeaderActionMenu(View anchor) {
        final Context ctx = getContext();
        List<AcidContextMenu.ActionItem> actions = new ArrayList<AcidContextMenu.ActionItem>();
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_CHECK, "批量选择", new Runnable() {
            @Override
            public void run() {
                enterMultiSelectMode(null);
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_FILE, "打开下载目录", new Runnable() {
            @Override
            public void run() {
                if (mDownloads != null) {
                    mDownloads.openDownloadFolder();
                }
            }
        }));
        AcidContextMenu.showForAnchor(ctx, anchor, mIsDarkMode, AcidLocale.tr("下载操作"), actions);
    }

    private void enterMultiSelectMode(Long preselectDownloadId) {
        List<DownloadItem> allList = mDownloads != null ? mDownloads.getDownloads() : null;
        if (allList == null || allList.isEmpty()) {
            AcidFeedback.info(getContext(), "暂无下载任务记录");
            return;
        }
        mMultiSelectMode = true;
        mSelectedDownloadIds.clear();
        if (preselectDownloadId != null) {
            mSelectedDownloadIds.add(preselectDownloadId);
        }
        mMultiSelectBar = null;
        updateDownloadListRows();
    }

    private void exitMultiSelectMode() {
        if (!mMultiSelectMode) return;
        resetMultiSelectState();
        updateDownloadListRows();
    }

    private void toggleDownloadSelection(DownloadItem it) {
        if (!mMultiSelectMode || it == null) return;
        final Context ctx = getContext();
        Long key = Long.valueOf(it.downloadId);
        boolean nextSelected = !mSelectedDownloadIds.contains(key);
        if (nextSelected) {
            mSelectedDownloadIds.add(key);
        } else {
            mSelectedDownloadIds.remove(key);
        }
        DownloadRowHolder holder = mDownloadRows.get(rowKey(it));
        if (holder != null) {
            if (holder.checkIndicator != null) {
                holder.checkIndicator.setChecked(ctx, mIsDarkMode, nextSelected);
            }
            AcidWidgets.updateListItemCardSelection(
                ctx, mIsDarkMode, holder.rootCard, nextSelected, AcidTheme.accent(mIsDarkMode)
            );
        }
        setTitle("已选择 " + mSelectedDownloadIds.size() + " 项");
        updateMultiSelectBarState();
    }

    private boolean areAllVisibleSelected() {
        if (mCurrentVisibleDownloads.isEmpty()) return false;
        for (int i = 0; i < mCurrentVisibleDownloads.size(); i++) {
            DownloadItem it = mCurrentVisibleDownloads.get(i);
            if (!mSelectedDownloadIds.contains(Long.valueOf(it.downloadId))) {
                return false;
            }
        }
        return true;
    }

    private void toggleSelectAllVisible() {
        if (!mMultiSelectMode || mCurrentVisibleDownloads.isEmpty()) return;
        final Context ctx = getContext();
        int accent = AcidTheme.accent(mIsDarkMode);
        boolean selectTarget = !areAllVisibleSelected();

        for (int i = 0; i < mCurrentVisibleDownloads.size(); i++) {
            DownloadItem it = mCurrentVisibleDownloads.get(i);
            Long key = Long.valueOf(it.downloadId);
            if (selectTarget) {
                mSelectedDownloadIds.add(key);
            } else {
                mSelectedDownloadIds.remove(key);
            }
            DownloadRowHolder holder = mDownloadRows.get(rowKey(it));
            if (holder != null) {
                if (holder.checkIndicator != null) {
                    holder.checkIndicator.setChecked(ctx, mIsDarkMode, selectTarget);
                }
                AcidWidgets.updateListItemCardSelection(
                    ctx, mIsDarkMode, holder.rootCard, selectTarget, accent
                );
            }
        }
        setTitle("已选择 " + mSelectedDownloadIds.size() + " 项");
        updateMultiSelectBarState();
    }

    private void updateMultiSelectBarState() {
        if (mMultiSelectBar == null) return;
        boolean hasVisible = !mCurrentVisibleDownloads.isEmpty();
        boolean allVisibleSelected = hasVisible && areAllVisibleSelected();
        mMultiSelectBar.updateState(mSelectedDownloadIds.size(), allVisibleSelected, hasVisible);
    }

    private void showDownloadContextMenu(View anchor, final DownloadItem it) {
        final Context ctx = getContext();
        List<AcidContextMenu.ActionItem> actions = new ArrayList<AcidContextMenu.ActionItem>();
        if (it.status == DownloadItem.STATUS_SAVED) {
            actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_DOWNLOAD, "开始下载", new Runnable() {
                @Override
                public void run() {
                    if (mDownloads != null) {
                        mDownloads.startDownload(it.url, it.fileName, null, null, it.mimeType, it.contentLength);
                        updateDownloadListRows();
                        startProgressPolling();
                    }
                }
            }));
        } else if (it.status == DownloadManager.STATUS_RUNNING) {
            actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_CLOSE, "暂停下载", new Runnable() {
                @Override
                public void run() {
                    pauseDownloadWithFeedback(ctx, it);
                }
            }));
        } else if (it.status == DownloadManager.STATUS_PAUSED) {
            actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_RELOAD, "继续下载", new Runnable() {
                @Override
                public void run() {
                    resumeDownloadWithFeedback(ctx, it, false);
                }
            }));
        } else if (it.status == DownloadManager.STATUS_FAILED) {
            actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_RELOAD, "继续/重试下载", new Runnable() {
                @Override
                public void run() {
                    resumeDownloadWithFeedback(ctx, it, true);
                }
            }));
        }
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_FILE, "打开文件", new Runnable() {
            @Override
            public void run() {
                if (mDownloads != null) mDownloads.openDownloadedFile(it, false);
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_SEND, "其他应用打开", new Runnable() {
            @Override
            public void run() {
                if (mDownloads != null) mDownloads.openDownloadedFile(it, true);
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_DOWNLOAD, "打开所在目录", new Runnable() {
            @Override
            public void run() {
                if (mDownloads != null) mDownloads.openDownloadFolder();
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_GLOBE, "复制下载链接", new Runnable() {
            @Override
            public void run() {
                if (mDownloads != null) mDownloads.copyUrlToClipboard(it.url);
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_RELOAD, "重新下载", new Runnable() {
            @Override
            public void run() {
                if (mDownloads != null) {
                    mDownloads.redownload(it.downloadId);
                    updateDownloadListRows();
                    startProgressPolling();
                }
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_CHECK, "批量选择", new Runnable() {
            @Override
            public void run() {
                enterMultiSelectMode(Long.valueOf(it.downloadId));
            }
        }));
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_TRASH, "删除任务", true, new Runnable() {
            @Override
            public void run() {
                showDeleteConfirmDialog(it);
            }
        }));
        AcidContextMenu.showForAnchor(ctx, anchor, mIsDarkMode, it.fileName, actions);
    }

    private void showDeleteConfirmDialog(final DownloadItem it) {
        if (it == null) return;
        List<Long> ids = new ArrayList<Long>(1);
        ids.add(Long.valueOf(it.downloadId));
        showDeleteDownloadsConfirmDialog(ids, it.fileName);
    }

    private void showDeleteDownloadsConfirmDialog(final List<Long> targetIds, final String singleFileName) {
        if (targetIds == null || targetIds.isEmpty()) return;
        final Context ctx = getContext();
        final boolean[] deletePhysical = new boolean[]{false};
        final boolean isSingle = (singleFileName != null && targetIds.size() == 1);

        LinearLayout extraBox = new LinearLayout(ctx);
        extraBox.setOrientation(LinearLayout.VERTICAL);
        extraBox.addView(AcidDialog.createCheckableRow(
            ctx, mIsDarkMode, "同时删除本地已下载的源文件", false,
            new AcidWidgets.OnToggleListener() {
                @Override
                public void onToggle(boolean isChecked) {
                    deletePhysical[0] = isChecked;
                }
            }
        ));

        String title = isSingle ? "删除下载任务" : "批量删除下载任务";
        String msg = isSingle
            ? ("确定移除下载记录「" + singleFileName + "」吗？")
            : ("确定移除选中的 " + targetIds.size() + " 个下载任务吗？");

        new AcidDialog(ctx, mIsDarkMode)
            .setTitle(title)
            .setMessage(msg)
            .setCustomView(extraBox)
            .setDangerButton("删除", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    boolean delFile = deletePhysical[0];
                    int removed = mDownloads != null ? mDownloads.deleteDownloads(targetIds, delFile) : 0;
                    if (mMultiSelectMode) {
                        resetMultiSelectState();
                    }
                    updateDownloadListRows();
                    if (isSingle) {
                        AcidFeedback.info(ctx, delFile ? "已删除任务及本地源文件" : "已移除下载任务记录");
                    } else {
                        AcidFeedback.info(
                            ctx,
                            delFile
                                ? ("已删除 " + removed + " 个任务及本地源文件")
                                : ("已移除 " + removed + " 个下载任务记录")
                        );
                    }
                    return true;
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private String buildDownloadSubtitle(DownloadItem it) {
        String catTag = it.getCategoryLabel() + " · ";
        if (it.status == DownloadItem.STATUS_SAVED) {
            return catTag + "待下载 · " + DownloadItem.formatSize(it.contentLength) + " · 点击开始下载";
        }
        if (it.status == DownloadManager.STATUS_RUNNING) {
            String progSize = DownloadItem.formatSize(it.downloadedBytes)
                + " / " + DownloadItem.formatSize(it.contentLength);
            String speedStr = it.getFormattedSpeed();
            String speedTag = (speedStr != null && !speedStr.isEmpty()) ? " · " + speedStr : "";
            return catTag + "正在下载 " + it.getProgressPercent() + "% · " + progSize + speedTag;
        }
        if (it.status == DownloadManager.STATUS_PAUSED) {
            return catTag + "已暂停 · " + DownloadItem.formatSize(it.downloadedBytes);
        }
        if (it.status == DownloadManager.STATUS_PENDING) {
            return catTag + "排队等待下载...";
        }
        if (it.status == DownloadManager.STATUS_FAILED) {
            return catTag + "下载失败 · " + DownloadItem.formatSize(it.contentLength);
        }
        String sizeStr = DownloadItem.formatSize(
            it.contentLength > 0 ? it.contentLength : it.downloadedBytes
        );
        return catTag + "已完成 · " + sizeStr + " · /Download/";
    }

    private int downloadSubtitleColor(DownloadItem it) {
        if (it.status == DownloadItem.STATUS_SAVED) return AcidTheme.accent(mIsDarkMode);
        if (it.status == DownloadManager.STATUS_RUNNING) return AcidTheme.COLOR_BLUE;
        if (it.status == DownloadManager.STATUS_PAUSED) return AcidTheme.COLOR_AMBER;
        if (it.status == DownloadManager.STATUS_PENDING) return AcidTheme.COLOR_AMBER;
        if (it.status == DownloadManager.STATUS_FAILED) return AcidTheme.COLOR_RED;
        return AcidTheme.muted(mIsDarkMode);
    }

    private int downloadIconTint(DownloadItem it) {
        if (it.status == DownloadManager.STATUS_FAILED) return AcidTheme.COLOR_RED;
        if (it.status == DownloadItem.STATUS_SAVED) return AcidTheme.accent(mIsDarkMode);
        return AcidTheme.muted(mIsDarkMode);
    }

    private void refreshDownloadProgressInPlace() {
        if (mDownloads == null || mDownloadRows.isEmpty()) return;
        List<DownloadItem> allList = mDownloads.getDownloads();

        for (DownloadItem it : allList) {
            DownloadRowHolder holder = mDownloadRows.get(rowKey(it));
            if (holder == null) continue;

            if (holder.infoView != null) {
                holder.infoView.setText(AcidLocale.tr(buildDownloadSubtitle(it)));
                holder.infoView.setTextColor(downloadSubtitleColor(it));
            }
            if (holder.progressBar != null) {
                if (it.contentLength > 0) {
                    holder.progressBar.setIndeterminate(false);
                    holder.progressBar.setProgress(it.getProgressPercent());
                } else {
                    holder.progressBar.setIndeterminate(true);
                }
            }
            if (holder.iconView != null) {
                holder.iconView.setColor(downloadIconTint(it));
            }
        }
    }
}
