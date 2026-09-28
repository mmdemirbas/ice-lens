package ui

import service.ObjectStoreCredentials
import service.StorageLocation
import service.WebHdfs

/**
 * A warehouse or table in object storage or on HDFS, and how to reach it.
 *
 * On HDFS ([onHdfs]) the one setting is [hdfsUser], the user WebHDFS reads as under simple
 * authentication, and the object-storage fields are unused; the two kinds are routed apart in
 * [AppState.applyRemoteCredentials].
 *
 * ### The secret is deliberately not here
 *
 * Every field on this type is persisted through `java.util.prefs`, which on macOS is a plist in the
 * user's Library and on Linux an XML file under `~/.java` — world-readable, plain text, backed up
 * and synced. A cloud key written there is a key leaked to every process the user runs and to
 * whatever copies their home directory. So the secret is held **in memory for the session only**
 * ([AppState.remoteSecrets]), and the dialog says so rather than letting a reader assume otherwise.
 *
 * [useCredentialChain] is the answer for most people and is the default the dialog offers: on a
 * machine with the AWS CLI configured, or on an instance with a role, the credentials are already
 * in the environment and DuckDB's `credential_chain` provider finds them. Asking for a paste there
 * would be asking a reader to copy a secret into one more place for no gain.
 */
data class RemoteLocation(
    val url: String,
    val useCredentialChain: Boolean = true,
    val keyId: String? = null,
    val region: String? = null,
    val endpoint: String? = null,
    val useSsl: Boolean = true,
    val urlStyle: String? = null,
    val hdfsUser: String? = null,
) {
    /** A `webhdfs://` or `swebhdfs://` location, which DuckDB is never configured for. */
    val onHdfs: Boolean get() = WebHdfs.serves(url)

    /** The bucket, which is the widest scope a key should ever be handed. */
    val bucket: String get() = url.substringAfter("://").substringBefore('/')

    /** `s3://bucket` — what the credentials are restricted to; see [ObjectStoreCredentials.scopeFor]. */
    val scope: String get() = ObjectStoreCredentials.scopeFor(url)

    /** A DuckDB secret name; see [ObjectStoreCredentials.secretNameFor]. */
    val secretName: String get() = ObjectStoreCredentials.secretNameFor(url)

    /** DuckDB's own type for the scheme; see [ObjectStoreCredentials.secretTypeFor]. */
    val secretType: String get() = ObjectStoreCredentials.secretTypeFor(url)

    fun credentials(secret: String?): ObjectStoreCredentials = ObjectStoreCredentials.forLocation(
        url = url,
        useCredentialChain = useCredentialChain,
        keyId = keyId,
        secret = secret,
        region = region,
        endpoint = endpoint,
        useSsl = useSsl,
        urlStyle = urlStyle,
    )

    fun serialize(): String = listOf(
        url, useCredentialChain.toString(), keyId.orEmpty(), region.orEmpty(),
        endpoint.orEmpty(), useSsl.toString(), urlStyle.orEmpty(), hdfsUser.orEmpty(),
    ).joinToString("|") { encodeField(it) }

    companion object {
        /** The persisted list uses `;` between entries and `|` between fields, as the workspace does. */
        private fun encodeField(value: String) =
            value.replace("%", "%25").replace(";", "%3B").replace("|", "%7C")

        private fun decodeField(value: String) =
            value.replace("%7C", "|").replace("%3B", ";").replace("%25", "%")

        fun deserialize(text: String): RemoteLocation? {
            val parts = text.split("|").map(::decodeField)
            if (parts.size < 7 || parts[0].isBlank()) return null
            if (!StorageLocation.isRemote(parts[0])) return null
            return RemoteLocation(
                url = parts[0].trimEnd('/'),
                useCredentialChain = parts[1].toBooleanStrictOrNull() ?: true,
                keyId = parts[2].ifBlank { null },
                region = parts[3].ifBlank { null },
                endpoint = parts[4].ifBlank { null },
                useSsl = parts[5].toBooleanStrictOrNull() ?: true,
                urlStyle = parts[6].ifBlank { null },
                // The eighth field arrived with HDFS; a location saved before it has seven.
                hdfsUser = parts.getOrNull(7)?.ifBlank { null },
            )
        }

        fun serializeAll(locations: List<RemoteLocation>): String =
            locations.joinToString(";") { it.serialize() }

        fun deserializeAll(text: String): List<RemoteLocation> =
            text.split(";").filter { it.isNotBlank() }.mapNotNull(::deserialize)

        /**
         * What the reader typed, as a location — or the reason it is not one.
         *
         * Returns null for anything usable. A URL is checked before it is stored because the
         * failure it otherwise produces arrives much later, at the first read, phrased as a
         * storage error about a path nobody recognises.
         */
        fun validate(url: String): String? {
            val trimmed = url.trim()
            return when {
                trimmed.isBlank() -> "Enter a location, for example s3://warehouse/db"
                // HDFS's own scheme is the RPC protocol, which nothing here speaks; the same
                // namenode answers WebHDFS on its HTTP port, which is the address to give.
                StorageLocation.schemeOf(trimmed) == "hdfs" ->
                    "HDFS is read over WebHDFS: give the namenode's HTTP address, for example " +
                        "webhdfs://namenode:9870/warehouse — not the RPC port 8020"
                !trimmed.contains("://") -> "A remote location needs a scheme, for example s3://warehouse/db"
                StorageLocation.schemeOf(trimmed) == "file" -> "Use Add to Workspace for a local path"
                // Before the scheme check, not after: `s3://` with no bucket is a URI that
                // `pathOf` also rejects, and reporting that as "nothing reads s3://" would be
                // false about the one part of the input that was right.
                trimmed.substringAfter("://").substringBefore('/').isBlank() ->
                    if (WebHdfs.serves(trimmed)) "Name the namenode and its WebHDFS port, for example webhdfs://namenode:9870/warehouse"
                    else "Name a bucket, for example s3://warehouse/db"
                runCatching { StorageLocation.pathOf(trimmed) }.isFailure ->
                    "Nothing here reads '${StorageLocation.schemeOf(trimmed)}://'. Supported: s3, gs, gcs, r2, webhdfs, swebhdfs"
                else -> null
            }
        }

        /**
         * An endpoint's own default for URL style.
         *
         * Virtual-host style needs wildcard DNS for the bucket, which a bare `host:port` almost
         * never has — so anything with an explicit endpoint gets path style unless it is told
         * otherwise. Getting this wrong produces a DNS failure that reads as the store being down.
         */
        fun defaultUrlStyle(endpoint: String?): String? = if (endpoint.isNullOrBlank()) null else "path"
    }
}
