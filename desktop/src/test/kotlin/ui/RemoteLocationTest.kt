package ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What is stored about a remote location, and — more importantly — what is not.
 *
 * The secret is absent by construction: [RemoteLocation] has no field for it, so there is nothing
 * to accidentally serialise. That is asserted here rather than left to the reader of the class,
 * because "we remember not to persist it" is the kind of rule a later field quietly breaks.
 */
class RemoteLocationTest {

    private val minio = RemoteLocation(
        url = "s3://warehouse/db",
        useCredentialChain = false,
        keyId = "minioadmin",
        region = "us-east-1",
        endpoint = "127.0.0.1:9000",
        useSsl = false,
        urlStyle = "path",
    )

    @Test
    fun `a location round-trips through the persisted form`() {
        assertEquals(minio, RemoteLocation.deserialize(minio.serialize()))
        val chain = RemoteLocation(url = "s3://prod-lake/warehouse")
        assertEquals(chain, RemoteLocation.deserialize(chain.serialize()))
        assertEquals(
            listOf(minio, chain),
            RemoteLocation.deserializeAll(RemoteLocation.serializeAll(listOf(minio, chain))),
        )
    }

    /**
     * The two characters the persisted format is built from cannot break it.
     *
     * `;` separates entries and `|` separates fields, exactly as the workspace list does — so a
     * value holding either would split one entry into two and shift every field after it.
     */
    @Test
    fun `a separator inside a value survives the round trip`() {
        val awkward = minio.copy(url = "s3://bucket/a;b|c/d", region = "a|b;c")
        assertEquals(awkward, RemoteLocation.deserialize(awkward.serialize()))
    }

    /** Nothing in the persisted form is the secret, because there is nowhere to put one. */
    @Test
    fun `the persisted form cannot carry a secret`() {
        assertTrue("minioadmin" in minio.serialize(), "the key id is not a secret and is kept")
        // Every field, joined — if a secret field is ever added, it lands here and this fails.
        assertEquals(7, minio.serialize().split("|").size, "the persisted field count changed")
    }

    /**
     * A key is scoped to its bucket, never to the whole store.
     *
     * A workspace can hold two buckets in two accounts. An unscoped secret is the one DuckDB
     * reaches for on any URL, so one account's key would be sent to the other's endpoint.
     */
    @Test
    fun `credentials are scoped to the bucket, not the prefix and not everything`() {
        assertEquals("s3://warehouse", minio.scope)
        assertEquals("s3://warehouse", minio.credentials("shh").scope)
        // Not the prefix: a table under db/ must still be covered by the same secret.
        assertTrue(RemoteLocation(url = "s3://warehouse/db/mor/deep").scope == "s3://warehouse")
    }

    @Test
    fun `the credential chain sends no key at all`() {
        val chain = RemoteLocation(url = "s3://prod/wh", keyId = "left-over", useCredentialChain = true)
        val credentials = chain.credentials("also-left-over")
        assertTrue(credentials.useCredentialChain)
        assertNull(credentials.keyId, "a stale key id must not be sent alongside the chain")
        assertNull(credentials.secret)
    }

    /**
     * The secret name is a SQL identifier, and DuckDb refuses anything else.
     *
     * It is derived from a URL, which holds `:` and `/` — so this has to sanitise rather than
     * pass through, or every location would be rejected by the check on the other side.
     */
    @Test
    fun `the derived secret name is an identifier DuckDb accepts`() {
        listOf("s3://warehouse/db", "gs://a-b.c/d e", "r2://x")
            .map { RemoteLocation(url = it).secretName }
            .forEach { name ->
                assertTrue(
                    Regex("^[A-Za-z0-9_]{1,64}$").matches(name),
                    "'$name' is not something CREATE SECRET will take",
                )
            }
        // Two locations must not collide onto one secret, or one would silently replace the other.
        assertTrue(
            RemoteLocation(url = "s3://a/b").secretName != RemoteLocation(url = "s3://a/c").secretName,
        )
    }

    /** The scheme decides the secret type; three of the four speak the S3 API. */
    @Test
    fun `each scheme maps to the type DuckDb knows it by`() {
        assertEquals("s3", RemoteLocation(url = "s3://b/k").secretType)
        assertEquals("gcs", RemoteLocation(url = "gs://b/k").secretType)
        assertEquals("gcs", RemoteLocation(url = "gcs://b/k").secretType)
        assertEquals("r2", RemoteLocation(url = "r2://b/k").secretType)
    }

    /**
     * What is wrong is said before it is stored, not at the first read.
     *
     * The failure a bad URL otherwise produces arrives much later and is phrased as a storage
     * error about a path nobody recognises.
     */
    @Test
    fun `a location is checked before it is accepted`() {
        assertNull(RemoteLocation.validate("s3://warehouse/db"))
        assertNull(RemoteLocation.validate("  gs://bucket  "))
        assertTrue(RemoteLocation.validate("").orEmpty().contains("s3://"))
        assertTrue(RemoteLocation.validate("/wh/db/t").orEmpty().contains("scheme"))
        assertTrue(RemoteLocation.validate("file:///wh/db").orEmpty().contains("Add to Workspace"))
        // A scheme nothing on the classpath serves, named rather than accepted and failed later.
        val unserved = RemoteLocation.validate("hdfs://nn:8020/wh").orEmpty()
        assertTrue("hdfs" in unserved && "s3, gs, gcs, r2" in unserved, unserved)
        assertTrue(RemoteLocation.validate("s3://").orEmpty().contains("bucket"))
    }

    /**
     * An explicit endpoint gets path style, because virtual-host style needs wildcard DNS.
     *
     * `bucket.127.0.0.1:9000` does not resolve, and the failure reads as the store being down
     * rather than as a URL-style mistake.
     */
    @Test
    fun `an endpoint defaults to path style and AWS defaults to neither`() {
        assertEquals("path", RemoteLocation.defaultUrlStyle("127.0.0.1:9000"))
        assertNull(RemoteLocation.defaultUrlStyle(null))
        assertNull(RemoteLocation.defaultUrlStyle(""))
    }

    /** A trailing slash must not make one location look like two. */
    @Test
    fun `a trailing slash is not a different location`() {
        assertEquals(
            canonicalWorkspacePath("s3://warehouse/db"),
            canonicalWorkspacePath("s3://warehouse/db/"),
        )
    }

    /**
     * A remote URL never goes through `File.canonicalPath`.
     *
     * That resolves it against the working directory and turns `s3://warehouse/db` into
     * `<cwd>/s3:/warehouse/db` — which is not the location, would be stored instead of it, and
     * would never open anything.
     */
    @Test
    fun `canonicalising a remote path does not resolve it against the working directory`() {
        val canonical = canonicalWorkspacePath("s3://warehouse/db")
        assertEquals("s3://warehouse/db", canonical)
        assertTrue("s3:/warehouse" !in canonical.removePrefix("s3://"), canonical)
    }
}
