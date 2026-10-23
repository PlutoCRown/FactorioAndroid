package org.factorio.local.launcher;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.view.*;

/** Landscape on a full screen; leave split/freeform window geometry to Android. */
public final class WindowPolicy {
    private WindowPolicy() {}

    public static void apply(Activity activity) {
        boolean windowed = activity.isInMultiWindowMode();
        int orientation =
                windowed
                        ? ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        if (activity.getRequestedOrientation() != orientation)
            activity.setRequestedOrientation(orientation);
        Window window = activity.getWindow();
        // onCreate can precede setContentView: PhoneWindow.getInsetsController()
        // dereferences mDecor. Materialize the decor first, then allow its
        // controller to remain null until attachment/focus.
        View decor = window.getDecorView();
        if (windowed) window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        else window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.layoutInDisplayCutoutMode =
                    windowed
                            ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                            : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attrs);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(windowed);
            WindowInsetsController bars = decor.getWindowInsetsController();
            if (bars != null) {
                bars.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (windowed) bars.show(WindowInsets.Type.systemBars());
                else bars.hide(WindowInsets.Type.systemBars());
            }
        } else
            window.getDecorView()
                    .setSystemUiVisibility(
                            windowed
                                    ? View.SYSTEM_UI_FLAG_VISIBLE
                                    : View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
