package ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tool-window chrome takes the keyboard.
 *
 * The bar's buttons are `clickable` boxes and a pane's close is an `IconButton`. Neither has a
 * keymap of its own; whether Tab reaches them and Enter fires them is Compose's contract, not this
 * repository's. It is asserted anyway, because "reachable by Tab" is the bar the four navigable
 * surfaces are held to, and a contract nobody has exercised is a belief. The keys go through
 * `ImageComposeScene.sendKeyEvent`, which delivers to whatever holds focus — so the order the
 * callbacks fire in is the Tab order, and a control Tab skips is a callback that never fires.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class KeyboardReachTest {

    private fun drive(keys: List<Key>, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = 400, height = 400, density = Density(1f), content = content)
        try {
            scene.render()
            keys.forEach { key ->
                scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
                scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
                scene.render()
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `tab walks the tool-window bar, and enter or space opens the window under focus`() {
        val opened = mutableListOf<String>()
        drive(listOf(Key.Tab, Key.Enter, Key.Tab, Key.Spacebar)) {
            ToolWindowBar(
                anchor = ToolWindowAnchor.LEFT_TOP,
                windows = listOf("workspace" to Icons.Default.Folder, "structure" to Icons.Default.Info),
                activeWindowId = "workspace",
                onWindowClick = { opened += it },
            )
        }
        assertEquals(listOf("workspace", "structure"), opened)
    }

    @Test
    fun `tab reaches a pane's close button`() {
        var closed = 0
        drive(listOf(Key.Tab, Key.Enter)) {
            ToolWindowPane(title = "Workspace", onClose = { closed++ }) { Text("body") }
        }
        assertEquals(1, closed)
    }
}
