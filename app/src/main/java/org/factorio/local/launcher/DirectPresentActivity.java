package org.factorio.local.launcher;

import android.content.Context;
import android.view.Surface;
import java.io.File;

/**
 * Surface transport helper. Historical class name retained for the verified JNI ABI; this class is
 * no longer an Android Activity or a standalone diagnostic entry.
 */
public final class DirectPresentActivity {
    private DirectPresentActivity() {}

    public static native void detach();

    private static native void present(Surface surface, String socket, String report);

    public static void startGameSurface(Context context, Surface surface, File reportFile) {
        System.loadLibrary(
                (MesaRuntime.compatibleDevice()
                                || GraphicsRuntimeEvaluation.useSourceAdapters(context))
                        ? "rea_compat_direct_present"
                        : "rea_direct_present");
        final String socket =
                new File(context.getFilesDir(), "direct-present.sock").getAbsolutePath();
        final String report = reportFile.getAbsolutePath();
        new Thread(() -> present(surface, socket, report), "direct-game-surface").start();
    }
}
