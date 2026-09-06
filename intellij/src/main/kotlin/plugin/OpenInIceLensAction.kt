package plugin

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import java.nio.file.Path
import service.TableFormat
import service.TableFormatDetector

/**
 * "Open in Iceberg Lens" on a directory in the Project view.
 *
 * The item is **hidden rather than disabled** on anything that is not a table: a context menu in
 * this IDE is already long, and a permanently greyed entry on every directory in the project would
 * be noise on every right-click to say something about almost none of them. Where it does apply it
 * is the whole affordance — a reader working in a warehouse repository gets to the lens from the
 * file they are looking at rather than by typing its path somewhere.
 */
class OpenInIceLensAction : AnAction() {

    /**
     * The detection reads the filesystem — a directory listing looking for `*.metadata.json` — so
     * it may not run on the event dispatch thread. `BGT` is what the platform requires of an
     * `update` that touches the VFS or the disk, and getting it wrong is a freeze report rather
     * than an exception.
     */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val table = tableOf(event)
        event.presentation.isEnabledAndVisible = table != null
        if (table != null) {
            event.presentation.text = "Open ${table.second.name.lowercase()
                .replaceFirstChar { it.uppercase() }} Table in Iceberg Lens"
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val (file, _) = tableOf(event) ?: return
        IceLensService.of(project).open(file.path)
        ToolWindowManager.getInstance(project).getToolWindow("Iceberg Lens")?.activate(null)
    }

    /** The selected directory and its format, or null when the selection is not a table. */
    private fun tableOf(event: AnActionEvent): Pair<VirtualFile, TableFormat>? {
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        if (!file.isDirectory || file.fileSystem.protocol != "file") return null
        val format = runCatching { TableFormatDetector.detect(Path.of(file.path)) }
            .getOrDefault(TableFormat.UNKNOWN)
        return if (format == TableFormat.UNKNOWN) null else file to format
    }
}
