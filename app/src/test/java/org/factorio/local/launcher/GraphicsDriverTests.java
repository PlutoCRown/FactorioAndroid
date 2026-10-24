package org.factorio.local.launcher;

import java.util.Set;

public final class GraphicsDriverTests {
    private static int checks;

    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }

    public static void main(String[] args) {
        GraphicsDriver adreno = GraphicsDriver.detect("/vendor/lib64/hw/vulkan.adreno.so"::equals);
        check(
                adreno == GraphicsDriver.ADRENO
                        && adreno.compatibleMesa(false)
                        && adreno.compatibleMesa(true),
                "Adreno keeps compatibility providers regardless of Mali preference");
        for (String path :
                new String[] {
                    "/vendor/lib64/egl/libGLES_mali.so",
                    "/vendor/lib/egl/libGLES_mali.so",
                    "/vendor/lib64/libGLES_mali.so",
                    "/vendor/lib/libGLES_mali.so",
                    "/system/vendor/lib64/egl/libGLES_mali.so",
                    "/odm/lib64/egl/libGLES_mali.so"
                }) {
            GraphicsDriver mali = GraphicsDriver.detect(path::equals);
            check(
                    mali == GraphicsDriver.MALI && !mali.compatibleMesa(false),
                    "Mali keeps baseline providers by default: " + path);
            check(mali.compatibleMesa(true), "Mali can opt into compatibility providers: " + path);
        }
        GraphicsDriver other = GraphicsDriver.detect(path -> false);
        check(
                other == GraphicsDriver.OTHER
                        && !other.compatibleMesa(false)
                        && !other.compatibleMesa(true),
                "Other drivers ignore Mali preference and keep baseline providers");
        Set<String> both =
                Set.of("/vendor/lib64/hw/vulkan.adreno.so", "/vendor/lib64/egl/libGLES_mali.so");
        check(
                GraphicsDriver.detect(both::contains) == GraphicsDriver.ADRENO,
                "Adreno selection takes precedence if both files exist");
        System.out.println("GraphicsDriverTests: " + checks + " checks passed");
    }
}
