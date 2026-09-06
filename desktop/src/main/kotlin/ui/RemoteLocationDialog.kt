package ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * What the reader has to say to open a table that is not on this machine.
 *
 * The dialog is [RemoteLocationDialog]; everything it decides is in [RemoteLocationForm], which is
 * a composable of its own for the same reason `PageSizeField` is — a dialog is a window, and a
 * capture cannot open one, so the part worth looking at has to be renderable on its own.
 *
 * **The default is the ambient credential chain**, because on a machine with the AWS CLI
 * configured, or on an instance with a role, the key is already there and asking for a paste would
 * be asking a reader to copy a secret into one more place. The typed-key branch exists for MinIO,
 * Ceph and OBS, which is also why the endpoint field is beside it rather than hidden behind an
 * "advanced" disclosure: an endpoint is the *ordinary* case for those, not an exception.
 */
@Composable
fun RemoteLocationDialog(
    existing: RemoteLocation? = null,
    onDismiss: () -> Unit,
    onConfirm: (RemoteLocation, String?) -> Unit,
) {
    var url by remember { mutableStateOf(existing?.url ?: "s3://") }
    var useChain by remember { mutableStateOf(existing?.useCredentialChain ?: true) }
    var keyId by remember { mutableStateOf(existing?.keyId.orEmpty()) }
    var secret by remember { mutableStateOf("") }
    var region by remember { mutableStateOf(existing?.region.orEmpty()) }
    var endpoint by remember { mutableStateOf(existing?.endpoint.orEmpty()) }
    var useSsl by remember { mutableStateOf(existing?.useSsl ?: true) }

    val problem = RemoteLocation.validate(url)
    fun build() = RemoteLocation(
        url = url.trim().trimEnd('/'),
        useCredentialChain = useChain,
        keyId = keyId.trim().ifBlank { null },
        region = region.trim().ifBlank { null },
        endpoint = endpoint.trim().ifBlank { null },
        useSsl = useSsl,
        urlStyle = RemoteLocation.defaultUrlStyle(endpoint.trim().ifBlank { null }),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (existing == null) "Add a location in object storage" else "Credentials for this location",
                fontSize = TypeScale.body,
            )
        },
        text = {
            RemoteLocationForm(
                url = url, onUrlChange = { url = it }, problem = problem,
                useChain = useChain, onUseChainChange = { useChain = it },
                keyId = keyId, onKeyIdChange = { keyId = it },
                secret = secret, onSecretChange = { secret = it },
                region = region, onRegionChange = { region = it },
                endpoint = endpoint, onEndpointChange = { endpoint = it },
                useSsl = useSsl, onUseSslChange = { useSsl = it },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(build(), secret.takeIf { !useChain && it.isNotBlank() }) },
                enabled = problem == null,
            ) { Text(if (existing == null) "Add" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The form itself — see [RemoteLocationDialog] for why it is separate. */
@Composable
fun RemoteLocationForm(
    url: String,
    onUrlChange: (String) -> Unit,
    problem: String?,
    useChain: Boolean,
    onUseChainChange: (Boolean) -> Unit,
    keyId: String,
    onKeyIdChange: (String) -> Unit,
    secret: String,
    onSecretChange: (String) -> Unit,
    region: String,
    onRegionChange: (String) -> Unit,
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    useSsl: Boolean,
    onUseSslChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.width(FORM_WIDTH).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            singleLine = true,
            isError = problem != null,
            label = { Text("Location") },
            // The supported schemes are on screen from the start rather than only once the reader
            // has typed an unsupported one: which stores this build can open is the question.
            supportingText = {
                CompactText { Text(problem ?: "A warehouse or a single table. s3://, gs://, gcs:// or r2://") }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "CREDENTIALS",
            fontSize = TypeScale.micro,
            fontWeight = FontWeight.Bold,
            color = colors.onSurfaceVariant,
        )

        CredentialChoice(
            selected = useChain,
            onSelect = { onUseChainChange(true) },
            title = "Use the credentials already on this machine",
            detail = "The AWS CLI profile, the environment, or an instance role — whichever answers first.",
        )
        CredentialChoice(
            selected = !useChain,
            onSelect = { onUseChainChange(false) },
            title = "Enter a key",
            detail = "For MinIO, Ceph, OBS, or an account the chain does not cover.",
        )

        if (!useChain) {
            OutlinedTextField(
                value = keyId, onValueChange = onKeyIdChange, singleLine = true,
                label = { Text("Access key id") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = secret, onValueChange = onSecretChange, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                label = { Text("Secret access key") },
                // Stated on the form rather than left to be discovered at the next launch. The
                // preferences file this app persists to is plain text in the user's home on every
                // platform it ships to, so a key written there is readable by anything they run.
                supportingText = { CompactText { Text("Kept for this session only — it is never written to disk.") } },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "ENDPOINT",
            fontSize = TypeScale.micro,
            fontWeight = FontWeight.Bold,
            color = colors.onSurfaceVariant,
        )
        OutlinedTextField(
            value = endpoint, onValueChange = onEndpointChange, singleLine = true,
            label = { Text("Host and port") },
            supportingText = { CompactText { Text("Leave empty for AWS. For MinIO or Ceph: 127.0.0.1:9000") } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = region, onValueChange = onRegionChange, singleLine = true,
            label = { Text("Region") },
            supportingText = { CompactText { Text("Optional. us-east-1 works for most S3-compatible stores.") } },
            modifier = Modifier.fillMaxWidth(),
        )
        if (endpoint.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Switch(checked = useSsl, onCheckedChange = onUseSslChange)
                Spacer(Modifier.width(8.dp))
                CompactText {
                    Column {
                        Text("Use HTTPS", fontSize = TypeScale.small)
                        Text(
                            if (useSsl) "The endpoint is reached over TLS."
                            else "Plain HTTP — for a local container only.",
                            fontSize = TypeScale.micro,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One of the two ways to authenticate, with what it means under it.
 *
 * The label column is `CompactText`-styled for the reason `CardColumn` exists: Material3's body
 * style carries `lineHeight = 24.sp`, and a `Text` that overrides only `fontSize` keeps it — so a
 * 10sp explanation wrapping to three lines occupies 72dp and reads as three separate sentences
 * floating apart rather than as one caption under its option.
 *
 * The radio is aligned to the title's own centre rather than to the top of the block or the centre
 * of it: a two-line title with a three-line caption under it puts the block's centre halfway down
 * the caption, and the control would sit beside the explanation instead of beside the choice.
 */
@Composable
private fun CredentialChoice(selected: Boolean, onSelect: () -> Unit, title: String, detail: String) {
    val colors = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(4.dp))
        CompactText {
            Column(modifier = Modifier.padding(top = 10.dp)) {
                Text(title, fontSize = TypeScale.small)
                Text(detail, fontSize = TypeScale.micro, color = colors.onSurfaceVariant)
            }
        }
    }
}

private val FORM_WIDTH = 420.dp
