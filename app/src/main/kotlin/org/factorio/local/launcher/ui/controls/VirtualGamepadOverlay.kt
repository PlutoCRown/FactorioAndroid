package org.factorio.local.launcher.ui.controls

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.hypot
import kotlin.math.min
import org.factorio.local.launcher.ControllerRelay
import org.factorio.local.launcher.R
import org.factorio.local.launcher.ui.theme.FactoryTheme

/** Bounded Compose islands keep game touches outside the controls available during multitouch. */
class VirtualGamepadOverlay(
    private val activity: ComponentActivity,
    private val container: FrameLayout,
    private val relay: ControllerRelay,
) {
    private var active by mutableStateOf(false)
    private var shown by mutableStateOf(true)
    private var expanded by mutableStateOf(false)
    private var help by mutableStateOf(false)
    private var wide by mutableStateOf(true)
    private var compact by mutableStateOf(false)
    private var scale by mutableFloatStateOf(1f)
    private var epoch by mutableIntStateOf(0)
    private val held = mutableStateMapOf<String, Boolean>()
    private val offsets = mutableStateMapOf<Int, Offset>()
    private val views = mutableListOf<ComposeView>()
    private val positioner = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> position() }
    private val left = island(Gravity.BOTTOM or Gravity.START) { Cluster(false) }
    private val right = island(Gravity.BOTTOM or Gravity.END) { Cluster(true) }
    private val toolbar = island(Gravity.TOP or Gravity.CENTER_HORIZONTAL) { Toolbar() }

    init {
        relay.setVirtualEnabled(true)
        container.isMotionEventSplittingEnabled = true
        container.addOnLayoutChangeListener(positioner)
        container.post { position() }
    }

    private fun island(gravity: Int, content: @Composable () -> Unit): ComposeView {
        return ComposeView(activity).also { view ->
            view.id = View.generateViewId()
            view.isFocusable = false
            view.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            view.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density * scale, density.fontScale)
                ) {
                    FactoryTheme { if (active) content() }
                }
            }
            container.addView(view, FrameLayout.LayoutParams(-2, -2, gravity))
            views.add(view)
        }
    }

    private fun position() {
        if (container.width <= 0 || container.height <= 0) return
        val density = activity.resources.displayMetrics.density
        val insets =
            ViewCompat.getRootWindowInsets(container)
                ?.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
        val width = (container.width - (insets?.left ?: 0) - (insets?.right ?: 0)) / density
        val height = (container.height - (insets?.top ?: 0) - (insets?.bottom ?: 0)) / density
        val nextWide = width >= 480
        val nextCompact = height < 420
        val naturalWidth = if (nextWide) 260f else 150f
        val naturalHeight =
            if (nextWide) (if (nextCompact) 220f else 250f) else (if (nextCompact) 310f else 360f)
        val nextScale =
            min(
                1f,
                min(
                    width / (naturalWidth * 2 + 24),
                    (height - 100).coerceAtLeast(60f) / naturalHeight,
                ),
            )
        if (
            wide != nextWide || compact != nextCompact || kotlin.math.abs(scale - nextScale) > 0.01f
        )
            release()
        wide = nextWide
        compact = nextCompact
        scale = nextScale
        fun place(view: View, start: Int, end: Int, bottom: Int, top: Int) {
            val params = view.layoutParams as FrameLayout.LayoutParams
            val margins = intArrayOf(start, end, bottom, top)
            if (
                params.leftMargin != start ||
                    params.rightMargin != end ||
                    params.bottomMargin != bottom ||
                    params.topMargin != top
            ) {
                params.setMargins(margins[0], margins[3], margins[1], margins[2])
                view.layoutParams = params
            }
        }
        place(
            left,
            (insets?.left ?: 0) + (8 * density).toInt(),
            0,
            (insets?.bottom ?: 0) + (64 * density).toInt(),
            0,
        )
        place(
            right,
            0,
            (insets?.right ?: 0) + (8 * density).toInt(),
            (insets?.bottom ?: 0) + (64 * density).toInt(),
            0,
        )
        place(toolbar, 0, 0, 0, (insets?.top ?: 0) + (8 * density).toInt())
    }

    fun setInputActive(value: Boolean) {
        if (active == value) return
        release()
        active = value
    }

    fun release() {
        epoch++
        held.clear()
        offsets.clear()
        relay.releaseVirtual()
    }

    fun close() {
        release()
        relay.setVirtualEnabled(false)
        container.removeOnLayoutChangeListener(positioner)
        views.forEach {
            it.disposeComposition()
            container.removeView(it)
        }
        views.clear()
    }

    private fun button(owner: String, code: Int, down: Boolean) {
        if (!active && down) return
        held[owner] = down
        relay.virtualButton(owner, code, down)
    }

    private fun trigger(owner: String, code: Int, down: Boolean) {
        if (!active && down) return
        held[owner] = down
        relay.virtualTrigger(owner, code, down)
    }

    @Composable
    private fun Cluster(rightSide: Boolean) {
        if (!shown) return
        val stick = if (rightSide) 1 else 0
        Column(
            Modifier.padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                PadButton(if (rightSide) "RB" else "LB", if (rightSide) 10 else 9)
                HoldControl(
                    if (rightSide) "RT" else "LT",
                    "trigger-$stick",
                    held["trigger-$stick"] == true,
                ) {
                    trigger("trigger-$stick", stick, it)
                }
                val latch = "latch-$stick"
                val label = if (rightSide) "RT" else "LT"
                TextButton(
                    onClick = { trigger(latch, stick, held[latch] != true) },
                    modifier = Modifier.size(44.dp, 40.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(if (held[latch] == true) "$label ●" else "$label ◇", fontSize = 10.sp)
                }
            }
            if (wide) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (rightSide) FaceButtons() else Stick(stick)
                    if (rightSide) Stick(stick) else Dpad()
                }
            } else {
                if (rightSide) FaceButtons() else Dpad()
                Stick(stick)
            }
            PadButton(if (rightSide) "R3" else "L3", if (rightSide) 8 else 7)
        }
    }

    @Composable
    private fun PadButton(
        label: String,
        code: Int,
        modifier: Modifier = Modifier,
        color: Color = Color(0xffffe6c0),
    ) {
        val owner = "button-$code"
        HoldControl(label, owner, held[owner] == true, modifier, color) { button(owner, code, it) }
    }

    @Composable
    private fun HoldControl(
        label: String,
        owner: String,
        down: Boolean,
        modifier: Modifier = Modifier,
        color: Color = Color(0xffffe6c0),
        change: (Boolean) -> Unit,
    ) {
        Box(
            modifier
                .size(if (compact) 36.dp else 42.dp)
                .background(
                    if (down) Color(0xff93652e).copy(alpha = 0.8f)
                    else Color(0xff242324).copy(alpha = 0.55f),
                    CircleShape,
                )
                .border(1.dp, color.copy(alpha = if (down) 0.9f else 0.45f), CircleShape)
                .semantics {
                    role = Role.Button
                    contentDescription = label
                    onClick {
                        change(true)
                        change(false)
                        true
                    }
                }
                .pointerInput(owner, epoch) {
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        first.consume()
                        change(true)
                        try {
                            do {
                                val event = awaitPointerEvent()
                                val pointer = event.changes.find { it.id == first.id }
                                pointer?.consume()
                            } while (pointer != null && pointer.pressed)
                        } finally {
                            change(false)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = color, fontSize = 12.sp)
        }
    }

    @Composable
    private fun Stick(index: Int) {
        val label = stringResource(if (index == 0) R.string.virtual_move else R.string.virtual_look)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(
                Modifier.size(if (compact) 88.dp else 112.dp)
                    .semantics { contentDescription = label }
                    .pointerInput(index, epoch) {
                        fun move(position: Offset) {
                            val travel = min(size.width, size.height) * 0.32f
                            val raw = position - Offset(size.width / 2f, size.height / 2f)
                            val length = hypot(raw.x, raw.y)
                            val offset = if (length > travel) raw * (travel / length) else raw
                            offsets[index] = offset / travel
                            relay.virtualStick(index, offset.x / travel, offset.y / travel)
                        }
                        awaitEachGesture {
                            val first = awaitFirstDown(requireUnconsumed = false)
                            first.consume()
                            move(first.position)
                            try {
                                do {
                                    val event = awaitPointerEvent()
                                    val pointer = event.changes.find { it.id == first.id }
                                    if (pointer != null && pointer.pressed) {
                                        pointer.consume()
                                        move(pointer.position)
                                    }
                                } while (pointer != null && pointer.pressed)
                            } finally {
                                offsets[index] = Offset.Zero
                                relay.virtualStick(index, 0f, 0f)
                            }
                        }
                    }
            ) {
                val radius = size.minDimension / 2
                drawCircle(Color(0xff242324).copy(alpha = 0.4f), radius)
                drawCircle(
                    Color(0xffffe6c0).copy(alpha = 0.4f),
                    radius - 1.dp.toPx(),
                    style = Stroke(1.dp.toPx()),
                )
                drawCircle(
                    Color(0xffe39827).copy(alpha = 0.65f),
                    radius * 0.3f,
                    center + (offsets[index] ?: Offset.Zero) * size.minDimension * 0.32f,
                )
            }
            Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
        }
    }

    @Composable
    private fun Dpad() {
        Box(Modifier.size(if (compact) 88.dp else 108.dp)) {
            PadButton("↑", 11, Modifier.align(Alignment.TopCenter))
            PadButton("↓", 12, Modifier.align(Alignment.BottomCenter))
            PadButton("←", 13, Modifier.align(Alignment.CenterStart))
            PadButton("→", 14, Modifier.align(Alignment.CenterEnd))
        }
    }

    @Composable
    private fun FaceButtons() {
        Box(Modifier.size(if (compact) 88.dp else 108.dp)) {
            PadButton("Y", 3, Modifier.align(Alignment.TopCenter), Color(0xffffd76b))
            PadButton("A", 0, Modifier.align(Alignment.BottomCenter), Color(0xff8ee797))
            PadButton("X", 2, Modifier.align(Alignment.CenterStart), Color(0xff88c8ff))
            PadButton("B", 1, Modifier.align(Alignment.CenterEnd), Color(0xffff9c8f))
        }
    }

    @Composable
    private fun Toolbar() {
        Column(
            Modifier.background(Color(0xff242324).copy(alpha = 0.45f), MaterialTheme.shapes.small)
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PadButton("Back", 4)
                PadButton("Start", 6)
                TextButton(
                    onClick = {
                        release()
                        expanded = !expanded
                    }
                ) {
                    Text("＋", fontSize = 16.sp)
                }
                TextButton(
                    onClick = {
                        release()
                        help = true
                    }
                ) {
                    Text("?", fontSize = 16.sp)
                }
                TextButton(
                    onClick = {
                        release()
                        shown = !shown
                    }
                ) {
                    Text(
                        stringResource(if (shown) R.string.virtual_hide else R.string.virtual_show),
                        fontSize = 11.sp,
                    )
                }
            }
            if (expanded)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PadButton("Guide", 5)
                    PadButton("M1", 15)
                    PadButton("P1", 16)
                    PadButton("P2", 17)
                    PadButton("P3", 18)
                    PadButton("P4", 19)
                    PadButton("TP", 20)
                }
        }
        if (help)
            AlertDialog(
                onDismissRequest = { help = false },
                title = { Text(stringResource(R.string.virtual_gamepad)) },
                text = {
                    Text(
                        stringResource(R.string.virtual_gamepad_help),
                        Modifier.verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { help = false }) { Text(stringResource(R.string.close)) }
                },
            )
    }
}
