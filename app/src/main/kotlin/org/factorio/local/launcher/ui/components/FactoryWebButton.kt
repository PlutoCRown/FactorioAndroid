package org.factorio.local.launcher.ui.components

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/**
 * Factorio normal.css .button / .button-green, adapted to Android touch targets. CSS shadow
 * offsets, blur and spread below are logical pixels (dp on Android). Software Canvas computes the
 * small cached bevel bitmap so inset blur also works on older hardware Canvas implementations.
 * Compose only draws the cached image.
 */
private data class WebShadow(
    val x: Float,
    val y: Float,
    val blur: Float,
    val spread: Float,
    val color: Long,
)

private data class WebButtonPaint(
    val background: Long,
    val text: Long,
    val shadows: List<WebShadow>,
    val glow: Long? = null,
    val outerColor: Long = 0xff000000,
    val outerBlur: Float = 4f,
)

private fun shadow(x: Number, y: Number, blur: Number, spread: Number, color: Long) =
    WebShadow(x.toFloat(), y.toFloat(), blur.toFloat(), spread.toFloat(), color)

private const val Black = 0xff000000

private fun buttonPaint(
    green: Boolean,
    enabled: Boolean,
    pressed: Boolean,
    highlighted: Boolean,
): WebButtonPaint {
    val sides = listOf(shadow(8, 0, 4, -8, Black), shadow(-8, 0, 4, -8, Black))
    if (!enabled) {
        val text = if (green) 0xff376d3b else 0xff818181
        return WebButtonPaint(
            if (green) 0xff002b02 else 0xff3d3d3d,
            text,
            sides +
                listOf(
                    shadow(0, 8, 4, -8, Black),
                    shadow(0, -6, 4, -8, text),
                    shadow(0, -8, 4, -8, Black),
                ),
        )
    }
    if (pressed) {
        val edge = if (green) 0xff3f5024 else 0xff563a10
        return WebButtonPaint(
            if (green) 0xffcfdf93 else 0xfff1be64,
            Black,
            listOf(
                shadow(0, 10, 2, -8, Black),
                shadow(0, 9, 2, -8, Black),
                shadow(8, 0, 4, -8, edge),
                shadow(8, 0, 4, -8, edge),
                shadow(-8, 0, 4, -8, edge),
                shadow(-8, 0, 4, -8, edge),
                shadow(0, 9, 2, -8, edge),
                shadow(0, -9, 2, -8, edge),
                shadow(0, -8.5, 0, -8, edge),
            ),
        )
    }
    if (highlighted) {
        val glow = if (green) 0xff34be3c else 0xfff9b44b
        return WebButtonPaint(
            if (green) 0xff92e897 else 0xffe39827,
            Black,
            sides +
                listOf(
                    shadow(0, 9, 2, -8, if (green) 0xffcdf1cf else 0xffffffff),
                    shadow(0, 8, 4, -8, Black),
                    shadow(0, -8, 4, -8, Black),
                    shadow(0, -9, 2, -8, 0xff432400),
                    shadow(0, 0, 4, 2, glow),
                ),
            glow,
        )
    }
    return WebButtonPaint(
        if (green) 0xff5eb663 else 0xff8e8e8e,
        Black,
        sides +
            listOf(
                shadow(0, 10, 2, -8, if (green) 0xff95df99 else 0xffe3e3e3),
                shadow(0, 10, 2, -8, if (green) 0xff163218 else 0xff282828),
                shadow(0, -9, 2, -8, Black),
            ),
    )
}

/** Shared rasterization for Compose controls and the native game's overlay button. */
private fun renderWebSurface(width: Int, height: Int, scale: Float, style: WebButtonPaint): Bitmap {
    val padding = ceil(8 * scale).toInt()
    val bitmap =
        Bitmap.createBitmap(width + 2 * padding, height + 2 * padding, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.translate(padding.toFloat(), padding.toFloat())
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun configure(color: Long, blur: Float = 0f) {
        paint.color = color.toInt()
        paint.maskFilter =
            if (blur > 0f) BlurMaskFilter(blur * scale, BlurMaskFilter.Blur.NORMAL) else null
    }
    // CSS shadow lists are frontmost first; paint them in reverse order.
    style.glow?.let {
        configure(it, 2f)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
    configure(style.outerColor, style.outerBlur)
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    configure(style.background)
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    canvas.save()
    canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
    for (shadow in style.shadows.asReversed()) {
        // An inset shadow is a blurred inverse rectangle: positive spread shrinks its hole.
        val path =
            Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(
                    -padding.toFloat() * 4,
                    -padding.toFloat() * 4,
                    width + padding.toFloat() * 4,
                    height + padding.toFloat() * 4,
                    Path.Direction.CW,
                )
                addRect(
                    (shadow.x + shadow.spread) * scale,
                    (shadow.y + shadow.spread) * scale,
                    width + (shadow.x - shadow.spread) * scale,
                    height + (shadow.y - shadow.spread) * scale,
                    Path.Direction.CW,
                )
            }
        configure(shadow.color, shadow.blur)
        canvas.drawPath(path, paint)
    }
    canvas.restore()
    return bitmap
}

private fun Modifier.webButtonSurface(style: WebButtonPaint) = drawWithCache {
    val padding = ceil(8 * density).toFloat()
    val image =
        renderWebSurface(
                ceil(size.width).toInt().coerceAtLeast(1),
                ceil(size.height).toInt().coerceAtLeast(1),
                density,
                style,
            )
            .asImageBitmap()
    onDrawBehind { drawImage(image, topLeft = Offset(-padding, -padding)) }
}

/**
 * normal.css .panel.p0: gray fill, top highlight, dark side/bottom inset and brown outer shadow.
 */
internal fun Modifier.factoryWebPanelSurface() =
    webButtonSurface(
        WebButtonPaint(
            0xff313031,
            Black,
            listOf(
                shadow(3, 0, 2, -2, 0xff201815),
                shadow(0, 3, 2, -2, 0xff8f8c8b),
                shadow(-3, 0, 2, -2, 0xff201815),
                shadow(0, -3, 3, -3, Black),
            ),
            outerColor = 0xff201815,
            outerBlur = 3f,
        )
    )

/**
 * Uses the same .button palette/shadows without adding a Compose lifecycle to the game Activity.
 */
class FactoryButtonDrawable(private val density: Float) : Drawable() {
    private var style = buttonPaint(false, true, false, false)
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun isStateful() = true

    override fun onBoundsChange(bounds: Rect) {
        bitmap = null
    }

    override fun onStateChange(state: IntArray): Boolean {
        val next =
            buttonPaint(
                false,
                android.R.attr.state_enabled in state,
                android.R.attr.state_pressed in state,
                android.R.attr.state_hovered in state || android.R.attr.state_focused in state,
            )
        if (next == style) return false
        style = next
        bitmap = null
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        // Keep the original wide touch target; its visible square is 40dp inside the 48dp height.
        val side =
            (minOf(bounds.width(), bounds.height()) - ceil(8 * density).toInt()).coerceAtLeast(1)
        val image = bitmap ?: renderWebSurface(side, side, density, style).also { bitmap = it }
        val padding = ceil(8 * density).toFloat()
        canvas.drawBitmap(
            image,
            bounds.left + (bounds.width() - side) / 2f - padding,
            bounds.top + (bounds.height() - side) / 2f - padding,
            paint,
        )
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

@Composable
internal fun FactoryWebButton(
    enabled: Boolean,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val active = enabled && pressed
    val style = buttonPaint(primary, enabled, active, hovered || focused)
    CompositionLocalProvider(
        LocalContentColor provides Color(style.text),
        LocalTextStyle provides
            MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    ) {
        Row(
            modifier
                .heightIn(min = 44.dp)
                .webButtonSurface(style)
                .clickable(
                    interactionSource = interactions,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    top = if (active) 8.dp else 6.dp,
                    bottom = if (active) 4.dp else 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
            content = content,
        )
    }
}
