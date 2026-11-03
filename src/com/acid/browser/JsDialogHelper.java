package com.acid.browser;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.widget.EditText;
import android.widget.LinearLayout;

/**
 * WebView JavaScript Dialog Presenter ({@code alert}, {@code confirm}, {@code prompt})
 * built on {@link AcidDialog} and {@link AcidWidgets}.
 *
 * <p>Extracted from {@link BrowserController} to keep UI presentation out of the browser
 * engine controller and guarantee every {@link JsResult} is settled exactly once.
 */
public final class JsDialogHelper {

    private JsDialogHelper() {}

    private static String hostTitle(String url, String fallback) {
        if (url != null && !url.isEmpty()) {
            try {
                String host = Uri.parse(url).getHost();
                if (host != null && !host.isEmpty()) {
                    return host + " 提示";
                }
            } catch (Exception ignored) {}
        }
        return fallback;
    }

    public static void showAlert(Context ctx, boolean dark, String url, String message, final JsResult result) {
        final boolean[] settled = new boolean[]{false};
        try {
            new AcidDialog(ctx, dark)
                .setTitle(hostTitle(url, "网页提示"))
                .setMessage(message != null ? message : "")
                .setPositiveButton("确定", new AcidDialog.OnActionListener() {
                    @Override
                    public boolean onAction(Dialog dialog) {
                        if (!settled[0]) {
                            settled[0] = true;
                            result.confirm();
                        }
                        return true;
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface d) {
                        if (!settled[0]) {
                            settled[0] = true;
                            result.cancel();
                        }
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface d) {
                        if (!settled[0]) {
                            settled[0] = true;
                            result.cancel();
                        }
                    }
                })
                .show();
        } catch (Exception e) {
            if (!settled[0]) result.cancel();
        }
    }

    public static void showConfirm(Context ctx, boolean dark, String url, String message, final JsResult result) {
        final boolean[] settled = new boolean[]{false};
        try {
            new AcidDialog(ctx, dark)
                .setTitle(hostTitle(url, "网页确认"))
                .setMessage(message != null ? message : "")
                .setPositiveButton("确定", new AcidDialog.OnActionListener() {
                    @Override
                    public boolean onAction(Dialog dialog) {
                        if (!settled[0]) {
                            settled[0] = true;
                            result.confirm();
                        }
                        return true;
                    }
                })
                .setNegativeButton("取消", new AcidDialog.OnActionListener() {
                    @Override
                    public boolean onAction(Dialog dialog) {
                        if (!settled[0]) {
                            settled[0] = true;
                            result.cancel();
                        }
                        return true;
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface d) {
                        if (!settled[0]) {
                            settled[0] = true;
                            result.cancel();
                        }
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface d) {
                        if (!settled[0]) {
                            settled[0] = true;
                            result.cancel();
                        }
                    }
                })
                .show();
        } catch (Exception e) {
            if (!settled[0]) result.cancel();
        }
    }

    public static void showPrompt(
        Context ctx,
        boolean dark,
        String url,
        String message,
        String defaultValue,
        final JsPromptResult result
    ) {
        final boolean[] settled = new boolean[]{false};
        try {
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            final EditText et = AcidWidgets.inputField(
                ctx, dark, defaultValue != null ? defaultValue : "", "", true
            );
            box.addView(et);

            new AcidDialog(ctx, dark)
                .setTitle(hostTitle(url, "网页输入"))
                .setMessage(message != null ? message : "")
                .setCustomView(box)
                .setPositiveButton("确定", new AcidDialog.OnActionListener() {
                    @Override
                    public boolean onAction(Dialog dialog) {
                        if (!settled[0]) {
                            settled[0] = true;
                            result.confirm(et.getText() != null ? et.getText().toString() : "");
                        }
                        return true;
                    }
                })
                .setNegativeButton("取消", new AcidDialog.OnActionListener() {
                    @Override
                    public boolean onAction(Dialog dialog) {
                        if (!settled[0]) {
                            settled[0] = true;
                            result.cancel();
                        }
                        return true;
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface d) {
                        if (!settled[0]) {
                            settled[0] = true;
                            result.cancel();
                        }
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface d) {
                        if (!settled[0]) {
                            settled[0] = true;
                            result.cancel();
                        }
                    }
                })
                .show();
        } catch (Exception e) {
            if (!settled[0]) result.cancel();
        }
    }
}
