package com.maxrave.simpmusic.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maxrave.simpmusic.isTelevision
import kotlinx.coroutines.launch

/**
 * The Android TV focus colour. The same light blue in both themes, so a move to the next
 * control stays obvious on a white page and on a black one.
 */
val TvFocusBlue = Color(0xFF7AD7FF)

private val TvFocusFill = TvFocusBlue.copy(alpha = 0.34f)
private val TvFocusStroke = 3.dp

@OptIn(ExperimentalMaterial3Api::class)
internal val TvRippleConfiguration =
    RippleConfiguration(
        color = TvFocusBlue,
        rippleAlpha =
            RippleAlpha(
                draggedAlpha = 0.24f,
                focusedAlpha = 0.55f,
                hoveredAlpha = 0.20f,
                pressedAlpha = 0.40f,
            ),
    )

/**
 * Light-blue focus ring for a control that draws its own indication (Material buttons, chips,
 * the navigation rail). No-op off Android TV. The ring is fully opaque the moment focus arrives
 * and leaves with it, so it never settles into the faint system highlight.
 */
@Composable
fun Modifier.tvFocusRing(corner: Dp = 12.dp): Modifier = if (isTelevision()) this.then(TvFocusRingElement(corner)) else this

/** [focusable] plus [tvFocusRing], for rows that take focus themselves. */
@Composable
fun Modifier.tvFocusable(corner: Dp = 12.dp): Modifier = this.tvFocusRing(corner).focusable()

/** Used as [androidx.compose.foundation.LocalIndication] on TV, so a plain clickable gets the same ring. */
internal object TvFocusIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = TvFocusIndicationNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = TvFocusBlue.hashCode()
}

private class TvFocusIndicationNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode {
    private var focused = false

    override fun onAttach() {
        coroutineScope.launch {
            val focuses = mutableListOf<FocusInteraction.Focus>()
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> focuses.add(interaction)
                    is FocusInteraction.Unfocus -> focuses.remove(interaction.focus)
                }
                val now = focuses.isNotEmpty()
                if (now != focused) {
                    focused = now
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawTvFocus(focused, 12.dp)
    }
}

private class TvFocusRingElement(
    private val corner: Dp,
) : ModifierNodeElement<TvFocusRingNode>() {
    override fun create(): TvFocusRingNode = TvFocusRingNode(corner)

    override fun update(node: TvFocusRingNode) {
        node.corner = corner
    }

    override fun equals(other: Any?): Boolean = other is TvFocusRingElement && other.corner == corner

    override fun hashCode(): Int = corner.hashCode()
}

private class TvFocusRingNode(
    var corner: Dp,
) : Modifier.Node(), DrawModifierNode, FocusEventModifierNode {
    private var focused = false
    private val focusScale = Animatable(1f)

    override fun onFocusEvent(focusState: FocusState) {
        // hasFocus covers a Material button whose clickable child is the real focus target.
        val now = focusState.hasFocus
        if (now != focused) {
            focused = now
            coroutineScope.launch {
                focusScale.animateTo(if (now) 1.06f else 1f, tween(160)) {
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val zoom = focusScale.value
        if (zoom == 1f) {
            drawTvFocus(focused, corner)
        } else {
            // YouTube grows the focused shelf card over its neighbours. The layout slot stays put.
            val card = this
            scale(zoom, zoom) {
                with(card) { drawTvFocus(focused, corner) }
            }
        }
    }
}

private fun ContentDrawScope.drawTvFocus(
    focused: Boolean,
    corner: Dp,
) {
    if (focused) {
        drawRoundRect(color = TvFocusFill, cornerRadius = CornerRadius(corner.toPx()))
    }
    drawContent()
    if (focused) {
        val stroke = TvFocusStroke.toPx()
        val inset = stroke / 2f
        drawRoundRect(
            color = TvFocusBlue,
            topLeft = Offset(inset, inset),
            size = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(corner.toPx()),
            style = Stroke(width = stroke),
        )
    }
}
