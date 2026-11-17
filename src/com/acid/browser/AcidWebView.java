package com.acid.browser;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;

/**
 * Controlled {@link WebView} subclass with explicit View-focus and IME isolation policies.
 *
 * <p>Solves two multi-tab / automation interference bugs cleanly at the Android View boundary:
 * <ul>
 *   <li>Inactive background tabs can never acquire window focus or touch the active tab's IME;</li>
 *   <li>Foreground tabs locked by the Agent in {@link AgentOverlayView#MODE_AGENT} deny Android
 *       Window Focus and soft-keyboard connections ({@link #onCheckIsTextEditor}/
 *       {@link #onCreateInputConnection}/{@link #checkInputConnectionProxy}) while keeping raw
 *       touch dispatch and DOM manipulation intact, restoring full human editing on takeover.</li>
 * </ul>
 */
public class AcidWebView extends WebView {

    public static final class IsolationPolicy {
        public final boolean allowFocus;
        public final boolean allowIme;

        public IsolationPolicy(boolean allowFocus, boolean allowIme) {
            this.allowFocus = allowFocus;
            this.allowIme = allowIme;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof IsolationPolicy)) return false;
            IsolationPolicy other = (IsolationPolicy) o;
            return allowFocus == other.allowFocus && allowIme == other.allowIme;
        }

        @Override
        public int hashCode() {
            return (allowFocus ? 2 : 0) + (allowIme ? 1 : 0);
        }
    }

    /**
     * Single source of truth determining whether a tab's {@link WebView} may take Android window
     * focus or expose an {@link InputConnection} to the soft keyboard.
     */
    public static IsolationPolicy resolveIsolationPolicy(
        boolean isActiveTab,
        int controlMode,
        boolean isDrivenByAgent,
        boolean humanUiInputFocused
    ) {
        if (!isActiveTab || humanUiInputFocused
                || (controlMode == AgentOverlayView.MODE_AGENT && isDrivenByAgent)) {
            // Deny both Android Window Focus (which binds IMM mServedView) and IME connections;
            // raw dispatchTouchEvent + DOM JS execution remain functional for Agent automation.
            return new IsolationPolicy(false, false);
        }
        return new IsolationPolicy(true, true);
    }

    /**
     * Determines whether {@code WebChromeClient.onCreateWindow()} should switch the foreground
     * viewport to the newly created tab. Background Agent tabs must open popups in the background
     * so they never interrupt the tab the human is currently typing in.
     */
    public static boolean shouldSwitchToNewWindow(boolean sourceIsActiveTab) {
        return sourceIsActiveTab;
    }

    /**
     * Resolves which tab should be reloaded after {@code setDesktopMode}. When automation sets mode
     * for a background bound tab, only that tab should reload — never the human's active tab.
     */
    public static int resolveModeReloadTabId(int activeTabId, int targetTabId, boolean targetAgentTabOnly) {
        if (targetAgentTabOnly && targetTabId > 0) {
            return targetTabId;
        }
        return activeTabId;
    }

    private final int mDefaultDescendantFocusability;
    private boolean mAllowFocus = true;
    private boolean mAllowIme = true;

    public AcidWebView(Context context) {
        super(context);
        mDefaultDescendantFocusability = getDescendantFocusability();
    }

    public void applyIsolationPolicy(IsolationPolicy policy) {
        if (policy == null) return;
        boolean prevAllowFocus = mAllowFocus;
        boolean prevAllowIme = mAllowIme;
        mAllowFocus = policy.allowFocus;
        mAllowIme = policy.allowIme;

        if (mAllowFocus) {
            if (!prevAllowFocus) {
                setDescendantFocusability(mDefaultDescendantFocusability);
                setFocusable(true);
                setFocusableInTouchMode(true);
            }
        } else {
            setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            setFocusable(false);
            setFocusableInTouchMode(false);
            if (hasFocus()) {
                clearFocus();
            }
        }

        InputMethodManager imm = getInputMethodManager();
        if (imm != null) {
            if ((!mAllowIme || !mAllowFocus) && imm.isActive(this)) {
                if (getWindowToken() != null) {
                    imm.hideSoftInputFromWindow(getWindowToken(), 0);
                }
                if (prevAllowIme && !mAllowIme) {
                    // Drop any active Chromium InputConnection so subsequent agent taps cannot reuse it
                    imm.restartInput(this);
                }
            } else if (mAllowIme && !prevAllowIme && mAllowFocus && hasFocus()) {
                imm.restartInput(this);
            }
        }
    }

    private InputMethodManager getInputMethodManager() {
        Context ctx = getContext();
        if (ctx == null) return null;
        return (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    @Override
    public boolean requestFocus(int direction, Rect previouslyFocusedRect) {
        if (!mAllowFocus) {
            return false;
        }
        return super.requestFocus(direction, previouslyFocusedRect);
    }

    // Note: View.requestFocusFromTouch() is final and delegates to setFocusableInTouchMode + requestFocus.

    @Override
    public boolean onCheckIsTextEditor() {
        if (!mAllowFocus || !mAllowIme) {
            return false;
        }
        return super.onCheckIsTextEditor();
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        if (!mAllowFocus || !mAllowIme) {
            return null;
        }
        return super.onCreateInputConnection(outAttrs);
    }

    @Override
    public boolean checkInputConnectionProxy(View view) {
        if (!mAllowFocus || !mAllowIme) {
            return false;
        }
        return super.checkInputConnectionProxy(view);
    }
}
