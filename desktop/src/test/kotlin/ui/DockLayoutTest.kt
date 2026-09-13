package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The dock drawn, in the three shapes it takes: everything at its default anchor, a window moved
 * to the bottom with another hidden, and a drag in flight with the drop targets up.
 *
 * `App()` itself is never rendered in a test — it owns the preferences node and the coroutines —
 * so this is the only place the layout that used to be inlined in it is seen. The captures land
 * beside the inspector's, and the one assertion a number can state is the arithmetic of the row:
 * what the centre is given is the width minus the bars, the panes and the dividers, which is what
 * a `Row` that neither wraps nor clips would silently get wrong.
 */
class DockLayoutTest {

    private lateinit var prefs: Preferences

    @BeforeTest
    fun setUp() {
        prefs = Preferences.userRoot().node("icelens-dock-render-${System.nanoTime()}")
    }

    @AfterTest
    fun tearDown() {
        prefs.removeNode()
    }

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val outputDir = File(repoRoot, "desktop/build/reports/inspector")

    private fun dock() = DockState(
        prefs,
        listOf(
            ToolWindowConfig("workspace", "Workspace", Icons.Default.Storage, ToolWindowAnchor.LEFT_TOP),
            ToolWindowConfig("structure", "Structure", Icons.Default.AccountTree, ToolWindowAnchor.LEFT_BOTTOM),
            ToolWindowConfig("inspector", "Inspector", Icons.Default.Info, ToolWindowAnchor.RIGHT_TOP),
        ),
    )

    /** Renders two frames — the second is where anything decided by the first frame's layout lands. */
    private fun render(name: String, width: Int = 1000, height: Int = 600, content: @Composable () -> Unit): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            MaterialTheme(colorScheme = IceLensLightColorScheme) { content() }
        }
        val png = try {
            scene.render(16_000_000L)
            scene.render(32_000_000L).encodeToData()?.bytes
        } finally {
            scene.close()
        }
        val bytes = assertNotNull(png, "scene produced no image for $name")
        outputDir.mkdirs()
        File(outputDir, "$name-1.png").writeBytes(bytes)
        assertTrue(bytes.size > 5_000, "$name rendered to ${bytes.size} bytes, which is a blank scene")
        return bytes
    }

    @Composable
    private fun Dock(dock: DockState, onCentre: (Rect) -> Unit = {}) {
        DockLayout(
            dock = dock,
            modifier = Modifier.fillMaxSize(),
            centre = {
                Box(
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .onGloballyPositioned { onCentre(it.boundsInWindow()) },
                ) { Text("canvas", Modifier.padding(8.dp)) }
            },
            window = { id -> Text("$id body", Modifier.padding(8.dp)) },
        )
    }

    @Test
    fun `the centre is what the width leaves after the bars, panes and dividers`() {
        val dock = dock()
        var centre: Rect? = null
        render("dock-default") { Dock(dock) { centre = it } }

        val got = assertNotNull(centre, "the centre slot should have been laid out")
        // 32dp bar + 250dp workspace/structure + 8dp divider … 8dp divider + 300dp inspector + 32dp bar.
        assertEquals(32f + 250f + 8f, got.left)
        assertEquals(1000f - 32f - 300f - 8f, got.right)
        assertEquals(600f, got.bottom, "no bottom pane, no bottom strip: the row has the whole height")
    }

    @Test
    fun `a window moved to the bottom takes a strip from the centre, and a hidden one leaves its bar`() {
        val dock = dock()
        dock.move("inspector", ToolWindowAnchor.BOTTOM_LEFT)
        dock.toggle("structure")
        var centre: Rect? = null
        render("dock-moved") { Dock(dock) { centre = it } }

        val got = assertNotNull(centre)
        assertEquals(1000f, got.right, "nothing anchored on the right means no right bar either")
        assertEquals(600f - 220f - 8f, got.bottom, "the bottom pane and its divider come off the centre")
        assertEquals(32f + 250f + 8f, got.left, "the left pane still shows the workspace alone")
    }

    @Test
    fun `hiding a bottom window leaves its bar as a strip, which is the way back to it`() {
        val dock = dock()
        dock.move("inspector", ToolWindowAnchor.BOTTOM_RIGHT)
        dock.toggle("inspector")
        var centre: Rect? = null
        render("dock-bottom-hidden") { Dock(dock) { centre = it } }

        assertEquals(600f - 32f, assertNotNull(centre).bottom)
    }

    @Test
    fun `a drag in flight draws the drop targets over the dock`() {
        val dock = dock()
        val plain = render("dock-before-drag") { Dock(dock) }
        dock.bounds = Rect(0f, 0f, 1000f, 600f)
        dock.beginDrag("inspector", Offset(50f, 400f), edgePx = 120f)
        assertEquals(ToolWindowAnchor.LEFT_BOTTOM, dock.dragTarget)
        val dragging = render("dock-dragging") { Dock(dock) }

        assertTrue(!plain.contentEquals(dragging), "the drop targets should change the picture")
    }
}
