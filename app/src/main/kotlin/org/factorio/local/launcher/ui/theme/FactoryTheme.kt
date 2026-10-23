package org.factorio.local.launcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val Coal = Color(0xff242324)
internal val Panel = Color(0xff313031)
internal val Ink = Color(0xffffe6c0)
internal val Muted = Color(0xffb6b1ad)
internal val Copper = Color(0xffe39827)
private val FactoryColors =
    darkColorScheme(
        primary = Copper,
        onPrimary = Coal,
        secondary = Copper,
        background = Coal,
        onBackground = Ink,
        surface = Panel,
        onSurface = Ink,
        onSurfaceVariant = Muted,
        outline = Color(0xff77716c),
    )

@Composable
internal fun FactoryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FactoryColors, content = content)
}
