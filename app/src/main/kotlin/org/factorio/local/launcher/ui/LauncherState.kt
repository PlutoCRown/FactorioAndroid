package org.factorio.local.launcher.ui

internal data class LauncherState(
    val page: Int = 0,
    val busy: Boolean = false,
    val message: String = "",
    val version: String? = null,
    val spaceAge: Boolean = false,
    val touchMouse: Boolean = true,
    val virtualGamepad: Boolean = false,
    val scale: Int = 100,
    val preset: String = "compat",
    val spriteLoadingLimit: Int = 1,
    val gpu: String? = null,
    val api: String? = null,
    val graphicsPassed: Boolean? = null,
    val progress: Float = -1f,
)
