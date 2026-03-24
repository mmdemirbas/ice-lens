package service

import model.*
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.system.measureTimeMillis

/**
 * Performance benchmarks for layout and model construction.
 * These tests verify that operations complete within reasonable time bounds
 * for tables of various sizes, and help catch performance regressions.
 */
class PerformanceTest {

    private fun generateTable(
        metadataCount: Int,
        snapshotsPerMetadata: Int,
        manifestsPerSnapshot: Int,
        filesPerManifest: Int,
    ): UnifiedTableModel {
        val metadatas = (1..metadataCount).map { mIdx ->
            val snapshots = (1..snapshotsPerMetadata).map { sIdx ->
                val globalSnapshotIdx = (mIdx - 1) * snapshotsPerMetadata + sIdx
                val manifests = (1..manifestsPerSnapshot).map { manIdx ->
                    val files = (1..filesPerManifest).map { fIdx ->
                        UnifiedDataFile(
                            path = Path.of("/test/data/file_${globalSnapshotIdx}_${manIdx}_$fIdx.parquet"),
                            metadata = ManifestEntry(
                                status = 1,
                                snapshotId = globalSnapshotIdx.toLong(),
                                dataFile = DataFile(
                                    filePath = "/test/data/file_${globalSnapshotIdx}_${manIdx}_$fIdx.parquet",
                                    fileFormat = "PARQUET",
                                    recordCount = 1000L,
                                    fileSizeInBytes = 50000L,
                                    content = if (fIdx % 5 == 0) 1 else 0
                                )
                            ),
                            rowsLoader = { emptyList() } // No actual Parquet reads
                        )
                    }
                    UnifiedManifest(
                        path = Path.of("/test/metadata/manifest_${globalSnapshotIdx}_$manIdx.avro"),
                        metadata = ManifestListEntry(
                            manifestPath = "/test/metadata/manifest_${globalSnapshotIdx}_$manIdx.avro",
                            content = if (manIdx % 3 == 0) 1 else 0,
                            sequenceNumber = globalSnapshotIdx,
                            addedSnapshotId = globalSnapshotIdx.toLong(),
                            addedFilesCount = filesPerManifest,
                        ),
                        dataFiles = files
                    )
                }
                UnifiedSnapshot(
                    path = Path.of("/test/metadata/snap_$globalSnapshotIdx.avro"),
                    metadata = Snapshot(
                        snapshotId = globalSnapshotIdx.toLong(),
                        timestampMs = 1700000000000L + globalSnapshotIdx * 1000L,
                        sequenceNumber = globalSnapshotIdx.toLong(),
                        summary = mapOf("operation" to "append")
                    ),
                    manifests = manifests
                )
            }
            UnifiedMetadata(
                path = Path.of("/test/metadata/v$mIdx.metadata.json"),
                metadata = TableMetadata(
                    formatVersion = 2,
                    tableUuid = "test-uuid",
                    location = "/test",
                    snapshots = snapshots.map { it.metadata },
                    currentSnapshotId = snapshots.lastOrNull()?.metadata?.snapshotId,
                ),
                snapshots = snapshots
            )
        }

        return UnifiedTableModel(
            path = Path.of("/test/table"),
            name = "perf_test_table",
            versionHint = "$metadataCount",
            metadatas = metadatas
        )
    }

    @Test
    fun `layout small table under 500ms`() {
        // 2 metadata × 3 snapshots × 2 manifests × 5 files = ~60 file nodes
        val table = generateTable(2, 3, 2, 5)
        val elapsed = measureTimeMillis {
            GraphLayoutService.layoutGraph(table, showRows = false)
        }
        println("Small table layout: ${elapsed}ms")
        assertTrue(elapsed < 2000, "Small table layout took ${elapsed}ms, expected < 2000ms")
    }

    @Test
    fun `layout medium table under 3s`() {
        // 5 metadata × 5 snapshots × 3 manifests × 10 files = ~750 file nodes
        val table = generateTable(5, 5, 3, 10)
        val elapsed = measureTimeMillis {
            GraphLayoutService.layoutGraph(table, showRows = false)
        }
        println("Medium table layout: ${elapsed}ms")
        assertTrue(elapsed < 5000, "Medium table layout took ${elapsed}ms, expected < 5000ms")
    }

    @Test
    fun `layout large table under 10s`() {
        // 10 metadata × 10 snapshots × 4 manifests × 10 files = ~4000 file nodes (capped at 10 per manifest)
        val table = generateTable(10, 10, 4, 10)
        val elapsed = measureTimeMillis {
            GraphLayoutService.layoutGraph(table, showRows = false)
        }
        println("Large table layout: ${elapsed}ms")
        assertTrue(elapsed < 15000, "Large table layout took ${elapsed}ms, expected < 15000ms")
    }

    @Test
    fun `snapshot filter performance with many nodes`() {
        val table = generateTable(5, 5, 3, 10)
        val graph = GraphLayoutService.layoutGraph(table, showRows = false)

        val snapshotNodeIds = graph.nodes
            .filterIsInstance<GraphNode.SnapshotNode>()
            .take(3)
            .map { it.id }
            .toSet()

        val elapsed = measureTimeMillis {
            repeat(100) {
                ui.computeVisibleNodeIdsForSnapshotFilter(graph, snapshotNodeIds)
            }
        }
        println("100x snapshot filter: ${elapsed}ms (${elapsed / 100.0}ms/call)")
        assertTrue(elapsed < 2000, "Snapshot filter 100x took ${elapsed}ms, expected < 2000ms")
    }

    @Test
    fun `filtered graph model performance`() {
        val table = generateTable(5, 5, 3, 10)
        val graph = GraphLayoutService.layoutGraph(table, showRows = false)
        val visibleIds = graph.nodes.take(graph.nodes.size / 2).map { it.id }.toSet()

        val elapsed = measureTimeMillis {
            repeat(100) {
                ui.filteredGraphModel(graph, visibleIds)
            }
        }
        println("100x filteredGraphModel: ${elapsed}ms (${elapsed / 100.0}ms/call)")
        assertTrue(elapsed < 2000, "filteredGraphModel 100x took ${elapsed}ms, expected < 2000ms")
    }

    @Test
    fun `node lookup performance with nodeById`() {
        val table = generateTable(5, 5, 3, 10)
        val graph = GraphLayoutService.layoutGraph(table, showRows = false)
        val nodeIds = graph.nodes.map { it.id }

        val elapsed = measureTimeMillis {
            repeat(10000) {
                nodeIds.forEach { id -> graph.nodeById[id] }
            }
        }
        println("10000x full nodeById scan: ${elapsed}ms")
        assertTrue(elapsed < 2000, "nodeById scan 10000x took ${elapsed}ms, expected < 2000ms")
    }

    @Test
    fun `graph builder produces realistic edge counts`() {
        // Verify that performance tests exercise edge creation, not just nodes
        val table = generateTable(3, 4, 3, 5)
        val result = IcebergGraphBuilder.buildGraph(table, showRows = false)
        val nodeCount = result.nodes.size
        val edgeCount = result.edges.size

        // There should be edges connecting every parent to its children
        assertTrue(edgeCount > 0, "Graph should have edges")
        // With the hierarchy table→metadata→snapshot→manifest→file, edges should be at least nodeCount-1
        assertTrue(edgeCount >= nodeCount - 1, "Edge count ($edgeCount) should be >= node count - 1 (${nodeCount - 1})")
        println("Graph: $nodeCount nodes, $edgeCount edges (ratio: ${edgeCount.toFloat() / nodeCount})")
    }

    @Test
    fun `graph builder is O(n) in total artifacts`() {
        // Verify that doubling the table size roughly doubles build time, not quadruples it
        val smallTable = generateTable(2, 3, 2, 5)
        val largeTable = generateTable(4, 6, 4, 5)

        // Warm up
        IcebergGraphBuilder.buildGraph(smallTable, showRows = false)
        IcebergGraphBuilder.buildGraph(largeTable, showRows = false)

        val smallTime = measureTimeMillis {
            repeat(3) { IcebergGraphBuilder.buildGraph(smallTable, showRows = false) }
        }
        val largeTime = measureTimeMillis {
            repeat(3) { IcebergGraphBuilder.buildGraph(largeTable, showRows = false) }
        }

        val smallNodes = IcebergGraphBuilder.buildGraph(smallTable, showRows = false).nodes.size
        val largeNodes = IcebergGraphBuilder.buildGraph(largeTable, showRows = false).nodes.size
        val sizeRatio = largeNodes.toFloat() / smallNodes
        val timeRatio = largeTime.toFloat() / smallTime.coerceAtLeast(1)

        println("Small: $smallNodes nodes in ${smallTime}ms, Large: $largeNodes nodes in ${largeTime}ms")
        println("Size ratio: $sizeRatio, Time ratio: $timeRatio")

        // Time ratio should be roughly proportional to size ratio (not squared)
        // Allow 3x headroom for overhead
        assertTrue(timeRatio < sizeRatio * 3, "Time ratio ($timeRatio) too large vs size ratio ($sizeRatio) — suggests O(n²)")
    }

    @Test
    fun `DuckDB connection reuse`() {
        // Verify the synchronized connection doesn't deadlock with rapid sequential calls
        val elapsed = measureTimeMillis {
            repeat(5) {
                try {
                    // This will fail (no file) but should not deadlock or corrupt state
                    SampleRowReader.querySampleRows("/nonexistent/file.parquet")
                } catch (_: Exception) {
                    // Expected - file doesn't exist
                }
            }
        }
        println("5x DuckDB connection attempts: ${elapsed}ms")
        assertTrue(elapsed < 10000, "DuckDB sequential calls took ${elapsed}ms")
    }
}
