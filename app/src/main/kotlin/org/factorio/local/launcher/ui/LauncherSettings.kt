package org.factorio.local.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.factorio.local.launcher.R
import org.factorio.local.launcher.ui.components.*
import org.factorio.local.launcher.ui.theme.Copper

@Composable
internal fun LauncherSettings(s: LauncherState, actions: LauncherActions) {
    Eyebrow()
    Text(stringResource(R.string.launch_settings), style = MaterialTheme.typography.headlineMedium)
    Hint(R.string.settings_hint)
    FactoryPanel {
        Row(
            Modifier.fillMaxWidth()
                .toggleable(
                    s.touchMouse,
                    enabled = !s.busy,
                    role = Role.Switch,
                    onValueChange = actions.setTouchMouseEnabled,
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.touch_mouse),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(checked = s.touchMouse, onCheckedChange = null, enabled = !s.busy)
        }
        Hint(R.string.touch_mouse_hint)
        HorizontalDivider()
        Field(R.string.render_resolution, R.string.render_resolution_hint)
        val scales = listOf(100, 75, 50)
        Choice(
            listOf(R.string.scale_native, R.string.scale_balanced, R.string.scale_smooth),
            scales.indexOf(s.scale).coerceAtLeast(0),
            R.string.render_resolution,
            !s.busy,
        ) {
            actions.setRenderScale(scales[it])
        }
        HorizontalDivider()
        Field(R.string.graphics_preset, R.string.graphics_preset_hint)
        val presets = listOf("compat", "balanced", "quality", "game")
        Choice(
            listOf(
                R.string.preset_compat,
                R.string.preset_balanced,
                R.string.preset_quality,
                R.string.preset_game,
            ),
            presets.indexOf(s.preset).coerceAtLeast(0),
            R.string.graphics_preset,
            !s.busy,
        ) {
            actions.setGraphicsPreset(presets[it])
        }
        HorizontalDivider()
        Field(R.string.sprite_loading_limit, R.string.sprite_loading_limit_hint)
        val loadingLimits = listOf(1, 2, 4, 8, 16, 32, 0)
        Choice(
            listOf(
                R.string.loading_limit_1,
                R.string.loading_limit_2,
                R.string.loading_limit_4,
                R.string.loading_limit_8,
                R.string.loading_limit_16,
                R.string.loading_limit_32,
                R.string.loading_limit_game,
            ),
            loadingLimits.indexOf(s.spriteLoadingLimit).coerceAtLeast(0),
            R.string.sprite_loading_limit,
            !s.busy,
        ) {
            actions.setSpriteLoadingLimit(loadingLimits[it])
        }
        HorizontalDivider()
        Text(
            stringResource(R.string.graphics_renderer),
            style = MaterialTheme.typography.titleMedium,
        )
        Text("Zink · Android Surface", color = Copper)
        Hint(R.string.renderer_hint)
    }
    FactoryButton(R.string.back_home, true, true) { actions.action("home") }
}
