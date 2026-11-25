package com.acid.browser;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-tab single-flight gate protecting WebView renderer main threads from JS queue pileups.
 *
 * <p>Android's {@code WebView.evaluateJavascript} has no cancellation API: once a script times
 * out on the Java side, that work keeps running in Chromium's renderer queue. When a tab times out,
 * this gate marks it congested and short-circuits subsequent JS calls until either the in-flight
 * work drains, navigation resets the tab's generation, or a lightweight cooldown probe succeeds.
 */
public final class RendererSingleFlightGate {

    public static final long DEFAULT_RECOVERY_PROBE_COOLDOWN_MS = 8000L;
    public static final long STALE_PROBE_EXPIRY_MULTIPLIER = 3L;

    public static final class Ticket {
        static final Ticket UNTRACKED = new Ticket(-1, 0L);

        public final int tabId;
        public final long generation;
        private final AtomicBoolean mDone = new AtomicBoolean(false);

        Ticket(int tabId, long generation) {
            this.tabId = tabId;
            this.generation = generation;
        }

        boolean markDoneOnce() {
            return mDone.compareAndSet(false, true);
        }

        boolean isDone() {
            return mDone.get();
        }
    }

    public static final class ProbeTicket {
        public final int tabId;
        public final long generation;
        private final AtomicBoolean mDone = new AtomicBoolean(false);

        ProbeTicket(int tabId, long generation) {
            this.tabId = tabId;
            this.generation = generation;
        }

        boolean markDoneOnce() {
            return mDone.compareAndSet(false, true);
        }
    }

    private static final class Slot {
        long generation = 1L;
        int inFlightCount = 0;
        long congestedSinceMs = 0L;
        ProbeTicket activeProbe = null;
        long lastProbeAtMs = 0L;
    }

    private final long mRecoveryProbeCooldownMs;
    private final Map<Integer, Slot> mSlots = new HashMap<Integer, Slot>();

    public RendererSingleFlightGate() {
        this(DEFAULT_RECOVERY_PROBE_COOLDOWN_MS);
    }

    public RendererSingleFlightGate(long recoveryProbeCooldownMs) {
        mRecoveryProbeCooldownMs = Math.max(500L, recoveryProbeCooldownMs);
    }

    public synchronized boolean isTabCongested(int tabId) {
        if (tabId <= 0) return false;
        Slot slot = mSlots.get(tabId);
        return slot != null && slot.inFlightCount > 0 && slot.congestedSinceMs > 0L;
    }

    public synchronized Ticket tryBegin(int tabId) {
        if (tabId <= 0) {
            return Ticket.UNTRACKED;
        }
        if (isTabCongested(tabId)) {
            return null;
        }
        Slot slot = mSlots.get(tabId);
        if (slot == null) {
            slot = new Slot();
            mSlots.put(tabId, slot);
        }
        slot.inFlightCount++;
        return new Ticket(tabId, slot.generation);
    }

    public synchronized void onCompleted(Ticket ticket) {
        if (ticket == null || ticket.tabId <= 0 || !ticket.markDoneOnce()) {
            return;
        }
        Slot slot = mSlots.get(ticket.tabId);
        if (slot == null || slot.generation != ticket.generation) {
            return;
        }
        slot.inFlightCount = Math.max(0, slot.inFlightCount - 1);
        if (slot.inFlightCount == 0) {
            slot.congestedSinceMs = 0L;
            slot.activeProbe = null;
            slot.lastProbeAtMs = 0L;
        }
    }

    public synchronized void onTimedOut(Ticket ticket, long nowMs) {
        if (ticket == null || ticket.tabId <= 0 || ticket.isDone()) {
            return;
        }
        Slot slot = mSlots.get(ticket.tabId);
        if (slot == null || slot.generation != ticket.generation) {
            return;
        }
        if (slot.inFlightCount > 0 && slot.congestedSinceMs == 0L) {
            slot.congestedSinceMs = Math.max(1L, nowMs);
        }
    }

    public synchronized ProbeTicket tryBeginRecoveryProbe(int tabId, long nowMs) {
        if (!isTabCongested(tabId)) {
            return null;
        }
        Slot slot = mSlots.get(tabId);
        if (slot == null) {
            return null;
        }
        if (slot.activeProbe != null
            && (nowMs - slot.lastProbeAtMs) < (mRecoveryProbeCooldownMs * STALE_PROBE_EXPIRY_MULTIPLIER)) {
            return null;
        }
        long anchor = Math.max(slot.congestedSinceMs, slot.lastProbeAtMs);
        if ((nowMs - anchor) < mRecoveryProbeCooldownMs) {
            return null;
        }
        ProbeTicket probe = new ProbeTicket(tabId, slot.generation);
        slot.activeProbe = probe;
        slot.lastProbeAtMs = Math.max(1L, nowMs);
        return probe;
    }

    public synchronized void onRecoveryProbeFinished(ProbeTicket probe, boolean alive) {
        if (probe == null || !probe.markDoneOnce()) {
            return;
        }
        Slot slot = mSlots.get(probe.tabId);
        if (slot == null || slot.generation != probe.generation) {
            return;
        }
        if (slot.activeProbe == probe) {
            slot.activeProbe = null;
        }
        if (alive) {
            slot.inFlightCount = 0;
            slot.congestedSinceMs = 0L;
            slot.activeProbe = null;
            slot.lastProbeAtMs = 0L;
        }
    }

    public synchronized void resetTab(int tabId) {
        if (tabId <= 0) return;
        Slot slot = mSlots.get(tabId);
        if (slot == null) {
            return;
        }
        slot.generation++;
        slot.inFlightCount = 0;
        slot.congestedSinceMs = 0L;
        slot.activeProbe = null;
        slot.lastProbeAtMs = 0L;
    }

    public synchronized void removeTab(int tabId) {
        if (tabId <= 0) return;
        mSlots.remove(tabId);
    }

    public synchronized void clearAll() {
        mSlots.clear();
    }
}
