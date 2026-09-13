package model

import kotlin.test.Test
import kotlin.test.assertTrue

class FixtureCatalogTest {
    /** A fixture added to `example/` is in every sweep from then on; a list that shrank would mean a table was deleted. */
    @Test
    fun `the catalogue lists every checked-in table of both formats`() {
        assertTrue(FixtureCatalog.iceberg.size >= 28, FixtureCatalog.iceberg.toString())
        assertTrue(FixtureCatalog.paimon.size >= 26, FixtureCatalog.paimon.toString())
        assertTrue("mor" in FixtureCatalog.iceberg && "sgd" in FixtureCatalog.paimon)
        assertTrue(FixtureCatalog.iceberg.all { FixtureCatalog.icebergDir(it).isDirectory } && FixtureCatalog.paimon.all { FixtureCatalog.paimonDir(it).isDirectory })
    }
}
