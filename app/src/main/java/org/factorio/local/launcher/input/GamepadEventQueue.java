package org.factorio.local.launcher.input;

import java.util.ArrayDeque;

/** Preserve digital edges; only adjacent motion samples with the same held keys can coalesce. */
public final class GamepadEventQueue {
    private final ArrayDeque<VirtualGamepadState.Snapshot> pending = new ArrayDeque<>();
    private static final int LIMIT = 128;

    public boolean offer(VirtualGamepadState.Snapshot next) {
        VirtualGamepadState.Snapshot last = pending.peekLast();
        if (last != null && last.sameState(next)) return true;
        if (last != null && last.sameButtons(next)) pending.removeLast();
        if (pending.size() >= LIMIT) {
            pending.clear();
            return false;
        }
        pending.addLast(next);
        return true;
    }

    public VirtualGamepadState.Snapshot next(VirtualGamepadState.Snapshot current) {
        VirtualGamepadState.Snapshot next = pending.pollFirst();
        return next == null ? current : next;
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    public void clear() {
        pending.clear();
    }
}
