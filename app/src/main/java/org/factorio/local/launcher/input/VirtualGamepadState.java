package org.factorio.local.launcher.input;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** SDL gamepad state owned by touch controls, independent of Android and physical devices. */
public final class VirtualGamepadState {
    public static final int BUTTON_COUNT = 21;
    public static final int BUTTON_MASK = (1 << BUTTON_COUNT) - 1;
    private static final float DEAD_ZONE = 0.12f;
    private final Map<String, Integer> buttons = new HashMap<>();
    private final Set<String> leftTrigger = new HashSet<>(), rightTrigger = new HashSet<>();
    private final short[] axes = neutralAxes();
    private boolean enabled;

    public static final class Snapshot {
        public final boolean enabled;
        public final int buttons;
        private final short[] axes;

        public Snapshot(boolean enabled, int buttons, short[] axes) {
            if ((buttons & ~BUTTON_MASK) != 0 || axes.length != 6)
                throw new IllegalArgumentException("Invalid SDL gamepad state");
            this.enabled = enabled;
            this.buttons = buttons;
            this.axes = axes.clone();
        }

        public short[] axes() {
            return axes.clone();
        }

        public boolean sameButtons(Snapshot other) {
            return enabled == other.enabled
                    && buttons == other.buttons
                    && axes[4] == other.axes[4]
                    && axes[5] == other.axes[5];
        }

        public boolean sameState(Snapshot other) {
            return sameButtons(other) && Arrays.equals(axes, other.axes);
        }
    }

    public static short[] neutralAxes() {
        return new short[] {0, 0, 0, 0, Short.MIN_VALUE, Short.MIN_VALUE};
    }

    public void enabled(boolean value) {
        enabled = value;
        releaseAll();
    }

    public void button(String owner, int button, boolean down) {
        if (button < 0 || button >= BUTTON_COUNT)
            throw new IllegalArgumentException("Invalid button");
        if (!enabled) return;
        if (down) buttons.put(owner, 1 << button);
        else buttons.remove(owner);
    }

    public void trigger(String owner, int trigger, boolean down) {
        if (trigger < 0 || trigger > 1) throw new IllegalArgumentException("Invalid trigger");
        if (!enabled) return;
        Set<String> owners = trigger == 0 ? leftTrigger : rightTrigger;
        if (down) owners.add(owner);
        else owners.remove(owner);
        axes[4 + trigger] = owners.isEmpty() ? Short.MIN_VALUE : Short.MAX_VALUE;
    }

    public void stick(int stick, float x, float y) {
        if (stick < 0 || stick > 1) throw new IllegalArgumentException("Invalid stick");
        if (!enabled) return;
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            x = 0;
            y = 0;
        }
        double distance = Math.hypot(x, y);
        if (distance <= DEAD_ZONE) {
            x = 0;
            y = 0;
        } else {
            double strength = (Math.min(1, distance) - DEAD_ZONE) / (1 - DEAD_ZONE);
            x = (float) (x / distance * strength);
            y = (float) (y / distance * strength);
        }
        axes[stick * 2] = axis(x);
        axes[stick * 2 + 1] = axis(y);
    }

    private static short axis(float value) {
        return (short) Math.round(value * (value < 0 ? 32768 : 32767));
    }

    public void releaseAll() {
        buttons.clear();
        leftTrigger.clear();
        rightTrigger.clear();
        System.arraycopy(neutralAxes(), 0, axes, 0, axes.length);
    }

    public Snapshot snapshot() {
        int mask = 0;
        for (int value : buttons.values()) mask |= value;
        return new Snapshot(enabled, mask, axes);
    }

    /** Sticks choose the stronger device as a pair; triggers and buttons combine independently. */
    public static Snapshot merge(Snapshot virtual, int physicalButtons, short[] physicalAxes) {
        if (!virtual.enabled) return new Snapshot(false, physicalButtons, physicalAxes);
        short[] combined = physicalAxes.clone(), touch = virtual.axes;
        for (int i = 0; i < 4; i += 2) {
            double physical = Math.hypot(combined[i], combined[i + 1]);
            if (Math.hypot(touch[i], touch[i + 1]) > physical) {
                combined[i] = touch[i];
                combined[i + 1] = touch[i + 1];
            }
        }
        combined[4] = (short) Math.max(combined[4], touch[4]);
        combined[5] = (short) Math.max(combined[5], touch[5]);
        return new Snapshot(virtual.enabled, physicalButtons | virtual.buttons, combined);
    }
}
