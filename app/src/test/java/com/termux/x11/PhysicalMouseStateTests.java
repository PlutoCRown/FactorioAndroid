package com.termux.x11;

import java.util.*;

public final class PhysicalMouseStateTests {
    private static int checks;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] args) {
        List<String> buttons = new ArrayList<>();
        float[] xy = new float[4];
        PhysicalMouseState mouse =
                new PhysicalMouseState(
                        new PhysicalMouseState.Sink() {
                            public void move(float x, float y) {
                                xy[0] = x;
                                xy[1] = y;
                            }

                            public void button(int button, boolean down) {
                                buttons.add(button + ":" + down);
                            }

                            public void scroll(float x, float y) {
                                xy[2] = x;
                                xy[3] = y;
                            }
                        });
        mouse.viewport(1280, 720, 640, 360);
        check(
                mouse.pointer(400, 200, 0) && xy[0] == 200 && xy[1] == 100 && buttons.isEmpty(),
                "hover moves game pointer without clicks at render scale");
        mouse.pointer(400, 200, 1);
        mouse.pointer(500, 250, 1);
        mouse.pointer(500, 250, 1);
        check(
                buttons.equals(Arrays.asList("1:true")),
                "drag and duplicate button events do not repeat DOWN");
        mouse.pointer(500, 250, 4);
        check(
                buttons.equals(Arrays.asList("1:true", "1:false", "3:true")),
                "left release precedes right press");
        mouse.pointer(500, 250, 0);
        mouse.pointer(500, 250, 2);
        mouse.cancel();
        check(
                buttons.subList(3, 6).equals(Arrays.asList("3:false", "2:true", "2:false")),
                "middle and right buttons use X11 identities");
        mouse.rightButton(true);
        mouse.rightButton(true);
        mouse.rightButton(false);
        mouse.rightButton(false);
        check(
                mouse.presses() == mouse.releases() && mouse.held() == 0,
                "mouse BACK fallback has balanced deduplicated releases");
        mouse.scroll(.125f, -.25f);
        check(
                xy[2] == .125f && xy[3] == -.25f && mouse.scrolls() == 1,
                "hardware wheel preserves fractions on both axes");
        mouse.scroll(Float.NaN, 0);
        check(mouse.scrolls() == 1, "invalid wheel event is ignored");
        mouse.pointer(500, 250, 4);
        mouse.viewport(1280, 720, 1280, 720);
        check(
                mouse.held() == 0 && mouse.presses() == mouse.releases(),
                "resize releases held hardware buttons");
        mouse.pointer(2000, -10, 0);
        check(xy[0] == 1279 && xy[1] == 0, "out of bounds pointer is clamped");
        mouse.pointer(1, 1, 1);
        check(
                !mouse.pointer(Float.NaN, 1, 1) && mouse.held() == 0,
                "invalid pointer releases buttons");
        System.out.println("PhysicalMouseStateTests: " + checks + " passed");
    }
}
