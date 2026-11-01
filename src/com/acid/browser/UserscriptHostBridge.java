package com.acid.browser;

import android.content.Context;
import android.os.Handler;
import android.webkit.WebView;

/**
 * Bridges Userscript actions (new tab, download, desktop notification, phase injection,
 * and Tampermonkey facade probe) back into {@link BrowserController}.
 *
 * <p>Extracted from {@link BrowserController} to decouple script runtime dispatch
 * from top-level tab and automation management.
 */
public class UserscriptHostBridge implements UserscriptNativeBridge.Host {

    private final BrowserController mController;
    private final UserscriptManager mManager;
    private final Handler mMainHandler;
    private final Context mContext;

    public UserscriptHostBridge(
        BrowserController controller,
        UserscriptManager manager,
        Handler mainHandler,
        Context context
    ) {
        mController = controller;
        mManager = manager;
        mMainHandler = mainHandler;
        mContext = context != null ? context.getApplicationContext() : null;
    }

    @Override
    public void openInTab(final String url, final boolean active) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                mController.createNewTab(url, active);
            }
        });
    }

    @Override
    public void download(final String url, final String fileName) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                mController.requestDownload(url, fileName, null, null, null, 0);
            }
        });
    }

    @Override
    public void onInjectionRequested(final UserscriptNativeBridge bridge, final int phase) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                WebView wv = bridge.getWebView();
                if (wv == null) return;
                String url;
                try {
                    url = wv.getUrl();
                } catch (Exception e) {
                    return;
                }
                mManager.injectPhase(wv, url, phase);
            }
        });
    }

    @Override
    public String isScriptInstalled(String name, String namespace) {
        UserscriptMeta m = mManager.findInstalled(name, namespace);
        return m == null ? null : m.fileName;
    }

    @Override
    public void notify(final String title, final String text) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                String msg = (title == null || title.isEmpty()) ? text : (title + "：" + text);
                if (msg != null && !msg.trim().isEmpty() && mContext != null) {
                    AcidFeedback.info(mContext, msg);
                }
            }
        });
    }
}
