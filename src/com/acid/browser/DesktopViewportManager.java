package com.acid.browser;

import android.webkit.WebView;

/**
 * Manages desktop viewport meta tag overrides and responsive CSS layout enforcement.
 * Solves responsive layout issues (e.g. GitHub, Twitter/X, Bilibili) where pages
 * rely on CSS media query breakpoints (width >= 1012px/1280px) rather than User-Agent alone.
 */
public final class DesktopViewportManager {

    public static final int DESKTOP_WIDTH = 1280;
    public static final double OVERVIEW_ZOOM_THRESHOLD = 0.94;
    public static final String VIEWPORT_TAG_ATTR = "data-acid-viewport";
    public static final String TARGET_CONTENT =
        "width=" + DESKTOP_WIDTH + ", initial-scale=0.32, minimum-scale=0.1, maximum-scale=5.0, user-scalable=yes";

    private DesktopViewportManager() {}

    /**
     * Computes the relative {@code WebView.zoomBy(factor)} multiplier needed to restore a
     * focus-zoomed desktop page back to full overview width ({@code targetWidth >= 1280px}).
     * Returns {@code 1.0} when already at overview width or when inputs are invalid.
     */
    public static double computeOverviewRestoreFactor(double layoutWidth, double visualViewportWidth) {
        double targetWidth = Math.max((double) DESKTOP_WIDTH, layoutWidth);
        if (visualViewportWidth <= 1.0 || targetWidth <= 1.0) {
            return 1.0;
        }
        double ratio = visualViewportWidth / targetWidth;
        if (ratio >= OVERVIEW_ZOOM_THRESHOLD) {
            return 1.0;
        }
        return Math.max(0.05, Math.min(0.98, ratio));
    }

    /** Lightweight JS probe returning current layout width, visualViewport width/offset, and scroll position. */
    public static String buildViewportMetricsScript() {
        return "(function(){try{" +
            "var vv=window.visualViewport;" +
            "var lw=Math.max(document.documentElement?document.documentElement.clientWidth:0,window.innerWidth||0);" +
            "var vvw=(vv&&vv.width)?vv.width:lw;" +
            "var vvol=(vv&&typeof vv.offsetLeft==='number')?vv.offsetLeft:0;" +
            "var sy=window.scrollY||(document.documentElement?document.documentElement.scrollTop:0)||0;" +
            "return JSON.stringify({ok:true,lw:lw,vvw:vvw,vvol:vvol,sy:sy});" +
            "}catch(e){return '{\"ok\":false}';}})()";
    }

    /**
     * Returns true if the URL is an external web page eligible for desktop viewport overrides.
     * Local schemes (file:, acid:, about:, data:) are excluded to preserve local page layout.
     */
    public static boolean isEligibleUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * Generates the JavaScript expression to enforce or release the desktop viewport.
     */
    public static String buildViewportScript(boolean desktop) {
        if (desktop) {
            return "(function(){try{" +
                "if(window.__acidBridge&&window.__acidBridge.enforceDesktopViewport){window.__acidBridge.enforceDesktopViewport(true);return;}" +
                "var tw=" + DESKTOP_WIDTH + ";" +
                "var tc='" + TARGET_CONTENT + "';" +
                "var h=document.head||document.getElementsByTagName('head')[0]||document.documentElement;" +
                "if(!h)return;" +
                "function lock(){" +
                "  var metas=document.querySelectorAll('meta[name=\"viewport\"]');" +
                "  var locked=false;" +
                "  metas.forEach(function(m){" +
                "    if(m.getAttribute('" + VIEWPORT_TAG_ATTR + "')==='desktop'&&m.content===tc){locked=true;}else{m.remove();}" +
                "  });" +
                "  if(!locked){" +
                "    var m=document.createElement('meta');" +
                "    m.name='viewport';" +
                "    m.content=tc;" +
                "    m.setAttribute('" + VIEWPORT_TAG_ATTR + "','desktop');" +
                "    h.insertBefore(m,h.firstChild);" +
                "  }" +
                "}" +
                "lock();" +
                "if(!window.__acid_desktop_observer&&window.MutationObserver){" +
                "  window.__acid_desktop_observer=new MutationObserver(function(){lock();});" +
                "  window.__acid_desktop_observer.observe(h,{childList:true,subtree:false});" +
                "}" +
                "}catch(e){}})()";
        } else {
            return "(function(){try{" +
                "if(window.__acidBridge&&window.__acidBridge.enforceDesktopViewport){window.__acidBridge.enforceDesktopViewport(false);return;}" +
                "if(window.__acid_desktop_observer){" +
                "  window.__acid_desktop_observer.disconnect();" +
                "  window.__acid_desktop_observer=null;" +
                "}" +
                "var ms=document.querySelectorAll('meta[" + VIEWPORT_TAG_ATTR + "=\"desktop\"]');" +
                "ms.forEach(function(m){m.remove();});" +
                "var remaining=document.querySelector('meta[name=\"viewport\"]');" +
                "if(!remaining){" +
                "  var m=document.createElement('meta');" +
                "  m.name='viewport';" +
                "  m.content='width=device-width, initial-scale=1.0, user-scalable=yes';" +
                "  var h=document.head||document.getElementsByTagName('head')[0]||document.documentElement;" +
                "  if(h)h.appendChild(m);" +
                "}" +
                "}catch(e){}})()";
        }
    }

    /**
     * Injects or clears desktop viewport configuration on a WebView.
     */
    public static void applyDesktopViewport(WebView wv, boolean desktop) {
        if (wv == null) return;
        try {
            wv.evaluateJavascript(buildViewportScript(desktop), null);
        } catch (Throwable ignored) {}
    }
}
