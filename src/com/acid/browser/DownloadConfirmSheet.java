package com.acid.browser;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Dedicated Download Confirmation Sheet extracted from {@code BrowserActivity}.
 *
 * <p>Uses {@link AcidBottomSheetView} adaptive sizing and {@link AcidWidgets} form/button
 * primitives, with full Day/Night live re-theming support.
 */
public class DownloadConfirmSheet extends AcidBottomSheetView {

    private final BrowserController mController;

    private String mPendingUrl = "";
    private String mPendingFileName = "";
    private String mPendingUserAgent = null;
    private String mPendingContentDisposition = null;
    private String mPendingMimeType = null;
    private long mPendingContentLength = 0;
    private EditText mActiveNameInput = null;

    public DownloadConfirmSheet(Context context, BrowserController controller) {
        super(context);
        mController = controller;
    }

    public void showConfirm(
        String url,
        String fileName,
        String userAgent,
        String contentDisposition,
        String mimeType,
        long contentLength
    ) {
        mPendingUrl = url != null ? url : "";
        mPendingFileName = fileName != null ? fileName : "download";
        mPendingUserAgent = userAgent;
        mPendingContentDisposition = contentDisposition;
        mPendingMimeType = mimeType;
        mPendingContentLength = contentLength;
        mActiveNameInput = null;

        rebuildContent();
        show();
    }

    @Override
    protected void onThemeChanged(boolean isDark) {
        if (isShowing()) {
            if (mActiveNameInput != null && mActiveNameInput.getText() != null) {
                String edited = mActiveNameInput.getText().toString().trim();
                if (!edited.isEmpty()) {
                    mPendingFileName = edited;
                }
            }
            rebuildContent();
        }
    }

    private void rebuildContent() {
        setTitle("下载确认");
        hideAction();
        clearPinnedHeader();

        LinearLayout container = getContentContainer();
        container.removeAllViews();

        Context ctx = getContext();
        int accent = AcidTheme.accent(mIsDarkMode);
        int text = AcidTheme.text(mIsDarkMode);
        int muted = AcidTheme.muted(mIsDarkMode);
        int blue = AcidTheme.COLOR_BLUE;

        // 1. Task Info Card
        LinearLayout infoCard = AcidWidgets.cardGroup(ctx, mIsDarkMode);
        int pad = AcidTheme.dp(ctx, 16);
        infoCard.setPadding(pad, AcidTheme.dp(ctx, 14), pad, AcidTheme.dp(ctx, 14));

        // Lead Title Row (H3 = 14.5sp Bold)
        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, 0, 0, AcidTheme.dp(ctx, 10));

        AcidIcons.SvgIconView dlIcon = new AcidIcons.SvgIconView(ctx, AcidIcons.ICON_DOWNLOAD, accent, 22);
        titleRow.addView(dlIcon);

        TextView tvCardTitle = new TextView(ctx);
        tvCardTitle.setText("新建下载任务");
        tvCardTitle.setTextColor(text);
        tvCardTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, AcidTheme.SP_H3_CARD);
        tvCardTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvCardTitle.setPadding(AcidTheme.dp(ctx, 8), 0, 0, 0);
        titleRow.addView(tvCardTitle);
        infoCard.addView(titleRow);

        // Editable File Name
        infoCard.addView(AcidWidgets.fieldLabel(ctx, mIsDarkMode, "保存文件名 (可修改)"));
        final EditText etFileName = AcidWidgets.inputField(
            ctx, mIsDarkMode, mPendingFileName, "输入保存文件名", true
        );
        mActiveNameInput = etFileName;
        infoCard.addView(etFileName);

        // Size & MIME
        TextView tvSize = new TextView(ctx);
        String mimeInfo = !TextUtils.isEmpty(mPendingMimeType) ? (" · " + mPendingMimeType) : "";
        tvSize.setText("预估大小: " + BrowserController.DownloadItem.formatSize(mPendingContentLength) + mimeInfo);
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

        TextView btnBrowse = AcidWidgets.miniButton(ctx, mIsDarkMode, "浏览目录", blue, false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mController != null) {
                    mController.openDownloadFolder();
                }
            }
        });
        pathRow.addView(btnBrowse);
        infoCard.addView(pathRow);

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

        TextView btnCopy = AcidWidgets.miniButton(ctx, mIsDarkMode, "复制链接", accent, false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mController != null) {
                    mController.copyToClipboard(mPendingUrl);
                }
            }
        });
        urlRow.addView(btnCopy);
        infoCard.addView(urlRow);

        container.addView(infoCard);

        // 2. Bottom Action Bar: [取消] [立即下载]
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        btnRow.setPadding(0, AcidTheme.dp(ctx, 2), 0, 0);

        TextView btnCancel = AcidWidgets.secondaryButton(ctx, mIsDarkMode, "取消", muted, new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        );
        cLp.rightMargin = AcidTheme.dp(ctx, 8);
        btnRow.addView(btnCancel, cLp);

        TextView btnConfirm = AcidWidgets.primaryButton(ctx, mIsDarkMode, "立即下载", accent, new OnClickListener() {
            @Override
            public void onClick(View v) {
                String customName = etFileName.getText() != null ? etFileName.getText().toString().trim() : "";
                if (customName.isEmpty()) customName = mPendingFileName;
                dismiss();
                if (mController != null) {
                    mController.startDownload(
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
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        );
        dLp.leftMargin = AcidTheme.dp(ctx, 8);
        btnRow.addView(btnConfirm, dLp);

        container.addView(btnRow);
    }
}
