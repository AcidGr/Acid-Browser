package com.acid.browser;

import android.content.Context;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.acid.browser.download.DownloadCoordinator;
import com.acid.browser.download.DownloadItem;

import java.io.File;

/**
 * Download Confirmation Sheet built on {@link AcidBottomSheetView}'s {@link SheetPage} template
 * with pinned bottom CTA buttons and live filename draft preservation.
 */
public class DownloadConfirmSheet extends AcidBottomSheetView {

    private final DownloadCoordinator mDownloads;

    private String mPendingUrl = "";
    private String mPendingFileName = "";
    private String mPendingUserAgent = null;
    private String mPendingContentDisposition = null;
    private String mPendingMimeType = null;
    private long mPendingContentLength = 0;
    private boolean mHandled = true;

    public DownloadConfirmSheet(Context context, DownloadCoordinator downloads) {
        super(context);
        mDownloads = downloads;
    }

    @Override
    protected void onResetToRootPage() {
        setRootPage(buildConfirmPage());
    }

    public void prepareConfirm(
        String url,
        String fileName,
        String userAgent,
        String contentDisposition,
        String mimeType,
        long contentLength
    ) {
        mPendingUrl = url != null ? url : "";
        mPendingFileName = fileName != null ? fileName : "download";
        if (mDownloads != null) {
            File destDir = mDownloads.getDownloadDestinationDir();
            mPendingFileName = mDownloads.generateUniqueFileName(destDir, mPendingFileName);
        }
        mPendingUserAgent = userAgent;
        mPendingContentDisposition = contentDisposition;
        mPendingMimeType = mimeType;
        mPendingContentLength = contentLength;
        mHandled = false;
        setRootPage(buildConfirmPage());
    }

    private void saveToDownloadsIfPending() {
        if (mHandled) return;
        mHandled = true;
        if (mDownloads != null && mPendingUrl != null && !mPendingUrl.isEmpty()) {
            mDownloads.saveDownloadForLater(
                mPendingUrl,
                mPendingFileName,
                mPendingUserAgent,
                mPendingContentDisposition,
                mPendingMimeType,
                mPendingContentLength
            );
        }
    }

    @Override
    public void dismiss() {
        boolean wasShowing = isShowing();
        super.dismiss();
        if (wasShowing && !mHandled) {
            saveToDownloadsIfPending();
        }
    }

    private SheetPage buildConfirmPage() {
        return new SheetPage("新建下载", new FullPageRenderer() {
            @Override
            public void render(LinearLayout pinned, LinearLayout container, LinearLayout footer) {
                Context ctx = getContext();
                int accent = AcidTheme.accent(mIsDarkMode);
                int muted = AcidTheme.muted(mIsDarkMode);
                int blue = AcidTheme.COLOR_BLUE;

                // 1. Task Info Card
                LinearLayout infoCard = AcidWidgets.contentCard(ctx, mIsDarkMode);

                infoCard.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "保存文件名 (可修改)"));
                final EditText etFileName = AcidWidgets.inputField(
                    ctx, mIsDarkMode, mPendingFileName, "输入保存文件名", true
                );
                etFileName.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                    @Override
                    public void afterTextChanged(Editable s) {
                        String edited = s != null ? s.toString().trim() : "";
                        if (!edited.isEmpty()) {
                            mPendingFileName = edited;
                        }
                    }
                });
                infoCard.addView(etFileName);

                // Size & MIME
                TextView tvSize = new TextView(ctx);
                String mimeInfo = !TextUtils.isEmpty(mPendingMimeType) ? (" · " + mPendingMimeType) : "";
                tvSize.setText("预估大小: " + DownloadItem.formatSize(mPendingContentLength) + mimeInfo);
                tvSize.setTextColor(muted);
                tvSize.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
                infoCard.addView(tvSize);

                // Storage Directory Row
                LinearLayout pathRow = new LinearLayout(ctx);
                pathRow.setOrientation(LinearLayout.HORIZONTAL);
                pathRow.setGravity(Gravity.CENTER_VERTICAL);
                pathRow.setPadding(0, AcidTheme.dp(ctx, 8), 0, 0);

                TextView tvPath = new TextView(ctx);
                tvPath.setText("保存目录: /Download/");
                tvPath.setTextColor(muted);
                tvPath.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
                pathRow.addView(tvPath, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                pathRow.addView(AcidWidgets.miniButton(ctx, mIsDarkMode, "浏览目录", blue, false, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (mDownloads != null) {
                            mDownloads.openDownloadFolder();
                        }
                    }
                }));
                infoCard.addView(pathRow);

                // Engine info row
                int curEngine = (mDownloads != null) ? mDownloads.getDownloaderEngine() : 0;
                String engineName = (curEngine == 1) ? "系统下载器" : "内置下载器";
                TextView tvEngine = new TextView(ctx);
                tvEngine.setText("下载引擎: " + engineName);
                tvEngine.setTextColor(muted);
                tvEngine.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
                tvEngine.setPadding(0, AcidTheme.dp(ctx, 6), 0, 0);
                infoCard.addView(tvEngine);

                // Source URL Row
                LinearLayout urlRow = new LinearLayout(ctx);
                urlRow.setOrientation(LinearLayout.HORIZONTAL);
                urlRow.setGravity(Gravity.CENTER_VERTICAL);
                urlRow.setPadding(0, AcidTheme.dp(ctx, 8), 0, 0);

                TextView tvUrl = new TextView(ctx);
                tvUrl.setText("来源: " + mPendingUrl);
                tvUrl.setTextColor(muted);
                tvUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_CAPTION);
                tvUrl.setSingleLine(true);
                tvUrl.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams uLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                );
                uLp.rightMargin = AcidTheme.dp(ctx, 8);
                urlRow.addView(tvUrl, uLp);

                urlRow.addView(AcidWidgets.miniButton(ctx, mIsDarkMode, "复制链接", accent, false, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (mDownloads != null) {
                            mDownloads.copyUrlToClipboard(mPendingUrl);
                        }
                    }
                }));
                infoCard.addView(urlRow);

                container.addView(infoCard);

                // 2. Pinned Footer Action Bar: [先收起 (暂存)] [立即下载]
                TextView btnCollapse = AcidWidgets.secondaryButton(ctx, mIsDarkMode, "先收起", muted, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String customName = etFileName.getText() != null ? etFileName.getText().toString().trim() : "";
                        if (!customName.isEmpty()) mPendingFileName = customName;
                        saveToDownloadsIfPending();
                        closeEntireModalFlow();
                    }
                });
                TextView btnConfirm = AcidWidgets.primaryButton(ctx, mIsDarkMode, "立即下载", accent, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String customName = etFileName.getText() != null ? etFileName.getText().toString().trim() : "";
                        if (customName.isEmpty()) customName = mPendingFileName;
                        mHandled = true;
                        closeEntireModalFlow();
                        if (mDownloads != null) {
                            mDownloads.startDownload(
                                mPendingUrl,
                                customName,
                                mPendingUserAgent,
                                mPendingContentDisposition,
                                mPendingMimeType,
                                mPendingContentLength
                            );
                        }
                    }
                });
                footer.addView(AcidWidgets.buttonRow(ctx, btnCollapse, btnConfirm));
            }
        }).withAction(AcidIcons.ICON_TRASH, "丢弃", AcidTheme.COLOR_RED, new OnClickListener() {
            @Override
            public void onClick(View v) {
                mHandled = true;
                closeEntireModalFlow();
            }
        });
    }
}
