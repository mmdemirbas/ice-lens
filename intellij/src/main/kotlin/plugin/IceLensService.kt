package plugin

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Which table the tool window is showing, for one project.
 *
 * A service rather than state on the panel because the *action* chooses the table and the panel
 * draws it, and neither holds a reference to the other — the action runs from a context menu that
 * may fire before the tool window has ever been opened. The last choice is replayed to a listener
 * that registers afterwards, which is exactly that case: right-click, open, and the panel is
 * created already knowing what to draw.
 *
 * Plain listeners rather than a `StateFlow`. The IDE forbids a plugin bundling its own
 * kotlinx-coroutines and the platform's own is versioned with the IDE, so a listener list is one
 * fewer thing that can go wrong at a classloader boundary for a callback fired once per click.
 */
@Service(Service.Level.PROJECT)
class IceLensService {

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** The table path currently being shown, or null before anything has been chosen. */
    @Volatile
    var table: String? = null
        private set

    fun open(path: String) {
        table = path
        listeners.forEach { it(path) }
    }

    /** Registers [listener] and immediately replays the current selection, if there is one. */
    fun addListener(listener: (String) -> Unit) {
        listeners += listener
        table?.let(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners -= listener
    }

    companion object {
        fun of(project: Project): IceLensService = project.service()
    }
}
