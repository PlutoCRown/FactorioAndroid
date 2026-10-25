package org.factorio.local.launcher;

import java.util.Set;

public final class GraphicsDriverTests {
    private static int checks;

    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }

    public static void main(String[] args) {
        check(
                !GraphicsCapabilities.needsLegacy(false, false, false),
                "Unknown capabilities preserve existing selection");
        check(
                !GraphicsCapabilities.needsLegacy(true, true, true),
                "Complete features keep current Mesa");
        check(
                GraphicsCapabilities.needsLegacy(true, false, true),
                "Missing dynamic rendering selects traditional path");
        check(
                GraphicsCapabilities.needsLegacy(true, true, false),
                "Missing null descriptors selects traditional path");
        check(
                GraphicsCapabilities.needsLegacy(true, false, false),
                "Both missing select one coherent traditional path");
        GraphicsDriver adreno = GraphicsDriver.detect("/vendor/lib64/hw/vulkan.adreno.so"::equals);
        check(
                adreno == GraphicsDriver.ADRENO && adreno.compatibleMesa(),
                "Adreno keeps its existing compatibility providers");
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
                    mali == GraphicsDriver.MALI && !mali.compatibleMesa(),
                    "Mali detection does not select the ineffective compatibility providers: "
                            + path);
        }
        GraphicsDriver other = GraphicsDriver.detect(path -> false);
        check(
                other == GraphicsDriver.OTHER && !other.compatibleMesa(),
                "Other drivers keep baseline providers");
        Set<String> both =
                Set.of("/vendor/lib64/hw/vulkan.adreno.so", "/vendor/lib64/egl/libGLES_mali.so");
        check(
                GraphicsDriver.detect(both::contains) == GraphicsDriver.ADRENO,
                "Adreno selection takes precedence if both files exist");
        System.out.println("GraphicsDriverTests: " + checks + " checks passed");
    }
}
