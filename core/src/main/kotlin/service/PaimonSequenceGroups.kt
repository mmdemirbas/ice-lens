package service

import model.PaimonRowKind

/**
 * The part of `PartialUpdateMergeFunction` (Paimon 1.3.1) that decides whether a key survives
 * under `partial-update.remove-record-on-sequence-group`, folded here over a key's records in
 * sequence order because it is a scan with state and not an aggregate: `add` clears
 * `currentDeleteRow` on every record, `retractWithSequenceGroup` sets it when a `-D`'s value on
 * a named group's sequence field is at or above the row's — and resets the row, so the groups
 * start over — a `-U` there only moves the sequence field, an insert moves it when at or above,
 * and a record whose group fields are all null is skipped for that group. `getResult` answers
 * `DELETE` while `currentDeleteRow` holds or no insert was met at all. A record's value on a
 * group is compared to the row's the way the generated comparator does, nulls lowest.
 *
 * Only the named groups are folded: an unnamed group can retract columns and never the key,
 * and this decides keys, not the row's values.
 */
object PaimonSequenceGroups {

    /** One record of a key: its sequence number, kind, the file holding it, and its value on each named group's fields. */
    class Record(val sequence: Long, val kind: Int, val holder: String, val groups: List<List<Any?>>)

    /**
     * What the records left: the `-D`s that removed the key as they were met (the records before
     * each are dropped), whether the last record is one, and whether any insert was met.
     */
    class KeyFold(val removals: List<Record>, val removedNow: Boolean, val hasInsert: Boolean) {
        /** Not a row after the merge — removed by its last record, or never inserted. */
        val gone: Boolean get() = removedNow || !hasInsert
    }

    /**
     * Folds [records], which must be one key's in ascending sequence order; [notNull] says, per
     * named group and field, whether the column is declared `NOT NULL` — those are what `initRow`
     * copies from a removing `-D` into the row it resets to.
     */
    fun fold(records: List<Record>, notNull: List<List<Boolean>>): KeyFold {
        val groupCount = notNull.size
        val row = arrayOfNulls<List<Any?>>(groupCount)
        val removals = mutableListOf<Record>()
        var removedNow = false
        var hasInsert = false
        for (record in records) {
            removedNow = false
            val retraction = PaimonRowKind.isRetraction(record.kind)
            if (!retraction) hasInsert = true
            for (g in 0 until groupCount) {
                val value = record.groups[g]
                if (value.all { it == null }) continue
                val current = row[g]
                if (current != null && compare(value, current) < 0) continue
                if (retraction && record.kind == PaimonRowKind.DELETE) {
                    removedNow = true
                    removals += record
                    for (h in 0 until groupCount) {
                        val kept = record.groups[h].mapIndexed { i, v -> if (notNull[h][i]) v else null }
                        row[h] = if (kept.all { it == null }) null else kept
                    }
                    break
                }
                row[g] = value
            }
        }
        return KeyFold(removals, removedNow, hasInsert)
    }

    /** Lexicographic over the group's fields, a null below every value — the generated comparator's order. */
    private fun compare(a: List<Any?>, b: List<Any?>): Int {
        for (i in a.indices) {
            val x = a[i]
            val y = b.getOrNull(i)
            val c = when {
                x == null && y == null -> 0
                x == null -> -1
                y == null -> 1
                x.javaClass == y.javaClass && x is Comparable<*> -> @Suppress("UNCHECKED_CAST") (x as Comparable<Any>).compareTo(y)
                else -> x.toString().compareTo(y.toString())
            }
            if (c != 0) return c
        }
        return 0
    }
}
