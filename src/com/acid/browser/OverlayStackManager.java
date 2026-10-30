package com.acid.browser;

import java.util.ArrayList;
import java.util.List;

/**
 * Dynamic Z-order & Navigation Stack Coordinator for all overlays in Acid Browser.
 *
 * <p>Enforces clear, unambiguous navigation semantics across all modal tiers:
 * <ul>
 *   <li><b>Root modal</b> ({@link #openRootModal}): opens at L1 (no {@code [←]} button;
 *       both Back and {@code [✕]} dismiss the sheet).</li>
 *   <li><b>Child modal</b> ({@link #pushModal}): suspends the parent sheet and opens the child
 *       at L2 with {@code [← Title]} automatically rendered in its header. Tapping {@code [←]}
 *       or pressing system Back pops back to the parent sheet at its exact scroll &amp; sub-page
 *       state; tapping {@code [✕]} or the scrim closes the entire modal flow back to the browser!</li>
 *   <li><b>Tier separation</b>: Modal sheets (Tier 1/2) take strict Back-press priority over
 *       viewport overlays (Tier 0: {@code TabsOverviewView}, {@code AgentFloatingView}).</li>
 * </ul>
 */
public final class OverlayStackManager {

    private final List<AcidOverlay> mRegisteredModals = new ArrayList<AcidOverlay>();
    private final List<AcidOverlay> mRegisteredViewports = new ArrayList<AcidOverlay>();

    /** Active modal Z-order from bottom (0) to top (size - 1). */
    private final List<AcidOverlay> mActiveModalOrder = new ArrayList<AcidOverlay>();
    /** Active viewport Z-order from bottom (0) to top (size - 1). */
    private final List<AcidOverlay> mActiveViewportOrder = new ArrayList<AcidOverlay>();

    /** Suspended parent modals waiting to resume when the user navigates Back. */
    private final List<AcidOverlay> mSuspendedChain = new ArrayList<AcidOverlay>();

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
                }

                @Override
                public void onSheetCloseAllRequested(AcidBottomSheetView sheet) {
                    dismissAllModals();
                }
            });
        }
    }

    public void registerViewport(AcidOverlay viewportOverlay) {
        if (viewportOverlay == null || mRegisteredViewports.contains(viewportOverlay)) return;
        mRegisteredViewports.add(viewportOverlay);
    }

    /**
     * Opens a top-level (L1) modal sheet cleanly without any suspended parent chain.
     */
    public void openRootModal(AcidOverlay modal) {
        if (modal == null) return;
        dismissAllModals();
        if (modal instanceof AcidBottomSheetView) {
            ((AcidBottomSheetView) modal).setParentBackHandler(null);
        }
        modal.show();
        mActiveModalOrder.remove(modal);
        mActiveModalOrder.add(modal);
    }

    /**
     * Suspends {@code parent} and opens {@code child} as an L2 destination with {@code [← Title]}
     * enabled in its header. Tapping {@code [←]} or pressing Back pops back to {@code parent};
     * tapping {@code [✕]} or the scrim closes all modals.
     */
    public void pushModal(final AcidOverlay parent, final AcidOverlay child) {
        if (child == null) return;
        if (parent != null && parent != child && parent.isShowing()) {
            parent.dismiss();
            mSuspendedChain.remove(parent);
            mSuspendedChain.add(parent);
        }
        if (child instanceof AcidBottomSheetView) {
            final AcidBottomSheetView childSheet = (AcidBottomSheetView) child;
            if (!mSuspendedChain.isEmpty()) {
                childSheet.setParentBackHandler(new Runnable() {
                    @Override
                    public void run() {
                        popToParentModal(childSheet);
                    }
                });
            } else {
                childSheet.setParentBackHandler(null);
            }
        }
        child.show();
        mActiveModalOrder.remove(child);
        mActiveModalOrder.add(child);
    }

    private void popToParentModal(AcidBottomSheetView currentChild) {
        currentChild.setParentBackHandler(null);
        currentChild.dismiss();
        if (!mSuspendedChain.isEmpty()) {
            final AcidOverlay parent = mSuspendedChain.remove(mSuspendedChain.size() - 1);
            if (parent != null) {
                if (parent instanceof AcidBottomSheetView) {
                    final AcidBottomSheetView parentSheet = (AcidBottomSheetView) parent;
                    if (!mSuspendedChain.isEmpty()) {
                        parentSheet.setParentBackHandler(new Runnable() {
                            @Override
                            public void run() {
                                popToParentModal(parentSheet);
                            }
                        });
                    } else {
                        parentSheet.setParentBackHandler(null);
                    }
                    parentSheet.show();
                    parentSheet.refreshActivePage();
                } else {
                    parent.show();
                }
            }
        }
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
     * Dismisses every open modal and clears any suspended parent chain (invoked by {@code [✕]},
     * scrim tap, or direct URL navigation).
     */
    public void dismissAllModals() {
        mSuspendedChain.clear();
        List<AcidOverlay> copy = new ArrayList<AcidOverlay>(mRegisteredModals);
        for (AcidOverlay modal : copy) {
            if (modal instanceof AcidBottomSheetView) {
                ((AcidBottomSheetView) modal).setParentBackHandler(null);
            }
            if (modal.isShowing()) {
                modal.dismiss();
            }
        }
        mActiveModalOrder.clear();
    }

    /**
     * Handles system Back key in strict visual Z-order:
     * 1. Topmost visible modal sheet (and its internal sub-page stack / parent chain);
     * 2. Most recently shown visible viewport overlay.
     */
    public boolean handleBackPressed() {
        for (int i = mActiveModalOrder.size() - 1; i >= 0; i--) {
            AcidOverlay modal = mActiveModalOrder.get(i);
            if (modal != null && modal.isShowing() && modal.handleBackPressed()) {
                return true;
            }
        }
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
