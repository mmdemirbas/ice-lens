package service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The statement that carries a secret into DuckDB, and the fact that nothing else carries it out.
 *
 * `CREATE SECRET` takes no bind parameters, so every value is inlined into SQL — which makes this
 * the one place in the app where text a reader typed becomes executable. Both halves are asserted:
 * that a quote in a value cannot end the literal, and that the value never reaches a log line.
 */
class DuckDbCredentialsTest {

    @Test
    fun `a key and secret become a scoped secret for one bucket`() {
        val sql = DuckDb.createSecretSql(
            ObjectStoreCredentials(
                name = "wh", keyId = "AKIAEXAMPLE", secret = "s3cr3t",
                region = "eu-west-1", scope = "s3://warehouse",
            )
        )
        assertTrue("CREATE OR REPLACE SECRET wh (" in sql, sql)
        assertTrue("TYPE S3" in sql, sql)
        assertTrue("KEY_ID 'AKIAEXAMPLE'" in sql, sql)
        assertTrue("SCOPE 's3://warehouse'" in sql, sql)
    }

    /**
     * The credential chain is offered, because the common case is that the key is already there.
     *
     * On a developer's machine or an instance with a role, the credentials are in the environment
     * already. Requiring a paste would be asking a reader to copy a secret into one more place —
     * and the place it would live is this app's own preferences.
     */
    @Test
    fun `the ambient credential chain is expressible without a key`() {
        val sql = DuckDb.createSecretSql(
            ObjectStoreCredentials(name = "aws", useCredentialChain = true, region = "us-east-1")
        )
        assertTrue("PROVIDER credential_chain" in sql, sql)
        assertFalse("KEY_ID" in sql, sql)
        assertFalse("SECRET '" in sql, sql)
    }

    /** MinIO and every other S3-compatible store, which is what an endpoint override is for. */
    @Test
    fun `an endpoint carries its transport and url style`() {
        val sql = DuckDb.createSecretSql(
            ObjectStoreCredentials(
                name = "minio", keyId = "k", secret = "v",
                endpoint = "127.0.0.1:9000", useSsl = false, urlStyle = "path", region = "us-east-1",
            )
        )
        assertTrue("ENDPOINT '127.0.0.1:9000'" in sql, sql)
        assertTrue("USE_SSL false" in sql, sql)
        assertTrue("URL_STYLE 'path'" in sql, sql)
    }

    /**
     * A quote in a secret cannot end the literal it sits in.
     *
     * A generated password containing `'` is ordinary, and without doubling it would either break
     * the statement or — worse — close the string and leave the rest of the password as SQL.
     */
    @Test
    fun `a quote in a value is escaped rather than ending the string`() {
        val sql = DuckDb.createSecretSql(
            ObjectStoreCredentials(name = "q", keyId = "a'b", secret = "p'); DROP TABLE x; --")
        )
        assertTrue("KEY_ID 'a''b'" in sql, sql)
        assertTrue("SECRET 'p''); DROP TABLE x; --'" in sql, sql)
        // Every quote is either half of an escaped pair or a delimiter, so the delimiters pair up
        // and the injected `DROP TABLE` never leaves the literal it is inside.
        assertEquals(0, sql.replace("''", "").count { it == '\'' } % 2, sql)
    }

    /**
     * The name is an identifier and cannot be escaped, so it is restricted instead.
     *
     * There is no quoting that makes an arbitrary string safe in the position `CREATE SECRET <x>`,
     * which is why this is the one field that is rejected rather than escaped.
     */
    @Test
    fun `a secret name that is not an identifier is refused`() {
        listOf("a b", "x); DROP TABLE y; --", "", "a-b", "aʼb").forEach { name ->
            assertFailsWith<IllegalArgumentException>("'$name' should be refused") {
                DuckDb.setCredentials(listOf(ObjectStoreCredentials(name = name)))
            }
        }
        DuckDb.setCredentials(emptyList())
    }

    /**
     * The secret is not in the object's own `toString`, which is where it would otherwise leak.
     *
     * A data class prints every field, and these end up in log lines and exception messages by
     * accident rather than by decision — an `IllegalStateException` naming the configuration that
     * failed is the obvious way to write the error, and it would carry the key.
     */
    @Test
    fun `printing the credentials does not print the credentials`() {
        val printed = ObjectStoreCredentials(
            name = "wh", keyId = "AKIAEXAMPLE", secret = "top-secret-value", sessionToken = "tok",
        ).toString()
        assertFalse("top-secret-value" in printed, printed)
        assertFalse("tok" in printed, printed)
        assertTrue("redacted" in printed, printed)
        // The name and endpoint are what a reader needs to identify which entry failed, so those stay.
        assertTrue("wh" in printed, printed)
    }

    /**
     * Every message a store's own failure produces says what to do about it.
     *
     * One generic "could not read the table" for all of these is the failure mode where the reader
     * re-clicks forever: a 403 means fix the key, a 404 means fix the path, and a refused
     * connection means the endpoint is wrong. They are told apart by what the engine reported.
     */
    @Test
    fun `the three failures that actually happen are told apart`() {
        val denied = ObjectStorage.describe("s3://b/k", "HTTP GET error (HTTP 403) Authentication Failure")
        val missing = ObjectStorage.describe("s3://b/k", "Unable to connect to URL: 404 (Not Found)")
        val unreachable = ObjectStorage.describe("s3://b/k", "Connection refused")
        assertTrue("credentials" in denied && "refused them" in denied, denied)
        assertTrue("check the path" in missing, missing)
        assertTrue("endpoint" in unreachable, unreachable)
        listOf(denied, missing, unreachable).forEach { assertTrue("s3://b/k" in it, it) }
        // Three distinct messages, not one message three times.
        assertEquals(3, setOf(denied, missing, unreachable).size)
    }
}
