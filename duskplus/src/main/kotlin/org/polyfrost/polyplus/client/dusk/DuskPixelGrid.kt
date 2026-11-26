package org.polyfrost.polyplus.client.dusk

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.polyfrost.oneconfig.api.config.v1.Property
import org.polyfrost.oneconfig.api.config.v1.Visualizer
import org.polyfrost.oneconfig.api.ui.v1.ExperimentalOneConfigUiApi
import org.polyfrost.oneconfig.api.ui.v1.components.ControlSize
import org.polyfrost.oneconfig.api.ui.v1.components.Text
import org.polyfrost.oneconfig.api.ui.v1.components.TextButton
import org.polyfrost.oneconfig.api.ui.v1.theme.OneConfigTheme

/**
 * Dusk: the crosshair's pixel grid on the OneConfig page, painted like DuskClient's own editor.
 * A drag paints in the module's pixel colour; one that starts on a painted pixel erases (right click
 * belongs to the row's option menu). Clear and Undo sit below.
 * Presets and the colour are the page's own dropdown and colour rows. The grid is redrawn when
 * something else (a preset) replaces it.
 */
@OptIn(ExperimentalOneConfigUiApi::class)
internal class DuskPixelGrid(
    private val width: Int,
    private val height: Int,
    private val grid: () -> Array<IntArray>,
    private val replace: (Array<IntArray>) -> Unit,
    private val paintColor: () -> Int,
    /** called after each finished edit: the module marks itself custom and saves */
    private val edited: () -> Unit,
) : Visualizer {

    private fun snapshot(): Array<IntArray> = grid().map { it.clone() }.toTypedArray()

    private fun Array<IntArray>.same(other: Array<IntArray>) = java.util.Arrays.deepEquals(this, other)

    @Composable
    override fun visualize(prop: Property<*>) {
        // bumped on every change, so the canvas redraws
        var version by remember { mutableIntStateOf(0) }
        var hover by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        val undo = remember { ArrayDeque<Array<IntArray>>() }
        var undoCount by remember { mutableIntStateOf(0) }

        // a preset picked in the dropdown replaces the grid behind this control's back
        LaunchedEffect(Unit) {
            var last = java.util.Arrays.deepHashCode(grid())
            while (true) {
                withFrameNanos { }
                val now = java.util.Arrays.deepHashCode(grid())
                if (now != last) { last = now; version++ }
            }
        }

        fun pushUndo(g: Array<IntArray>) {
            undo.addLast(g)
            while (undo.size > UNDO_LIMIT) undo.removeFirst()
            undoCount = undo.size
        }

        val theme = OneConfigTheme.current
        val empty = theme.componentBackground
        val axis = theme.borderColor.copy(alpha = (theme.borderColor.alpha * 2f).coerceAtMost(1f))
        val hoverColor = theme.textColor
        val cell = CELL.dp

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Canvas(
                Modifier
                    .size(cell * width, cell * height)
                    .pointerInput(Unit) {
                        var button = -1
                        var before: Array<IntArray>? = null
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: continue
                                val cx = (change.position.x / size.width * width).toInt()
                                val cy = (change.position.y / size.height * height).toInt()
                                val inside = change.position.x >= 0 && change.position.y >= 0 && cx in 0 until width && cy in 0 until height
                                hover = if (inside && event.type != PointerEventType.Exit) cx to cy else null

                                fun paint() {
                                    if (!inside || button < 0) return
                                    val g = grid()
                                    val argb = if (button == 0) paintColor() else 0
                                    if (g[cy][cx] == argb) return
                                    val next = snapshot()
                                    next[cy][cx] = argb
                                    replace(next)
                                    version++
                                }

                                when (event.type) {
                                    // the row's right-click menu owns the secondary button, so a stroke
                                    // that starts on a painted pixel erases instead, like most pixel editors
                                    PointerEventType.Press -> if (button < 0 && inside && event.buttons.isPrimaryPressed && !event.buttons.isSecondaryPressed) {
                                        button = if ((grid()[cy][cx] ushr 24) != 0) 1 else 0
                                        if (button >= 0) {
                                            before = snapshot()
                                            paint()
                                            change.consume()
                                        }
                                    }
                                    PointerEventType.Move -> if (button >= 0) { paint(); change.consume() }
                                    PointerEventType.Release -> if (button >= 0 && !event.buttons.isPrimaryPressed) {
                                        before?.let { if (!it.same(grid())) { pushUndo(it); edited() } }
                                        button = -1
                                        before = null
                                        change.consume()
                                    }
                                    else -> {}
                                }
                            }
                        }
                    },
            ) {
                if (version < 0) return@Canvas // reading it subscribes the drawing to changes
                val g = grid()
                val step = size.width / width
                val inset = step / 4f
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val argb = g.getOrNull(y)?.getOrNull(x) ?: 0
                        val at = Offset(x * step, y * step)
                        if ((argb ushr 24) != 0) {
                            drawRect(Color(argb), at, Size(step, step))
                        } else {
                            // empty cells are small dots, the middle row and column a shade brighter
                            drawRect(
                                if (x == width / 2 || y == height / 2) axis else empty,
                                Offset(at.x + inset, at.y + inset), Size(step - 2 * inset, step - 2 * inset),
                            )
                        }
                    }
                }
                hover?.let { (x, y) ->
                    drawRect(hoverColor, Offset(x * step, y * step), Size(step, step), style = Stroke(1.dp.toPx()))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton("Clear", onClick = {
                    val before = snapshot()
                    if (before.any { row -> row.any { it != 0 } }) {
                        pushUndo(before)
                        replace(Array(height) { IntArray(width) })
                        version++
                        edited()
                    }
                }, size = ControlSize.Compact)
                TextButton("Undo", onClick = {
                    undo.removeLastOrNull()?.let { replace(it); version++; edited() }
                    undoCount = undo.size
                }, size = ControlSize.Compact, enabled = undoCount > 0)
                Text(
                    "Drag to paint; start on a painted pixel to erase",
                    color = theme.mutedText,
                    style = theme.typography.small,
                )
            }
        }
    }

    private companion object {
        const val CELL = 12
        const val UNDO_LIMIT = 64
    }
}
