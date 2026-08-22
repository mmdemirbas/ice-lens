package ui

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The five sizes this app draws text at. Nothing outside this file names a number of `sp`.
 *
 * Before it there were eight, from 8sp to 16sp, chosen one call site at a time. Eight sizes
 * spanning 2× is not a hierarchy — consecutive steps were 1.09× apart, which is under the
 * threshold at which a difference reads as deliberate, so the screen looked like one flat size
 * with noise on it. Five steps at a ratio near 1.2 read as five levels.
 *
 * | Step | Size | For |
 * |---|---|---|
 * | [micro] | 10sp | a card's eyebrow label, and nothing else |
 * | [small] | 12sp | table cells, secondary prose, the text inside controls |
 * | [body] | 14sp | primary body text, and the line that names a card |
 * | [title] | 17sp | a section heading |
 * | [display] | 21sp | the inspector's node header, one per panel |
 *
 * **A card's font sizes and its node's declared height are one decision.** `GraphNode` declares
 * the height ELK reserves and the card is drawn at exactly that; Compose clips nothing, but a
 * `Column` out of room simply does not place its last child, and each `Text` clips itself to what
 * it was measured at — so a card that outgrows its node loses a line with nothing failing.
 * Changing a step here changes every card that uses it. `CardHeightTest` is what catches it: it
 * draws each card with room to spare and requires the content to have fitted without.
 */
object TypeScale {
    val micro: TextUnit = 10.sp
    val small: TextUnit = 12.sp
    val body: TextUnit = 14.sp
    val title: TextUnit = 17.sp
    val display: TextUnit = 21.sp
}
