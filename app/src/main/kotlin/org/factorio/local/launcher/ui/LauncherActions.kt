package org.factorio.local.launcher.ui

internal data class LauncherActions(
    val action: (String) -> Unit,
    val setTouchMouseEnabled: (Boolean) -> Unit,
    val setVirtualGamepadEnabled: (Boolean) -> Unit,
    val setRenderScale: (Int) -> Unit,
    val setGraphicsPreset: (String) -> Unit,
    val setSpriteLoadingLimit: (Int) -> Unit,
)
