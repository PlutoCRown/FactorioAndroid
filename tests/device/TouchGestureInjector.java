package org.factorio.tests;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/** Shell-only device test helper, never included in the launcher APK. */
public final class TouchGestureInjector {
    private static Object manager;
    private static Method inject;
    private static long down;
    private static int delivered;

    private static void ownFocus() throws Exception {
        Process p = new ProcessBuilder("dumpsys", "window").redirectErrorStream(true).start();
        String focus = "";
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            for (String line; (line = reader.readLine()) != null; )
                if (line.trim().startsWith("mCurrentFocus=")) focus = line;
        }
        if (p.waitFor() != 0
                || !focus.contains("org.factorio.local.launcher/com.termux.x11.MainActivity"))
            throw new IllegalStateException("Own game is not focused; no gesture injection");
    }

    private static void event(int action, float x, float y, boolean second) throws Exception {
        int count = second ? 2 : 1;
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i == 0 ? 1 : 9;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords();
            coordinates[i].x = x + i * 100;
            coordinates[i].y = y;
            coordinates[i].pressure = 1;
            coordinates[i].size = 1;
        }
        MotionEvent e =
                MotionEvent.obtain(
                        down,
                        SystemClock.uptimeMillis(),
                        action,
                        count,
                        properties,
                        coordinates,
                        0,
                        0,
                        1,
                        1,
                        0,
                        0,
                        InputDevice.SOURCE_TOUCHSCREEN,
                        0);
        try {
            if (!((Boolean) inject.invoke(manager, e, 2)))
                throw new IllegalStateException("Input injection rejected");
            delivered++;
        } finally {
            e.recycle();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("mode x y");
        String mode = args[0];
        if (!mode.matches("right|scroll|tap|drag|rightcancel"))
            throw new IllegalArgumentException("Unknown own-app gesture");
        float x = Float.parseFloat(args[1]), y = Float.parseFloat(args[2]);
        ownFocus();
        Class<?> type = Class.forName("android.hardware.input.InputManager");
        Method get = type.getDeclaredMethod("getInstance");
        get.setAccessible(true);
        manager = get.invoke(null);
        inject = type.getDeclaredMethod("injectInputEvent", InputEvent.class, int.class);
        inject.setAccessible(true);
        down = SystemClock.uptimeMillis();
        boolean two = false, finished = false;
        try {
            event(MotionEvent.ACTION_DOWN, x, y, false);
            if (mode.startsWith("right")) {
                SystemClock.sleep(850);
                event(
                        mode.equals("rightcancel")
                                ? MotionEvent.ACTION_CANCEL
                                : MotionEvent.ACTION_UP,
                        x,
                        y,
                        false);
            } else if (mode.equals("tap")) {
                SystemClock.sleep(70);
                event(MotionEvent.ACTION_UP, x, y, false);
            } else if (mode.equals("drag")) {
                for (int i = 1; i <= 5; i++) {
                    SystemClock.sleep(45);
                    event(MotionEvent.ACTION_MOVE, x + i * 30, y, false);
                }
                event(MotionEvent.ACTION_UP, x + 150, y, false);
            } else {
                SystemClock.sleep(45);
                two = true;
                event(
                        MotionEvent.ACTION_POINTER_DOWN
                                | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        x,
                        y,
                        true);
                for (int i = 1; i <= 8; i++) {
                    SystemClock.sleep(35);
                    event(MotionEvent.ACTION_MOVE, x, y - i * 45, true);
                }
                event(
                        MotionEvent.ACTION_POINTER_UP
                                | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        x,
                        y - 360,
                        true);
                two = false;
                event(MotionEvent.ACTION_UP, x, y - 360, false);
            }
            finished = true;
        } finally {
            if (!finished)
                try {
                    event(MotionEvent.ACTION_CANCEL, x, y, two);
                } catch (Exception ignored) {
                }
        }
        System.out.println("OWN_TOUCH_TEST mode=" + mode + " delivered=" + delivered);
    }
}
