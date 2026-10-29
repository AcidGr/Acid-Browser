package com.acid.browser;

/**
 * Common contract for every full-screen overlay that can sit on top of the browser chrome:
 * bottom sheets, the tab overview, and the floating agent panel.
 *
 * <p>Before this existed each overlay exposed its own vocabulary ({@code dismiss()} on the
 * sheets, {@code close()} on the tab overview and the agent panel), so every caller had to
 * remember which verb applied, and {@link BrowserActivity} had to hand-maintain three
 * parallel if-chains for theme propagation, back-button handling and dismissal. The registry
 * in {@code BrowserActivity} now drives all three from one list.
 *
 * <p>Implementations must raise themselves in {@link #show()}; that is what guarantees a
 * newly opened overlay lands above whatever was already on screen.
 */
public interface AcidOverlay {

    /** True while the overlay is on screen and should receive back presses. */
    boolean isShowing();

    /** Reveals the overlay and brings it to the front of its host container. */
    void show();

    /** Hides the overlay. Safe to call when already hidden. */
    void dismiss();

    /** Re-applies the light or dark palette. */
    void applyTheme(boolean dark);

    /**
     * Consumes a back press when this overlay owns it.
     *
     * <p>Most overlays simply dismiss themselves, but a few are multi-state (the provider
     * config sheet walks edit/add back to the list), so the decision belongs to the overlay
     * rather than to the host.
     *
     * @return true when the press was handled and must not propagate.
     */
    boolean handleBackPressed();
}
