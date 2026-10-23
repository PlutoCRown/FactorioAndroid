package org.factorio.local.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.factorio.local.launcher.R
import org.factorio.local.launcher.ui.components.LauncherStatus
import org.factorio.local.launcher.ui.theme.*

@Composable
internal fun LauncherScreen(s: LauncherState, actions: LauncherActions, memory: Long) {
    Surface(color = Coal) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val wide = maxWidth >= 840.dp
            Row(Modifier.fillMaxSize()) {
                Sidebar(s, actions)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    // Separate scroll positions survive tab changes; artwork has no page key.
                    val homeScroll = rememberScrollState()
                    val settingsScroll = rememberScrollState()
                    val deviceScroll = rememberScrollState()
                    val scroll =
                        when (s.page) {
                            1 -> settingsScroll
                            2 -> deviceScroll
                            else -> homeScroll
                        }
                    Column(
                        Modifier.widthIn(max = 600.dp)
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .verticalScroll(scroll)
                            .padding(if (wide) 24.dp else 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        when (s.page) {
                            1 -> LauncherSettings(s, actions)
                            2 -> LauncherDevice(s, actions, memory)
                            else -> LauncherHome(s, actions)
                        }
                        if (s.page != 0) LauncherStatus(s.busy, s.message, s.progress)
                    }
                }
                if (wide) Artwork(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun Sidebar(s: LauncherState, actions: LauncherActions) {
    Surface(color = Color(0xff201815)) {
        Column(
            Modifier.width(76.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Image(painterResource(R.drawable.factorio_icon), null, Modifier.size(42.dp))
            Spacer(Modifier.height(12.dp))
            val pages =
                listOf(
                    Triple(R.string.home, R.drawable.ic_nav_home, "home"),
                    Triple(R.string.launch_settings, R.drawable.ic_nav_settings, "settings"),
                    Triple(R.string.device_info, R.drawable.ic_nav_device, "device"),
                )
            pages.forEachIndexed { index, (label, icon, event) ->
                NavigationRailItem(
                    selected = s.page == index,
                    onClick = { actions.action(event) },
                    enabled = index != 1 || s.version != null,
                    icon = {
                        Icon(painterResource(icon), stringResource(label), Modifier.size(24.dp))
                    },
                    colors =
                        NavigationRailItemDefaults.colors(
                            indicatorColor = Copper,
                            selectedIconColor = Coal,
                            unselectedIconColor = Muted,
                        ),
                )
            }
        }
    }
}

@Composable
private fun Artwork(modifier: Modifier) {
    Box(modifier) {
        Image(
            painterResource(R.drawable.factory_background),
            null,
            Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier.fillMaxSize()
                .background(Brush.horizontalGradient(listOf(Coal, Color.Transparent)))
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("THE FACTORY MUST GROW", fontSize = 11.sp, letterSpacing = 2.sp, color = Ink)
            Text(
                stringResource(R.string.artwork_caption),
                style = MaterialTheme.typography.titleLarge,
                color = Ink,
            )
        }
    }
}
