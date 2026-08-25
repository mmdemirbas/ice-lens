package ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import model.GraphDirection

/**
 * A keystroke this app navigates with, once the modifiers have been ruled out.
 *
 * Three surfaces read the arrows — the canvas, the structure tree and the workspace — and each
 * means something different by them. What they agree on is which key presses count at all, and
 * that agreement lives here rather than in three copies that drift.
 */
internal enum class ListKey { UP, DOWN, LEFT, RIGHT, ACTIVATE }

/**
 * The navigation key an event carries, or null when it is not one.
 *
 * **Bare keys only.** A modifier turns the same key into somebody else's shortcut — Cmd+Left is
 * "back" on macOS, Alt+Arrow moves by word in every text field — and a pane that swallows those
 * makes the rest of the window feel broken.
 */
internal fun navKey(event: KeyEvent): ListKey? {
    if (event.type != KeyEventType.KeyDown) return null
    if (event.isMetaPressed || event.isCtrlPressed || event.isAltPressed || event.isShiftPressed) return null
    return when (event.key) {
        Key.DirectionLeft -> ListKey.LEFT
        Key.DirectionRight -> ListKey.RIGHT
        Key.DirectionUp -> ListKey.UP
        Key.DirectionDown -> ListKey.DOWN
        Key.Enter, Key.NumPadEnter, Key.Spacebar -> ListKey.ACTIVATE
        else -> null
    }
}

/**
 * The same keystroke as a direction on the canvas, or null for [ListKey.ACTIVATE].
 *
 * The canvas has nothing to activate: moving the selection there is already the whole action, and
 * a node is opened by what the inspector does with the selection rather than by a second key.
 */
internal fun ListKey.asGraphDirection(): GraphDirection? = when (this) {
    ListKey.LEFT -> GraphDirection.LEFT
    ListKey.RIGHT -> GraphDirection.RIGHT
    ListKey.UP -> GraphDirection.UP
    ListKey.DOWN -> GraphDirection.DOWN
    ListKey.ACTIVATE -> null
}
