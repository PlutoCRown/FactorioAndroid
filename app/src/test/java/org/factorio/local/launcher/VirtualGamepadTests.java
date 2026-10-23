package org.factorio.local.launcher;

import java.util.Arrays;
import org.factorio.local.launcher.input.GamepadEventQueue;
import org.factorio.local.launcher.input.VirtualGamepadState;
import org.factorio.local.launcher.input.VirtualGamepadState.Snapshot;

/** Input ownership, analog ranges and lossless digital edges without an Android device. */
public final class VirtualGamepadTests {
    private static int checks;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] args) {
        VirtualGamepadState pad = new VirtualGamepadState();
        pad.button("a", 0, true);
        check(!pad.snapshot().enabled && pad.snapshot().buttons == 0, "disabled by default");
        pad.enabled(true);
        pad.button("touch", 0, true);
        pad.button("accessible", 0, true);
        pad.button("touch", 0, false);
        pad.button("p4", 19, true);
        check(pad.snapshot().buttons == ((1 << 19) | 1), "independent button owners and paddle");
        pad.button("accessible", 0, false);
        check(pad.snapshot().buttons == 1 << 19, "release only its owner");
        pad.trigger("hold", 0, true);
        pad.trigger("latch", 0, true);
        pad.trigger("hold", 0, false);
        check(pad.snapshot().axes()[4] == Short.MAX_VALUE, "trigger latch survives hold release");
        pad.trigger("latch", 0, false);
        check(pad.snapshot().axes()[4] == Short.MIN_VALUE, "trigger neutral is signed minimum");
        pad.stick(0, .05f, .05f);
        check(pad.snapshot().axes()[0] == 0 && pad.snapshot().axes()[1] == 0, "radial dead zone");
        pad.stick(0, 1, 0);
        check(pad.snapshot().axes()[0] == Short.MAX_VALUE, "positive endpoint");
        pad.stick(0, -1, 0);
        check(pad.snapshot().axes()[0] == Short.MIN_VALUE, "negative endpoint");
        pad.stick(0, 1, 1);
        short[] diagonal = pad.snapshot().axes();
        check(
                Math.abs(diagonal[0] - diagonal[1]) <= 1
                        && Math.hypot(diagonal[0], diagonal[1]) <= 32768,
                "radial clamping");
        pad.stick(1, Float.NaN, Float.POSITIVE_INFINITY);
        check(
                pad.snapshot().axes()[2] == 0 && pad.snapshot().axes()[3] == 0,
                "reject nonfinite motion");
        pad.stick(0, .5f, 0);
        pad.stick(1, 0, -1);
        pad.trigger("rt", 1, true);
        short[] physical = {0, 30000, 100, 0, Short.MAX_VALUE, Short.MIN_VALUE};
        Snapshot merged = VirtualGamepadState.merge(pad.snapshot(), 1 << 2, physical);
        check(
                merged.axes()[0] == 0
                        && merged.axes()[1] == 30000
                        && merged.axes()[3] == Short.MIN_VALUE,
                "stick pair ownership");
        check(
                merged.axes()[4] == Short.MAX_VALUE
                        && merged.axes()[5] == Short.MAX_VALUE
                        && merged.buttons == ((1 << 19) | (1 << 2)),
                "physical and touch combine");
        merged.axes()[0] = 123;
        physical[1] = 123;
        check(merged.axes()[0] == 0 && merged.axes()[1] == 30000, "snapshots own their arrays");
        pad.releaseAll();
        check(
                pad.snapshot().enabled
                        && pad.snapshot().buttons == 0
                        && Arrays.equals(pad.snapshot().axes(), VirtualGamepadState.neutralAxes()),
                "release keeps connection enabled");
        pad.button("a", 0, true);
        pad.enabled(false);
        pad.trigger("rt", 1, true);
        check(
                pad.snapshot().buttons == 0
                        && Arrays.equals(pad.snapshot().axes(), VirtualGamepadState.neutralAxes()),
                "disable clears state and ignores input");
        Snapshot disabled = new Snapshot(false, 1, new short[] {10, 20, 30, 40, 50, 60});
        check(
                VirtualGamepadState.merge(disabled, 2, VirtualGamepadState.neutralAxes()).buttons
                        == 2,
                "disabled input cannot leak");
        queue();
        System.out.println("VirtualGamepadTests: " + checks + " checks passed");
    }

    private static void queue() {
        VirtualGamepadState pad = new VirtualGamepadState();
        pad.enabled(true);
        GamepadEventQueue queue = new GamepadEventQueue();
        Snapshot neutral = pad.snapshot();
        pad.button("a", 0, true);
        queue.offer(pad.snapshot());
        pad.button("a", 0, false);
        queue.offer(pad.snapshot());
        check(
                queue.next(neutral).buttons == 1 && queue.next(neutral).buttons == 0,
                "quick press/release both delivered");
        pad.stick(0, .3f, 0);
        queue.offer(pad.snapshot());
        pad.stick(0, .7f, 0);
        queue.offer(pad.snapshot());
        check(
                queue.next(neutral).axes()[0] == pad.snapshot().axes()[0] && queue.isEmpty(),
                "coalesce only adjacent analog motion");
        pad.trigger("rt", 1, true);
        queue.offer(pad.snapshot());
        pad.trigger("rt", 1, false);
        queue.offer(pad.snapshot());
        check(
                queue.next(neutral).axes()[5] == Short.MAX_VALUE
                        && queue.next(neutral).axes()[5] == Short.MIN_VALUE,
                "trigger edges retained");
        boolean overflow = false;
        for (int i = 0; i < 129; i++) {
            pad.button("a", 0, i % 2 == 0);
            if (!queue.offer(pad.snapshot())) overflow = true;
        }
        check(overflow && queue.isEmpty(), "bounded queue reports overflow and drops stale edges");
        queue.offer(neutral);
        queue.clear();
        check(queue.isEmpty(), "cancellation clears pending edges");
    }
}
