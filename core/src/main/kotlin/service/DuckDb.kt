package service

import java.sql.Connection
import java.sql.DriverManager
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("service.DuckDb")

/**
 * How to reach one object store, as DuckDB describes it.
 *
 * [keyId] and [secret] are null when [useCredentialChain] is set, which is the shape that matters
 * most in practice: on a developer's machine or an EC2 instance the credentials are already in the
 * environment (`~/.aws/credentials`, SSO, an instance profile), and asking a reader to paste a key
 * that is already configured is asking them to copy a secret into one more place.
 *
 * [endpoint] is what makes this work against anything other than AWS — MinIO, Ceph, OBS. It is
 * `host:port` without a scheme, because that is the form DuckDB's `ENDPOINT` takes; [useSsl] and
 * [urlStyle] carry the rest of what a non-AWS endpoint needs, and `path` style is what a bare
 * `host:port` almost always wants since virtual-host style requires wildcard DNS.
 *
 * [scope] restricts which URLs the credentials apply to (`s3://bucket`), so a workspace holding
 * two buckets in two accounts does not send one account's key to the other.
 */
data class ObjectStoreCredentials(
    val name: String,
    val type: String = "s3",
    val keyId: String? = null,
    val secret: String? = null,
    val sessionToken: String? = null,
    val region: String? = null,
    val endpoint: String? = null,
    val useSsl: Boolean = true,
    val urlStyle: String? = null,
    val scope: String? = null,
    val useCredentialChain: Boolean = false,
) {
    /**
     * The secret's own `toString`, with nothing secret in it.
     *
     * A data class prints every field, and these end up in log lines, crash reports and
     * `IllegalStateException` messages by accident rather than by decision. Overriding it is the
     * only way that stays true as fields are added.
     */
    override fun toString(): String =
        "ObjectStoreCredentials(name=$name, type=$type, endpoint=$endpoint, region=$region, " +
            "scope=$scope, chain=$useCredentialChain, keyId=${if (keyId == null) "unset" else "set"}, " +
            "secret=${if (secret == null) "unset" else "redacted"})"
}

/**
 * The DuckDB connection this app reads through, and the credentials configured on it.
 *
 * One connection, deliberately. DuckDB is both the sample-row engine and — once a table lives in
 * object storage — the thing that reads the metadata files too, and a second connection would mean
 * a second place to configure the same credentials. A reader who typed a key once and still got
 * "access denied" from half the app would be right to call that a bug.
 *
 * Everything runs under [lock]. A DuckDB `Connection` is not safe to share across threads, and the
 * graph is built off the main thread while the inspector reads rows on another.
 */
object DuckDb {

    init {
        Class.forName("org.duckdb.DuckDBDriver")
    }

    /** A secret name is a SQL identifier and cannot be escaped, so it is restricted instead. */
    private val SECRET_NAME = Regex("^[A-Za-z0-9_]{1,64}$")

    private val lock = Any()
    @Volatile private var connection: Connection? = null
    private val credentials = linkedMapOf<String, ObjectStoreCredentials>()
    @Volatile private var httpfsLoaded = false

    /** Whether any object-store credentials have been configured. */
    val hasCredentials: Boolean get() = synchronized(lock) { credentials.isNotEmpty() }

    /** The configured entries, safe to print — [ObjectStoreCredentials.toString] redacts. */
    fun configured(): List<ObjectStoreCredentials> = synchronized(lock) { credentials.values.toList() }

    /**
     * Runs [block] against the shared connection while holding the lock.
     *
     * The connection is rebuilt if it was closed — a DuckDB connection can be dropped by an
     * out-of-memory condition inside the engine, and the alternative to reopening is that every
     * later read in the session fails with a stale-handle error nobody can act on.
     */
    fun <T> withConnection(block: (Connection) -> T): T = synchronized(lock) {
        val existing = connection?.takeIf { !it.isClosed }
        val conn = existing ?: DriverManager.getConnection("jdbc:duckdb:").also {
            logger.debug("Opened a DuckDB connection")
            connection = it
            httpfsLoaded = false
        }
        if (existing == null) reapplyLocked(conn)
        block(conn)
    }

    /**
     * Replaces the configured credentials and applies them.
     *
     * Replaces rather than adds, so removing a location from the workspace actually removes its
     * key from the engine. DuckDB has no "drop every secret", so the connection is reset instead —
     * cheap, since it holds no state this app cares about between reads.
     */
    fun setCredentials(entries: List<ObjectStoreCredentials>) {
        synchronized(lock) {
            val same = credentials.values.toList() == entries
            credentials.clear()
            entries.forEach { entry ->
                require(SECRET_NAME.matches(entry.name)) {
                    "A secret name may only hold letters, digits and underscores: '${entry.name}'"
                }
                credentials[entry.name] = entry
            }
            if (!same) {
                // Drop the connection so no previously-created secret survives the change.
                runCatching { connection?.close() }
                connection = null
                httpfsLoaded = false
                ObjectStorage.clearCache()
            }
        }
    }

    /** Closes the connection if open. Safe to call repeatedly. */
    fun close() {
        synchronized(lock) {
            connection?.let { conn ->
                runCatching { conn.close() }.onFailure { logger.warn("Closing DuckDB failed: {}", it.message) }
            }
            connection = null
            httpfsLoaded = false
        }
    }

    /**
     * Loads `httpfs` and creates every configured secret. Must be called holding [lock].
     *
     * `httpfs` is not compiled into the JDBC jar; DuckDB fetches it once and caches it under the
     * user's home. That download is the one part of remote reading that can fail before any
     * credential is even used, so it is reported as itself rather than as a failure to read a file.
     */
    private fun reapplyLocked(conn: Connection) {
        if (credentials.isEmpty()) return
        if (!httpfsLoaded) {
            runCatching {
                conn.createStatement().use { st ->
                    st.execute("INSTALL httpfs")
                    st.execute("LOAD httpfs")
                }
            }.onFailure {
                throw IllegalStateException(
                    "DuckDB could not load its httpfs extension, which is what reads object storage. " +
                        "It is downloaded once from extensions.duckdb.org and cached locally, so this " +
                        "usually means no network on first use: ${it.message}",
                    it,
                )
            }
            httpfsLoaded = true
        }
        credentials.values.forEach { entry ->
            runCatching { conn.createStatement().use { it.execute(createSecretSql(entry)) } }
                .onFailure {
                    // The SQL is never logged or attached: it carries the key.
                    throw IllegalStateException(
                        "DuckDB rejected the credentials named '${entry.name}': ${it.message}", it,
                    )
                }
        }
        logger.info("Configured {} object-store credential(s) on DuckDB", credentials.size)
    }

    /**
     * The `CREATE SECRET` statement for [entry].
     *
     * `CREATE SECRET` takes no bind parameters, so every value is inlined — which makes this a
     * trust boundary, since an endpoint or a scope comes from whatever the reader typed. Single
     * quotes are doubled per SQL, and the one value that cannot be quoted at all, the secret's
     * name, is an identifier checked against [SECRET_NAME] before it reaches here.
     *
     * Internal for the test that asserts the escaping; it is never logged.
     */
    internal fun createSecretSql(entry: ObjectStoreCredentials): String {
        fun lit(value: String) = "'" + value.replace("'", "''") + "'"
        val fields = buildList {
            add("TYPE ${entry.type.uppercase().filter { it.isLetterOrDigit() || it == '_' }}")
            if (entry.useCredentialChain) {
                add("PROVIDER credential_chain")
            } else {
                entry.keyId?.let { add("KEY_ID ${lit(it)}") }
                entry.secret?.let { add("SECRET ${lit(it)}") }
                entry.sessionToken?.let { add("SESSION_TOKEN ${lit(it)}") }
            }
            entry.region?.let { add("REGION ${lit(it)}") }
            entry.endpoint?.let {
                add("ENDPOINT ${lit(it)}")
                add("USE_SSL ${entry.useSsl}")
            }
            entry.urlStyle?.let { add("URL_STYLE ${lit(it)}") }
            entry.scope?.let { add("SCOPE ${lit(it)}") }
        }
        return "CREATE OR REPLACE SECRET ${entry.name} (${fields.joinToString(", ")})"
    }
}
