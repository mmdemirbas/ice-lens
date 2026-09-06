package plugin

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * The tool window. Everything it draws is [IceLensPanel]; this only attaches it.
 *
 * `DumbAware` because nothing here needs indices: the table is read off the filesystem by the same
 * engine the desktop app uses, and a reader who opened the IDE on a warehouse should not have to
 * wait for indexing to look at a manifest.
 */
class IceLensToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = IceLensPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel.component, null, false)
        toolWindow.contentManager.addContent(content)
    }
}
