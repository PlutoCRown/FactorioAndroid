package org.factorio.local.launcher;

import android.content.Context;
import java.io.*;
import java.util.UUID;
import org.json.JSONObject;

/** Source-built GNU SDL relay. The child resolves its own SDL3 interfaces at runtime. */
public final class GamepadRuntime {
    private GamepadRuntime() {}

    public static JSONObject prepare(Context context) throws Exception {
        File library =
                new File(context.getApplicationInfo().nativeLibraryDir, "librea_gamepad_bridge.so");
        RuntimeLibraries.requireLibrary(library);
        File directory = new File(context.getCacheDir(), "x11");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot prepare private gamepad directory");
        File socket =
                new File(
                        directory,
                        "pad-"
                                + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                                + ".sock");
        if (socket.getAbsolutePath().length() > 100 || socket.exists())
            throw new IOException("Invalid private gamepad socket path");
        return new JSONObject()
                .put("id", "sdl3-symbol-relay")
                .put("interfaceResolution", "loaded-elf-symbols")
                .put("library", library.getAbsolutePath())
                .put("gamepadSocket", socket.getAbsolutePath())
                .put("sameUidSocketOnly", true)
                .put("libraryLoadedInArt", false);
    }
}
