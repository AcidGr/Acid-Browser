package com.acid.browser;

import java.util.ArrayList;
import java.util.List;

/**
 * Dynamic Z-order & Back-Stack Coordinator for all overlays in Acid Browser.
 *
 * <p>Solves three structural bugs in the previous static {@code List<AcidOverlay>} design:
 * <ol>
 *   <li><b>Z-order sync</b>: Whenever an overlay is opened, it moves to the top of the active
 *       stack so {@link #handleBackPressed()} always closes the visually topmost surface first.</li>
 *   <li><b>Modal suspension chain</b>: When a secondary sheet (e.g. {@code ModelProviderConfigSheet}
 *       or {@code AdBlockSheet}) is launched from inside another sheet (e.g. {@code SettingsSheet}
 *       or {@code AgentModelSelectorSheet}), the parent sheet is suspended cleanly and restored
 *       automatically when the child sheet is closed via Back/Close/Scrim.</li>
 *   <li><b>Tier separation</b>: Full-screen modal sheets (Tier 1/2) take strict back-press
 *       priority over viewport-embedded overlays (Tier 0: {@code TabsOverviewView},
 *       {@code AgentFloatingView}).</li>
 * </ol>
 */
public final class OverlayStackManager {

    private final List<AcidOverlay> mRegisteredModals = new ArrayList<AcidOverlay>();
    private final List<AcidOverlay> mRegisteredViewports = new ArrayList<AcidOverlay>();

    /** Active modal Z-order from bottom (0) to top (size - 1). */
    private final List<AcidOverlay> mActiveModalOrder = new ArrayList<AcidOverlay>();
    /** Active viewport Z-order from bottom (0) to top (size - 1). */
    private final List<AcidOverlay> mActiveViewportOrder = new ArrayList<AcidOverlay>();

    /** Suspended parent modals waiting to resume once the top modal closes. */
    private final List<AcidOverlay> mSuspendedChain = new ArrayList<AcidOverlay>();
    private boolean mSuppressResume = false;

    public void registerModal(final AcidOverlay modal) {
        if (modal == null || mRegisteredModals.contains(modal)) return;
        mRegisteredModals.add(modal);
        if (modal instanceof AcidBottomSheetView) {
            ((AcidBottomSheetView) modal).addLifecycleObserver(new AcidBottomSheetView.LifecycleObserver() {
                @Override
                public void onSheetShown(AcidBottomSheetView sheet) {
                    mActiveModalOrder.remove(sheet);
                    mActiveModalOrder.add(sheet);
                }

                @Override
                public void onSheetDismissed(AcidBottomSheetView sheet) {
                    mActiveModalOrder.remove(sheet);
                    if (!mSuppressResume && mActiveModalOrder.isEmpty() && !mSuspendedChain.isEmpty()) {
                        AcidOverlay parent = mSuspendedChain.remove(mSuspendedChain.size() - 1);
                        if (parent != null) {
                            parent.show();
                            if (parent instanceof AcidBottomSheetView) {
                                ((AcidBottomSheetView) parent).refreshActivePage();
                            }
                        }
                    }
                }
            });
        }
    }

    public void registerViewport(AcidOverlay viewportOverlay) {
        if (viewportOverlay == null || mRegisteredViewports.contains(viewportOverlay)) return;
        mRegisteredViewports.add(viewportOverlay);
    }

    /**
     * Opens a top-level modal sheet cleanly without any suspended parent chain.
     */
    public void openRootModal(AcidOverlay modal) {
        if (modal == null) return;
        mSuspendedChain.clear();
        modal.show();
        mActiveModalOrder.remove(modal);
        mActiveModalOrder.add(modal);
    }

    /**
     * Suspends {@code parent} and opens {@code child}; closing {@code child} automatically
     * resumes {@code parent} at its exact previous scroll & sub-page state.
     */
    public void pushModal(AcidOverlay parent, AcidOverlay child) {
        if (child == null) return;
        if (parent != null && parent != child && parent.isShowing()) {
            mSuppressResume = true;
            try {
                parent.dismiss();
            } finally {
                mSuppressResume = false;
            }
            mSuspendedChain.remove(parent);
            mSuspendedChain.add(parent);
        }
        child.show();
        mActiveModalOrder.remove(child);
        mActiveModalOrder.add(child);
    }

    /**
     * Marks a viewport overlay (TabsOverview / AgentFloatingView) as the most recently shown
     * Tier-0 surface so Back closes them in LIFO order.
     */
    public void notifyViewportShown(AcidOverlay viewport) {
        if (viewport == null) return;
        mActiveViewportOrder.remove(viewport);
        mActiveViewportOrder.add(viewport);
    }

    /**
     * Dismisses every open modal and clears any suspended parent chain (used when navigating
     * directly to a URL from inside a nested sheet).
     */
    public void dismissAllModals() {
        mSuppressResume = true;
        try {
            mSuspendedChain.clear();
            List<AcidOverlay> copy = new ArrayList<AcidOverlay>(mRegisteredModals);
            for (AcidOverlay modal : copy) {
                if (modal.isShowing()) {
                    modal.dismiss();
                }
            }
            mActiveModalOrder.clear();
        } finally {
            mSuppressResume = false;
        }
    }

    /**
     * Handles system Back key in strict visual Z-order:
     * 1. Topmost visible modal sheet (and its internal sub-page stack);
     * 2. Most recently shown visible viewport overlay;
     * 3. Any remaining visible registered overlay as fallback.
     */
    public boolean handleBackPressed() {
        // 1. Active modals in LIFO visual order
        for (int i = mActiveModalOrder.size() - 1; i >= 0; i--) {
            AcidOverlay modal = mActiveModalOrder.get(i);
            if (modal != null && modal.isShowing() && modal.handleBackPressed()) {
                return true;
            }
        }
        // Fallback scan in case a non-BottomSheet modal showed itself directly
        for (int i = mRegisteredModals.size() - 1; i >= 0; i--) {
            AcidOverlay modal = mRegisteredModals.get(i);
            if (modal != null && modal.isShowing() && modal.handleBackPressed()) {
                return true;
            }
        }

        // 2. Active viewport overlays in LIFO order
        for (int i = mActiveViewportOrder.size() - 1; i >= 0; i--) {
            AcidOverlay vp = mActiveViewportOrder.get(i);
            if (vp != null && vp.isShowing() && vp.handleBackPressed()) {
                return true;
            }
        }
        for (int i = mRegisteredViewports.size() - 1; i >= 0; i--) {
            AcidOverlay vp = mRegisteredViewports.get(i);
            if (vp != null && vp.isShowing() && vp.handleBackPressed()) {
                return true;
            }
        }
        return false;
    }

    public void applyTheme(boolean dark) {
        for (AcidOverlay vp : mRegisteredViewports) {
            vp.applyTheme(dark);
        }
        for (AcidOverlay modal : mRegisteredModals) {
            modal.applyTheme(dark);
        }
    }
}
