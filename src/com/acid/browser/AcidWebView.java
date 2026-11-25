package com.acid.browser;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;

import java.util.concurrent.atomic.AtomicBoolean;

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
        public final boolean blockProgrammaticFocus;

        public IsolationPolicy(boolean allowFocus, boolean allowIme) {
            this(allowFocus, allowIme, !allowFocus);
        }

        public IsolationPolicy(boolean allowFocus, boolean allowIme, boolean blockProgrammaticFocus) {
            this.allowFocus = allowFocus;
            this.allowIme = allowIme;
            this.blockProgrammaticFocus = blockProgrammaticFocus;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof IsolationPolicy)) return false;
            IsolationPolicy other = (IsolationPolicy) o;
            return allowFocus == other.allowFocus
                && allowIme == other.allowIme
                && blockProgrammaticFocus == other.blockProgrammaticFocus;
        }

        @Override
        public int hashCode() {
            return (allowFocus ? 4 : 0) + (allowIme ? 2 : 0) + (blockProgrammaticFocus ? 1 : 0);
        }
    }

    /**
     * Single source of truth determining whether a tab's {@link WebView} may take Android window
     * focus, expose an {@link InputConnection} to the soft keyboard, or steal focus via script.
     */
    public static IsolationPolicy resolveIsolationPolicy(
        boolean isActiveTab,
        int controlMode,
        boolean isDrivenByAgent,
        boolean humanUiInputFocused
    ) {
        if (!isActiveTab || (controlMode == AgentOverlayView.MODE_AGENT && isDrivenByAgent)) {
            // Deny both Android Window Focus (which binds IMM mServedView) and IME connections;
            // raw dispatchTouchEvent + DOM JS execution remain functional for Agent automation.
            return new IsolationPolicy(false, false, true);
        }
        if (humanUiInputFocused) {
            // Keep focusableInTouchMode=true so direct human taps reclaim focus from URL/Agent bar,
            // while blocking non-touch/JS autofocus steals away from active chrome text inputs.
            return new IsolationPolicy(true, true, true);
        }
        return new IsolationPolicy(true, true, false);
    }

    /**
     * Decides whether an incoming {@code requestFocus} call on this WebView should be honored.
     */
    public static boolean shouldAllowRequestFocus(
        boolean allowFocus,
        boolean blockProgrammaticFocus,
        boolean hasRecentTouch
    ) {
        if (!allowFocus) return false;
        if (blockProgrammaticFocus && !hasRecentTouch) return false;
        return true;
    }

    /**
     * Decides whether a popup tab created in {@code onCreateWindow} should inherit Agent binding.
     */
    public static boolean shouldTransferAgentBindingToNewWindow(
        boolean sourceIsBoundTab,
        int controlMode,
        int agentBoundTabId,
        boolean sourceIsActiveTab
    ) {
        if (agentBoundTabId > 0) {
            return sourceIsBoundTab;
        }
        return controlMode == AgentOverlayView.MODE_AGENT && sourceIsActiveTab;
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

    private static final long USER_TOUCH_FOCUS_WINDOW_MS = 600L;
    private static final AtomicBoolean sWholeDocDrawInitialized = new AtomicBoolean(false);

    static {
        ensureWholeDocumentDrawEnabled();
    }

    /**
     * Only pre-API 26 WebViews (without hardware {@code PixelCopy}) need whole-document
     * DisplayList recording for software draw; API 26+ keeps Chromium's faster tile culling.
     */
    public static boolean shouldEnableSlowWholeDocumentDraw(int sdkInt) {
        return sdkInt >= 21 && sdkInt < 26;
    }

    /**
     * Enables full-document DisplayList recording on legacy devices lacking {@code PixelCopy}
     * so software draw fallbacks capture off-tile CSS/HTML layers without handicapping API 26+.
     */
    public static void ensureWholeDocumentDrawEnabled() {
        if (sWholeDocDrawInitialized.compareAndSet(false, true)) {
            if (shouldEnableSlowWholeDocumentDraw(android.os.Build.VERSION.SDK_INT)) {
                try {
                    WebView.enableSlowWholeDocumentDraw();
                } catch (Throwable ignored) {}
            }
        }
    }

    private final int mDefaultDescendantFocusability;
    private boolean mAllowFocus = true;
    private boolean mAllowIme = true;
    private boolean mBlockProgrammaticFocus = false;
    private long mLastUserTouchUptimeMs = 0L;

    public AcidWebView(Context context) {
        super(context);
        mDefaultDescendantFocusability = getDescendantFocusability();
    }

    public void applyIsolationPolicy(IsolationPolicy policy) {
        if (policy == null) return;
        boolean prevAllowFocus = mAllowFocus;
        boolean prevAllowIme = mAllowIme;
        boolean prevBlockProgrammatic = mBlockProgrammaticFocus;
        mAllowFocus = policy.allowFocus;
        mAllowIme = policy.allowIme;
        mBlockProgrammaticFocus = policy.blockProgrammaticFocus;

        if (!prevBlockProgrammatic && mBlockProgrammaticFocus) {
            // Invalidate prior touch timestamp when UI bar takes focus so a stale tap cannot hijack it back
            mLastUserTouchUptimeMs = 0L;
        }

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
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev != null && mAllowFocus && mAllowIme && ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mLastUserTouchUptimeMs = SystemClock.uptimeMillis();
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean requestFocus(int direction, Rect previouslyFocusedRect) {
        boolean hasRecentTouch = mLastUserTouchUptimeMs > 0L
            && (SystemClock.uptimeMillis() - mLastUserTouchUptimeMs) <= USER_TOUCH_FOCUS_WINDOW_MS;
        if (!shouldAllowRequestFocus(mAllowFocus, mBlockProgrammaticFocus, hasRecentTouch)) {
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
