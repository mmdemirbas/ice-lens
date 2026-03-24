package app

import org.slf4j.LoggerFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ui.App
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowStateListener
import java.util.prefs.Preferences

private val prefs = Preferences.userRoot().node("com.github.mmdemirbas.icelens.window")
private const val PREF_WINDOW_X = "window_x"
private const val PREF_WINDOW_Y = "window_y"
private const val PREF_WINDOW_WIDTH = "window_width"
private const val PREF_WINDOW_HEIGHT = "window_height"
private const val PREF_WINDOW_MAXIMIZED = "window_maximized"
private const val PREF_DISPLAY_ID = "display_id"
private const val PREF_DISPLAY_INDEX = "display_index"

private const val DEFAULT_WIDTH = 1200
private const val DEFAULT_HEIGHT = 800
private const val MIN_WIDTH = 800
private const val MIN_HEIGHT = 500

private data class LaunchWindowConfig(
    val bounds: Rectangle,
    val maximized: Boolean
)

private fun allDisplayDevices(): List<GraphicsDevice> =
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.toList()

private fun selectedDisplay(devices: List<GraphicsDevice>): GraphicsDevice? {
    if (devices.isEmpty()) return null
    val savedId = prefs.get(PREF_DISPLAY_ID, null)
    if (!savedId.isNullOrBlank()) {
        devices.firstOrNull { it.iDstring == savedId }?.let { return it }
    }
    val savedIndex = prefs.getInt(PREF_DISPLAY_INDEX, -1)
    if (savedIndex in devices.indices) return devices[savedIndex]
    return GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice ?: devices.first()
}

private fun safelySizedBounds(screenBounds: Rectangle): Rectangle {
    val width = (prefs.getInt(PREF_WINDOW_WIDTH, DEFAULT_WIDTH))
        .coerceAtLeast(MIN_WIDTH)
        .coerceAtMost((screenBounds.width - 40).coerceAtLeast(MIN_WIDTH))
    val height = (prefs.getInt(PREF_WINDOW_HEIGHT, DEFAULT_HEIGHT))
        .coerceAtLeast(MIN_HEIGHT)
        .coerceAtMost((screenBounds.height - 40).coerceAtLeast(MIN_HEIGHT))
    val x = screenBounds.x + ((screenBounds.width - width) / 2).coerceAtLeast(0)
    val y = screenBounds.y + ((screenBounds.height - height) / 2).coerceAtLeast(0)
    return Rectangle(x, y, width, height)
}

private fun resolveLaunchWindowConfig(): LaunchWindowConfig {
    val devices = allDisplayDevices()
    val targetScreen = selectedDisplay(devices)?.defaultConfiguration?.bounds
        ?: Rectangle(100, 100, DEFAULT_WIDTH, DEFAULT_HEIGHT)
    val wasMaximized = prefs.getBoolean(PREF_WINDOW_MAXIMIZED, false)
    val savedX = prefs.get(PREF_WINDOW_X, null)?.toIntOrNull()
    val savedY = prefs.get(PREF_WINDOW_Y, null)?.toIntOrNull()
    val savedW = prefs.getInt(PREF_WINDOW_WIDTH, DEFAULT_WIDTH)
    val savedH = prefs.getInt(PREF_WINDOW_HEIGHT, DEFAULT_HEIGHT)
    val savedBounds = if (savedX != null && savedY != null && savedW > 0 && savedH > 0) {
        Rectangle(savedX, savedY, savedW, savedH)
    } else null
    val visibleBounds = devices.map { it.defaultConfiguration.bounds }
    val safeBounds = if (wasMaximized) {
        // When reopening maximized, prefer the selected display and avoid stale saved bounds
        // from another monitor.
        safelySizedBounds(targetScreen)
    } else if (savedBounds != null && visibleBounds.any { it.intersects(savedBounds) }) {
        savedBounds
    } else {
        safelySizedBounds(targetScreen)
    }
    return LaunchWindowConfig(
        bounds = safeBounds,
        maximized = wasMaximized
    )
}

private fun deviceForWindowBounds(bounds: Rectangle, devices: List<GraphicsDevice>): Pair<GraphicsDevice, Int>? {
    if (devices.isEmpty()) return null
    val center = Point(bounds.centerX.toInt(), bounds.centerY.toInt())
    devices.forEachIndexed { index, device ->
        if (device.defaultConfiguration.bounds.contains(center)) {
            return device to index
        }
    }
    var bestArea = -1L
    var bestPair: Pair<GraphicsDevice, Int>? = null
    devices.forEachIndexed { index, device ->
        val overlap = bounds.intersection(device.defaultConfiguration.bounds)
        val area = overlap.width.toLong().coerceAtLeast(0L) * overlap.height.toLong().coerceAtLeast(0L)
        if (area > bestArea) {
            bestArea = area
            bestPair = device to index
        }
    }
    return bestPair ?: (devices.first() to 0)
}

private fun persistWindowState(window: java.awt.Window, isMaximized: Boolean) {
    prefs.putBoolean(PREF_WINDOW_MAXIMIZED, isMaximized)
    val devices = allDisplayDevices()
    val selected = deviceForWindowBounds(window.bounds, devices)
    if (selected != null) {
        prefs.put(PREF_DISPLAY_ID, selected.first.iDstring)
        prefs.putInt(PREF_DISPLAY_INDEX, selected.second)
    }
    if (!isMaximized) {
        prefs.putInt(PREF_WINDOW_X, window.x)
        prefs.putInt(PREF_WINDOW_Y, window.y)
        prefs.putInt(PREF_WINDOW_WIDTH, window.width)
        prefs.putInt(PREF_WINDOW_HEIGHT, window.height)
    }
}

private val logger = LoggerFactory.getLogger("app.Main")

fun main() = application {
    logger.info("Iceberg Lens starting — log file: {}/.icelens/icelens.log", System.getProperty("user.home"))
    val launchConfig = resolveLaunchWindowConfig()
    var awtWindow by remember { mutableStateOf<java.awt.Window?>(null) }
    val windowState = rememberWindowState(
        position = WindowPosition(
            launchConfig.bounds.x.dp,
            launchConfig.bounds.y.dp
        ),
        size = DpSize(
            launchConfig.bounds.width.dp,
            launchConfig.bounds.height.dp
        ),
        placement = if (launchConfig.maximized) WindowPlacement.Maximized else WindowPlacement.Floating
    )

    Window(
        onCloseRequest = {
            val w = awtWindow
            if (w != null) {
                persistWindowState(w, windowState.placement == WindowPlacement.Maximized)
            }
            exitApplication()
        },
        title = "Iceberg Lens",
        state = windowState
    ) {
        awtWindow = window
        DisposableEffect(awtWindow) {
            val w = awtWindow
            if (w == null) {
                onDispose {}
            } else {
                val componentListener = object : ComponentAdapter() {
                    override fun componentMoved(e: ComponentEvent?) {
                        persistWindowState(w, windowState.placement == WindowPlacement.Maximized)
                    }

                    override fun componentResized(e: ComponentEvent?) {
                        persistWindowState(w, windowState.placement == WindowPlacement.Maximized)
                    }
                }
                w.addComponentListener(componentListener)

                val frame = w as? java.awt.Frame
                val stateListener = WindowStateListener {
                    persistWindowState(w, windowState.placement == WindowPlacement.Maximized)
                }
                frame?.addWindowStateListener(stateListener)

                onDispose {
                    w.removeComponentListener(componentListener)
                    frame?.removeWindowStateListener(stateListener)
                    persistWindowState(w, windowState.placement == WindowPlacement.Maximized)
                }
            }
        }
        App()
    }
}
