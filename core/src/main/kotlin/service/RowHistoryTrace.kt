package service

import model.LookupInput
import model.PaimonReadInput
import model.RowHistory
import model.RowHistoryInputs
import model.RowHistoryStep
import model.RowLookupInput
import model.ScanFilter

/**
 * The row lookup run at each snapshot of a [RowHistoryInputs], newest first — see
 * [model.RowHistory]. One lookup per snapshot, under the same filter and the same files the
 * drawn graph ruled out; on Iceberg every snapshot is read under the newest schema, so a data
 * file's matching rows are the same at every snapshot that lists it and are read once
 * ([RowLookup.lookup]'s `reads`). Paimon reads a file under its snapshot's own schema, which
 * an `ADD COLUMN` changes between two of them, so it reads per snapshot.
 */
object RowHistoryTrace {
    fun trace(inputs: RowHistoryInputs, filter: ScanFilter, ruledOut: Set<String>): RowHistory {
        val reads = mutableMapOf<String, Result<List<Map<String, Any?>>>>()
        val steps = inputs.snapshots.map { snapshot ->
            RowHistoryStep(snapshot, lookupAt(snapshot.input, filter, ruledOut, reads))
        }
        return RowHistory(steps, inputs.onMain)
    }

    private fun lookupAt(input: LookupInput, filter: ScanFilter, ruledOut: Set<String>, reads: MutableMap<String, Result<List<Map<String, Any?>>>>) =
        when (input) {
            is RowLookupInput -> RowLookup.lookup(input, filter, ruledOut, reads)
            is PaimonReadInput -> PaimonRowLookup.lookup(input, filter, ruledOut)
        }
}
