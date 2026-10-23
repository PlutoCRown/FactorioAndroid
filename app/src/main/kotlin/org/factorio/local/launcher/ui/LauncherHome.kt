package org.factorio.local.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.factorio.local.launcher.R
import org.factorio.local.launcher.ui.components.*
import org.factorio.local.launcher.ui.theme.Muted

@Composable
internal fun LauncherHome(s: LauncherState, actions: LauncherActions) {
    Eyebrow()
    Text(
        stringResource(if (s.version == null) R.string.home_welcome else R.string.home_continue),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Hint(if (s.version == null) R.string.home_import_hint else R.string.home_ready)
    LauncherStatus(s.busy, s.message, s.progress)
    FactoryPanel {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(painterResource(R.drawable.factorio_icon), null, Modifier.size(36.dp))
            Column {
                Text(
                    if (s.version == null) stringResource(R.string.add_game) else "Factorio",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    s.version?.let {
                        "$it · ${if (s.spaceAge) "Space Age" else stringResource(R.string.base_game)}"
                    } ?: stringResource(R.string.game_package_hint),
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        FactoryButton(
            if (s.version == null) R.string.choose_game else R.string.launch_game,
            !s.busy,
            true,
        ) {
            actions.action("play")
        }
        if (s.version == null) {
            TextButton(
                onClick = { actions.action("help") },
                enabled = !s.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.download_help_link))
            }
        } else {
            HorizontalDivider(color = Color(0xff201815))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FactoryButton(R.string.open_saves, !s.busy, modifier = Modifier.weight(1f)) {
                    actions.action("saves")
                }
                FactoryButton(R.string.import_save, !s.busy, modifier = Modifier.weight(1f)) {
                    actions.action("save")
                }
            }
            FactoryButton(R.string.open_mods, !s.busy) { actions.action("mods") }
            TextButton(
                onClick = { actions.action("choose") },
                enabled = !s.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.replace_game))
            }
        }
    }
    Hint(R.string.own_game_footer)
}
