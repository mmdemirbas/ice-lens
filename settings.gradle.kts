rootProject.name = "ice-lens"

// core     — headless engine: readers, decoders, model, analysis, layout. No UI dependency.
// desktop  — shell #1: Compose Desktop over core.
// intellij — shell #2: an IDE tool window over core, drawn with the IDE's own Swing components.
// cli      — shell #3: the same engine from a terminal — a listing, a node's rows, the integrity
//            check as an exit code, the exports as files.
//
// A server would attach here as a fourth sibling, over the same core.
include("core", "desktop", "intellij", "cli")
