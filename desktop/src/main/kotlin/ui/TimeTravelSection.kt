package ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import model.TimeTravelResolution

/** The widest the time field grows; a timestamp is one line and the panel is narrow. */
private val TIME_FIELD_MAX_WIDTH = 320.dp

/**
 * Which snapshot a read as of a typed time lands on — see [TimeTravelResolution] for the rule
 * each format applies. The field is seeded with the clock the expiry sections plan from, so
 * the section answers something before anything is typed, and its text is local state the way
 * the filter clause is: re-deriving it from the parsed time each frame would rewrite what the
 * reader is typing under the cursor. [intro] is the format's own sentence about the rule,
 * [resolve] applies it, and [operationOf] names the commit where the panel can.
 */
@Composable
internal fun TimeTravelSection(
    intro: String,
    initialMs: Long,
    resolve: (Long) -> TimeTravelResolution,
    operationOf: (Long) -> String?,
    currentSnapshotId: Long?,
) {
    val colors = MaterialTheme.colorScheme
    // Seeded exactly — with milliseconds where the clock has them — so the seed resolves to what
    // the clock resolves to; two commits a hundred milliseconds apart are one second on screen.
    var text by remember { mutableStateOf(formatAppTimestampExact(initialMs)) }
    val parsed = parseAppTimestamp(text)
    Section("Time Travel") {
        Text(intro, fontSize = TypeScale.small, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            isError = parsed == null,
            label = { Text("As of", fontSize = TypeScale.small) },
            placeholder = { Text("yyyy-MM-dd HH:mm:ss, an ISO instant, or epoch ms", fontSize = TypeScale.small) },
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.widthIn(max = TIME_FIELD_MAX_WIDTH).fillMaxWidth(),
        )
        if (parsed == null) {
            Text(
                "Not a time this reads: use yyyy-MM-dd HH:mm[:ss] in the local zone, an ISO-8601 instant, or epoch milliseconds.",
                fontSize = TypeScale.small,
                color = colors.error,
                modifier = Modifier.padding(top = 4.dp),
            )
            return@Section
        }
        val r = resolve(parsed)
        val id = r.snapshotId
        val lead = if (id == null) {
            "No snapshot at or before ${formatAppTimestamp(parsed)}" +
                (r.earliestMs?.let { " — the earliest a read can resolve to is ${formatAppTimestamp(it)}" } ?: " — the table has no snapshot") + "."
        } else {
            "Resolves to snapshot $id" + (operationOf(id)?.let { " ($it)" } ?: "") +
                (if (id == currentSnapshotId) ", the current snapshot" else "") +
                (r.atMs?.let { ", ${if (r.viaReset) "main set back to it" else "committed"} at ${formatAppTimestampExact(it)}" } ?: "") + "."
        }
        Text(lead, fontSize = TypeScale.body, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        val note = when {
            id == null -> null
            r.currentAncestor == false -> "Not an ancestor of the current snapshot: main was set back past it" +
                (r.leftBehindAt?.timestampMs?.let { " at ${formatAppTimestampExact(it)}" } ?: "") +
                ", so a read here sees data the table has since abandoned."
            r.viaReset -> "Resolved through the reset's own log entry — main was pointed back at this older snapshot at that moment, which is what makes it the last entry at or before the time."
            else -> null
        }
        if (note != null) Text(note, fontSize = TypeScale.small, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}
