rootProject.name = "ice-lens"

// core   — headless engine: readers, decoders, model, analysis, layout. No UI dependency.
// desktop — shell #1: Compose Desktop over core.
//
// Further shells (server, cli) attach here as siblings of desktop, all over the same core.
include("core", "desktop")
