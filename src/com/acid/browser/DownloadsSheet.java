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
import java.util.List;
import java.util.Map;

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
        final TextView infoView;
        final ProgressBar progressBar;
        final AcidIcons.SvgIconView iconView;

        DownloadRowHolder(TextView infoView, ProgressBar progressBar, AcidIcons.SvgIconView iconView) {
            this.infoView = infoView;
            this.progressBar = progressBar;
            this.iconView = iconView;
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

    public DownloadsSheet(Context context, DownloadCoordinator downloads) {
        super(context);
        mDownloads = downloads;
        setRootPage(buildDownloadsPage());
    }

    @Override
    public void dismiss() {
        stopProgressPolling();
        super.dismiss();
    }

    @Override
    protected void onResetToRootPage() {
        setRootPage(buildDownloadsPage());
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

                // 1. Pinned Search Bar
                final EditText etSearch = new EditText(ctx);
                etSearch.setText(mSearchKeyword);
                LinearLayout searchBox = AcidWidgets.searchBox(ctx, mIsDarkMode, etSearch, "搜索下载文件名...");
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
                        mSearchKeyword = s != null ? s.toString().trim() : "";
                        updateDownloadListRows();
                    }
                });

                updateDownloadListRows();
            }
        })
            .withAction(AcidIcons.ICON_FILE, "打开目录", new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mDownloads != null) {
                        mDownloads.openDownloadFolder();
                    }
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

        if (list.isEmpty()) {
            itemsContainer.addView(AcidWidgets.emptyState(
                ctx,
                mIsDarkMode,
                AcidIcons.ICON_DOWNLOAD,
                allList == null || allList.isEmpty() ? "暂无下载任务记录" : "未找到匹配的下载任务"
            ));
            return;
        }

        for (final DownloadItem it : list) {
            int iconTint = (it.status == DownloadManager.STATUS_FAILED)
                ? AcidTheme.COLOR_RED
                : (it.status == DownloadItem.STATUS_SAVED
                    ? AcidTheme.accent(mIsDarkMode)
                    : AcidTheme.muted(mIsDarkMode));

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

            if (pb != null) {
                rowBuilder.bottomView(pb, 30f);
            }

            final LinearLayout row = rowBuilder.build(ctx, mIsDarkMode);
            mDownloadRows.put(
                rowKey(it),
                new DownloadRowHolder(rowBuilder.subtitleView, pb, rowBuilder.iconView)
            );

            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
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
                        mDownloads.resumeDownload(it.downloadId);
                        AcidFeedback.ok(ctx, "已恢复下载: " + it.fileName);
                        updateDownloadListRows();
                        startProgressPolling();
                    } else if (it.status == DownloadManager.STATUS_FAILED) {
                        mDownloads.resumeDownload(it.downloadId);
                        AcidFeedback.info(ctx, "正在重试下载: " + it.fileName);
                        updateDownloadListRows();
                        startProgressPolling();
                    } else {
                        mDownloads.openDownloadedFile(it);
                    }
                }
            });

            row.setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
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
                    if (mDownloads != null) {
                        mDownloads.pauseDownload(it.downloadId);
                        AcidFeedback.info(ctx, "已暂停: " + it.fileName);
                        updateDownloadListRows();
                    }
                }
            }));
        } else if (it.status == DownloadManager.STATUS_PAUSED) {
            actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_RELOAD, "继续下载", new Runnable() {
                @Override
                public void run() {
                    if (mDownloads != null) {
                        mDownloads.resumeDownload(it.downloadId);
                        AcidFeedback.ok(ctx, "已恢复下载: " + it.fileName);
                        updateDownloadListRows();
                        startProgressPolling();
                    }
                }
            }));
        } else if (it.status == DownloadManager.STATUS_FAILED) {
            actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_RELOAD, "继续/重试下载", new Runnable() {
                @Override
                public void run() {
                    if (mDownloads != null) {
                        mDownloads.resumeDownload(it.downloadId);
                        AcidFeedback.info(ctx, "正在重试下载: " + it.fileName);
                        updateDownloadListRows();
                        startProgressPolling();
                    }
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
        actions.add(new AcidContextMenu.ActionItem(AcidIcons.ICON_TRASH, "删除任务", true, new Runnable() {
            @Override
            public void run() {
                showDeleteConfirmDialog(it);
            }
        }));
        AcidContextMenu.showForAnchor(ctx, anchor, mIsDarkMode, it.fileName, actions);
    }

    private void showDeleteConfirmDialog(final DownloadItem it) {
        final Context ctx = getContext();
        final boolean[] deletePhysical = new boolean[]{false};

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

        new AcidDialog(ctx, mIsDarkMode)
            .setTitle("删除下载任务")
            .setMessage("确定移除下载记录「" + it.fileName + "」吗？")
            .setCustomView(extraBox)
            .setDangerButton("删除", new AcidDialog.OnActionListener() {
                @Override
                public boolean onAction(Dialog dialog) {
                    boolean delFile = deletePhysical[0];
                    if (mDownloads != null) {
                        mDownloads.deleteDownload(it.downloadId, delFile);
                    }
                    updateDownloadListRows();
                    AcidFeedback.info(ctx, delFile ? "已删除任务及本地源文件" : "已移除下载任务记录");
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

    private void refreshDownloadProgressInPlace() {
        if (mDownloads == null || mDownloadRows.isEmpty()) return;
        int mutedTint = AcidTheme.muted(mIsDarkMode);
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
                holder.iconView.setColor(it.status == DownloadManager.STATUS_FAILED
                    ? AcidTheme.COLOR_RED : mutedTint);
            }
        }
    }
}
