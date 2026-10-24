package org.factorio.local.launcher

import android.app.ActivityManager
import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import org.factorio.local.launcher.ui.LauncherActions
import org.factorio.local.launcher.ui.LauncherScreen
import org.factorio.local.launcher.ui.LauncherState
import org.factorio.local.launcher.ui.theme.FactoryTheme
import org.json.JSONObject

/** Compose owns presentation; Java continues to own imports and the game session. */
class LauncherUi(private val host: MainActivity) {
    private var state by mutableStateOf(LauncherState())
    private val actions =
        LauncherActions(
            host::performUiAction,
            host::setTouchMouseEnabled,
            host::setVirtualGamepadEnabled,
            host::setRenderScale,
            host::setGraphicsPreset,
            host::setSpriteLoadingLimit,
            host::setMaliMesaCompatibility,
        )
    private val memory =
        ActivityManager.MemoryInfo()
            .also {
                (host.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(
                    it
                )
            }
            .totalMem

    fun mount() {
        host.setContent {
            FactoryTheme {
                // The factory artwork always stays on the right, including RTL locales.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    LauncherScreen(state, actions, memory)
                }
            }
        }
    }

    fun show(
        page: Int,
        busy: Boolean,
        message: String,
        game: JSONObject?,
        device: JSONObject?,
        progress: Float,
    ) {
        val preferences = host.getPreferences(0)
        val gl = device?.optJSONObject("graphics")
        state =
            LauncherState(
                page,
                busy,
                message,
                game?.optString("version"),
                game?.optBoolean("spaceAge") ?: false,
                preferences.getBoolean("touchMouseSimulation", true),
                preferences.getBoolean("virtualGamepad", false),
                preferences.getInt("renderScalePercent", 100),
                preferences.getString("graphicsPreset", "compat") ?: "compat",
                preferences.getInt("spriteLoadingLimit", 1),
                GraphicsDriver.current() == GraphicsDriver.MALI,
                preferences.getBoolean("maliMesaCompatibility", false),
                gl?.optString("glRenderer"),
                gl?.optString("glVersion")?.let {
                    Regex("OpenGL ES\\s+\\d+\\.\\d+").find(it)?.value
                },
                gl?.optBoolean("clearReadbackPassed"),
                progress,
            )
    }
}
