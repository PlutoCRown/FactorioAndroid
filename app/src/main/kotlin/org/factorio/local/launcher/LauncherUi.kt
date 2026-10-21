package org.factorio.local.launcher

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.util.Locale

private val Coal = Color(0xff242324)
private val Panel = Color(0xff313031)
private val Ink = Color(0xffffe6c0)
private val Muted = Color(0xffb6b1ad)
private val Copper = Color(0xffe39827)
private val FactoryColors = darkColorScheme(
    primary = Copper, onPrimary = Coal, secondary = Copper,
    background = Coal, onBackground = Ink, surface = Panel, onSurface = Ink,
    onSurfaceVariant = Muted, outline = Color(0xff77716c),
)

private data class LauncherState(
    val page: Int = 0,
    val busy: Boolean = false,
    val message: String = "",
    val version: String? = null,
    val spaceAge: Boolean = false,
    val touchMouse: Boolean = true,
    val scale: Int = 100,
    val preset: String = "compat",
    val spriteLoadingLimit: Int = 1,
    val gpu: String? = null,
    val api: String? = null,
    val graphicsPassed: Boolean? = null,
    val progress: Float = -1f,
)

/** Compose owns presentation; Java continues to own imports and the game session. */
class LauncherUi(private val host: MainActivity) {
    private var state by mutableStateOf(LauncherState())
    private val memory = ActivityManager.MemoryInfo().also {
        (host.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
    }.totalMem

    fun mount() {
        host.setContent {
            MaterialTheme(colorScheme = FactoryColors) {
                // The factory artwork always stays on the right, including RTL locales.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Launcher(state)
                }
            }
        }
    }

    fun show(page: Int, busy: Boolean, message: String, game: JSONObject?, device: JSONObject?, progress: Float) {
        val preferences = host.getPreferences(0)
        val gl = device?.optJSONObject("graphics")
        state = LauncherState(
            page, busy, message, game?.optString("version"), game?.optBoolean("spaceAge") ?: false,
            preferences.getBoolean("touchMouseSimulation", true),
            preferences.getInt("renderScalePercent", 100),
            preferences.getString("graphicsPreset", "compat") ?: "compat",
            preferences.getInt("spriteLoadingLimit", 1),
            gl?.optString("glRenderer"),
            gl?.optString("glVersion")?.let { Regex("OpenGL ES\\s+\\d+\\.\\d+").find(it)?.value },
            gl?.optBoolean("clearReadbackPassed"),
            progress,
        )
    }

    private fun action(name: String) = host.performUiAction(name)

    @Composable
    private fun Launcher(s: LauncherState) {
        Surface(color = Coal) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val wide = maxWidth >= 840.dp
                Row(Modifier.fillMaxSize()) {
                    Sidebar(s)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        // Separate scroll positions survive tab changes; artwork has no page key.
                        val homeScroll = rememberScrollState()
                        val settingsScroll = rememberScrollState()
                        val deviceScroll = rememberScrollState()
                        val scroll = when (s.page) { 1 -> settingsScroll; 2 -> deviceScroll; else -> homeScroll }
                        Column(
                            Modifier.fillMaxSize().verticalScroll(scroll).padding(if (wide) 32.dp else 20.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            when (s.page) {
                                1 -> Settings(s)
                                2 -> Device(s)
                                else -> Home(s)
                            }
                            if (s.page != 0) Status(s)
                        }
                    }
                    if (wide) Artwork(Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }

    @Composable
    private fun Sidebar(s: LauncherState) {
        Surface(color = Color(0xff201815)) {
            Column(
                Modifier.width(76.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Image(painterResource(R.drawable.factorio_icon), null, Modifier.size(42.dp))
                Spacer(Modifier.height(12.dp))
                val pages = listOf(
                    Triple(R.string.home, R.drawable.ic_nav_home, "home"),
                    Triple(R.string.launch_settings, R.drawable.ic_nav_settings, "settings"),
                    Triple(R.string.device_info, R.drawable.ic_nav_device, "device"),
                )
                pages.forEachIndexed { index, (label, icon, event) ->
                    NavigationRailItem(
                        selected = s.page == index,
                        onClick = { action(event) },
                        enabled = index != 1 || s.version != null,
                        icon = { Icon(painterResource(icon), stringResource(label), Modifier.size(24.dp)) },
                        colors = NavigationRailItemDefaults.colors(
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
    private fun Home(s: LauncherState) {
        Eyebrow()
        Text(stringResource(if (s.version == null) R.string.home_welcome else R.string.home_continue),
            style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Hint(if (s.version == null) R.string.home_import_hint else R.string.home_ready)
        Status(s)
        FactoryPanel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Image(painterResource(R.drawable.factorio_icon), null, Modifier.size(44.dp))
                Column {
                    Text(if (s.version == null) stringResource(R.string.add_game) else "Factorio", style = MaterialTheme.typography.titleLarge)
                    Text(s.version?.let { "$it · ${if (s.spaceAge) "Space Age" else stringResource(R.string.base_game)}" }
                        ?: stringResource(R.string.game_package_hint), color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            FactoryButton(if (s.version == null) R.string.choose_game else R.string.launch_game, !s.busy, true) { action("play") }
            if (s.version == null) {
                TextButton(onClick = { action("help") }, enabled = !s.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.download_help_link))
                }
            } else {
                HorizontalDivider(color = Color(0xff201815))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FactoryButton(R.string.open_saves, !s.busy, modifier = Modifier.weight(1f)) { action("saves") }
                    FactoryButton(R.string.import_save, !s.busy, modifier = Modifier.weight(1f)) { action("save") }
                }
                FactoryButton(R.string.open_mods, !s.busy) { action("mods") }
                TextButton(onClick = { action("choose") }, enabled = !s.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.replace_game))
                }
            }
        }
        Hint(R.string.own_game_footer)
    }

    @Composable
    private fun Settings(s: LauncherState) {
        Eyebrow()
        Text(stringResource(R.string.launch_settings), style = MaterialTheme.typography.headlineLarge)
        Hint(R.string.settings_hint)
        FactoryPanel {
            Row(
                Modifier.fillMaxWidth().toggleable(s.touchMouse, enabled = !s.busy, role = Role.Switch,
                    onValueChange = host::setTouchMouseEnabled).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.touch_mouse), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = s.touchMouse, onCheckedChange = null, enabled = !s.busy)
            }
            Hint(R.string.touch_mouse_hint)
            HorizontalDivider()
            Field(R.string.render_resolution, R.string.render_resolution_hint)
            val scales = listOf(100, 75, 50)
            Choice(listOf(R.string.scale_native, R.string.scale_balanced, R.string.scale_smooth),
                scales.indexOf(s.scale).coerceAtLeast(0), R.string.render_resolution, !s.busy) { host.setRenderScale(scales[it]) }
            HorizontalDivider()
            Field(R.string.graphics_preset, R.string.graphics_preset_hint)
            val presets = listOf("compat", "balanced", "quality", "game")
            Choice(listOf(R.string.preset_compat, R.string.preset_balanced, R.string.preset_quality, R.string.preset_game),
                presets.indexOf(s.preset).coerceAtLeast(0), R.string.graphics_preset, !s.busy) { host.setGraphicsPreset(presets[it]) }
            HorizontalDivider()
            Field(R.string.sprite_loading_limit, R.string.sprite_loading_limit_hint)
            val loadingLimits = listOf(1, 2, 4, 8, 16, 32, 0)
            Choice(listOf(R.string.loading_limit_1, R.string.loading_limit_2, R.string.loading_limit_4,
                R.string.loading_limit_8, R.string.loading_limit_16, R.string.loading_limit_32, R.string.loading_limit_game),
                loadingLimits.indexOf(s.spriteLoadingLimit).coerceAtLeast(0), R.string.sprite_loading_limit, !s.busy) {
                host.setSpriteLoadingLimit(loadingLimits[it])
            }
            HorizontalDivider()
            Text(stringResource(R.string.graphics_renderer), style = MaterialTheme.typography.titleMedium)
            Text("Zink · Android Surface", color = Copper)
            Hint(R.string.renderer_hint)
        }
        FactoryButton(R.string.back_home, true, true) { action("home") }
    }

    @Composable
    private fun Device(s: LauncherState) {
        Eyebrow()
        Text(stringResource(R.string.device_info), style = MaterialTheme.typography.headlineLarge)
        Hint(R.string.device_hint)
        FactoryPanel {
            Datum(R.string.device_label, "${Build.MANUFACTURER} ${Build.MODEL}")
            Datum(R.string.system_label, "Android ${Build.VERSION.RELEASE}")
            Datum(R.string.memory_label, String.format(Locale.ROOT, "%.1f GB", memory / 1_000_000_000.0))
            Datum(R.string.gpu_label, s.gpu ?: stringResource(R.string.waiting_for_check))
            Datum(R.string.graphics_api, s.api ?: stringResource(if (s.gpu == null) R.string.waiting_for_check else R.string.unavailable))
        }
        FactoryButton(if (s.graphicsPassed == null) R.string.check_device else R.string.recheck_device, !s.busy, true) { action("probe") }
        Hint(when (s.graphicsPassed) { null -> R.string.device_check_local; true -> R.string.device_check_pass; false -> R.string.device_check_fail })
    }

    @Composable
    private fun Artwork(modifier: Modifier) {
        Box(modifier) {
            Image(painterResource(R.drawable.factory_background), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Coal, Color.Transparent))))
            Column(Modifier.align(Alignment.BottomStart).padding(36.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("THE FACTORY MUST GROW", fontSize = 11.sp, letterSpacing = 2.sp, color = Ink)
                Text(stringResource(R.string.artwork_caption), style = MaterialTheme.typography.headlineMedium, color = Ink)
            }
        }
    }

    @Composable
    private fun Status(s: LauncherState) {
        if (s.busy || s.message.isNotEmpty()) {
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (s.busy) {
                    if (s.progress >= 0f) LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (s.message.isNotEmpty()) Text(s.message, color = Copper, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Eyebrow() { Text("F A C T O R I O", color = Copper, style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp) }

@Composable
private fun Hint(id: Int) { Text(stringResource(id), color = Muted, style = MaterialTheme.typography.bodySmall) }

@Composable
private fun Field(title: Int, hint: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
    Hint(hint)
}

@Composable
private fun Datum(title: Int, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title), color = Muted, style = MaterialTheme.typography.labelMedium)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
    }
}

private fun Modifier.factoryBorder() = drawWithContent {
    drawContent()
    val edge = 2.dp.toPx()
    drawLine(Color(0xff797674), Offset.Zero, Offset(size.width, 0f), edge)
    drawLine(Color(0xff797674), Offset.Zero, Offset(0f, size.height), edge)
    drawLine(Color(0xff100e0c), Offset(0f, size.height), Offset(size.width, size.height), edge)
    drawLine(Color(0xff100e0c), Offset(size.width, 0f), Offset(size.width, size.height), edge)
}

@Composable
private fun FactoryPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Panel, modifier = Modifier.fillMaxWidth().factoryBorder()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun FactoryButton(label: Int, enabled: Boolean, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FactoryWebButton(enabled, primary, modifier.fillMaxWidth(), onClick) {
        Text(stringResource(label))
    }
}

@Composable
private fun Choice(labels: List<Int>, selected: Int, field: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxWidth
        FactoryWebButton(enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), onClick = { expanded = true }) {
            Text(stringResource(field) + " · " + stringResource(labels[selected]), Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(24.dp))
        }
        DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.width(width)) {
            labels.forEachIndexed { index, label ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { expanded = false; onSelect(index) })
            }
        }
    }
}
