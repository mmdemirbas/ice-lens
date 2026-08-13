package ui

import androidx.compose.ui.graphics.vector.ImageVector

enum class ToolWindowAnchor {
    LEFT_TOP, LEFT_BOTTOM, RIGHT_TOP, RIGHT_BOTTOM, BOTTOM_LEFT, BOTTOM_RIGHT
}

data class ToolWindowConfig(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val anchor: ToolWindowAnchor,
    val isVisible: Boolean = true,
    val order: Int = 0
)
