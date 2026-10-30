package com.acid.browser;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Unified reactive state contract shared by persisted {@link AcidSettings} keys and ephemeral
 * runtime states (incognito mode, desktop viewport mode, MCP server active state).
 *
 * <p>Allows UI rows ({@code AcidWidgets.switchRow}) and menu tiles ({@code BrowserMenuSheet})
 * to bind declaratively to a single {@link BoolState} instead of wiring ad-hoc listener methods
 * through {@code BrowserActivity}.
 */
public final class StateSource {

    private StateSource() {}

    public interface Observer {
        void onChanged(boolean value);
    }

    public interface SetterHook {
        /**
         * Performs the underlying state transition when a caller invokes {@link BoolState#set(boolean)}.
         * Implementations return the resulting authoritative boolean state.
         */
        boolean apply(boolean requestedValue);
    }

    public interface BoolState {
        boolean get();
        void set(boolean value);
        void toggle();
        void observe(Observer observer);
        void unobserve(Observer observer);
    }

    /**
     * Thread-safe in-memory {@link BoolState} with optional action hook for runtime toggles
     * such as Incognito Mode, Desktop Mode, or MCP Server start/stop.
     */
    public static final class MutableBoolState implements BoolState {
        private volatile boolean mValue;
        private final SetterHook mHook;
        private final List<Observer> mObservers = new CopyOnWriteArrayList<Observer>();

        public MutableBoolState(boolean initialValue) {
            this(initialValue, null);
        }

        public MutableBoolState(boolean initialValue, SetterHook hook) {
            mValue = initialValue;
            mHook = hook;
        }

        @Override
        public boolean get() {
            return mValue;
        }

        @Override
        public void set(boolean value) {
            boolean next = (mHook != null) ? mHook.apply(value) : value;
            updateValueSilentlyOrNotify(next, true);
        }

        /**
         * Synchronizes state from an external source without re-invoking {@link SetterHook},
         * notifying observers if the value changed.
         */
        public void syncExternal(boolean actualValue) {
            updateValueSilentlyOrNotify(actualValue, true);
        }

        private void updateValueSilentlyOrNotify(boolean next, boolean notify) {
            boolean changed = (mValue != next);
            mValue = next;
            if (changed && notify) {
                for (Observer obs : mObservers) {
                    obs.onChanged(next);
                }
            }
        }

        @Override
        public void toggle() {
            set(!get());
        }

        @Override
        public void observe(Observer observer) {
            if (observer != null && !mObservers.contains(observer)) {
                mObservers.add(observer);
            }
        }

        @Override
        public void unobserve(Observer observer) {
            if (observer != null) {
                mObservers.remove(observer);
            }
        }
    }
}
