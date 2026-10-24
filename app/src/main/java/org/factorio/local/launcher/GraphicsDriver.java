package org.factorio.local.launcher;

import java.io.File;
import java.util.function.Predicate;

/** Driver-file selection only; no GPU context or engine process is started. */
enum GraphicsDriver {
    ADRENO,
    MALI,
    OTHER;

    static GraphicsDriver current() {
        return detect(path -> new File(path).isFile());
    }

    static GraphicsDriver detect(Predicate<String> fileExists) {
        if (fileExists.test("/vendor/lib64/hw/vulkan.adreno.so")) return ADRENO;
        String[] maliPaths = {
            "/vendor/lib64/egl/libGLES_mali.so",
            "/vendor/lib/egl/libGLES_mali.so",
            "/vendor/lib64/libGLES_mali.so",
            "/vendor/lib/libGLES_mali.so",
            "/system/vendor/lib64/egl/libGLES_mali.so",
            "/odm/lib64/egl/libGLES_mali.so"
        };
        for (String path : maliPaths) if (fileExists.test(path)) return MALI;
        return OTHER;
    }

    boolean compatibleMesa(boolean maliRequested) {
        return this == ADRENO || (this == MALI && maliRequested);
    }
}
