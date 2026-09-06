rootProject.name = "ice-lens"

// core     — headless engine: readers, decoders, model, analysis, layout. No UI dependency.
// desktop  — shell #1: Compose Desktop over core.
// intellij — shell #2: the same cards in an IDE tool window, over :desktop's UI.
//
// Further shells (server, cli) attach here as siblings, all over the same core.
include("core", "desktop", "intellij")
