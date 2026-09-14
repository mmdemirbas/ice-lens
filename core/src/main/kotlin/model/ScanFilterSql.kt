package model

/**
 * A [ScanFilter] as the `WHERE` clause DuckDB evaluates over a data file's rows, every literal
 * bound as a parameter — the same filter the pruning rules read against bounds, read against
 * the values, which is how a row is found rather than a file. [params] are the literals in
 * placeholder order, as the reader typed them.
 */
data class SqlPredicate(val sql: String, val params: List<String>)

/** DuckDB's spelling of an Iceberg primitive, for the `CAST` a bound literal needs to compare with a typed column; null for a type with none. */
fun duckDbTypeOf(type: IcebergType): String? = when (type) {
    IcebergType.BooleanType -> "BOOLEAN"
    IcebergType.IntType -> "INTEGER"
    IcebergType.LongType -> "BIGINT"
    IcebergType.FloatType -> "FLOAT"
    IcebergType.DoubleType -> "DOUBLE"
    IcebergType.DateType -> "DATE"
    IcebergType.StringType -> "VARCHAR"
    IcebergType.UuidType -> "UUID"
    is IcebergType.DecimalType -> "DECIMAL(${type.precision}, ${type.scale})"
    is IcebergType.TimestampType -> if (type.withZone) "TIMESTAMPTZ" else "TIMESTAMP"
    is IcebergType.TimeType -> "TIME"
    else -> null
}

/** `"name"`, with an embedded quote doubled — DuckDB's identifier quoting. */
fun quoteSqlIdentifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

/**
 * A column reference for DuckDB: a nested leaf named by its path (`addr.town`) is the struct
 * column and then the field, each quoted on its own — quoted whole, the dotted name is one
 * identifier no file has.
 */
fun quoteSqlColumnPath(path: String): String = path.split('.').joinToString(".", transform = ::quoteSqlIdentifier)

/**
 * Renders the filter for DuckDB. A literal is bound as text and cast to the column's type
 * where [typeOf] knows it — DuckDB compares a typed column with a text parameter only through
 * a cast, and `'2024-03-05'` is a date to one column and a string to another, which is the
 * parser's reason for keeping literals as text in the first place. `LIKE` casts the column to
 * text instead, since a pattern is text whatever the column is.
 */
fun ScanFilter.toSql(typeOf: (String) -> IcebergType?): SqlPredicate {
    val params = mutableListOf<String>()
    fun leaf(p: ScanPredicate): String {
        val column = quoteSqlColumnPath(p.column)
        val cast = typeOf(p.column)?.let(::duckDbTypeOf)
        fun bound(): String {
            params += p.literal
            return if (cast == null) "?" else "CAST(? AS $cast)"
        }
        return when (p.op) {
            PredicateOp.EQ -> "$column = ${bound()}"
            PredicateOp.NOT_EQ -> "$column <> ${bound()}"
            PredicateOp.LT -> "$column < ${bound()}"
            PredicateOp.LTE -> "$column <= ${bound()}"
            PredicateOp.GT -> "$column > ${bound()}"
            PredicateOp.GTE -> "$column >= ${bound()}"
            PredicateOp.IS_NULL -> "$column IS NULL"
            PredicateOp.IS_NOT_NULL -> "$column IS NOT NULL"
            PredicateOp.LIKE -> { params += p.literal; "CAST($column AS VARCHAR) LIKE ?" }
            PredicateOp.NOT_LIKE -> { params += p.literal; "CAST($column AS VARCHAR) NOT LIKE ?" }
        }
    }
    fun render(f: ScanFilter): String = when (f) {
        is ScanFilter.Term -> leaf(f.predicate)
        is ScanFilter.And -> if (f.terms.isEmpty()) "TRUE" else f.terms.joinToString(" AND ") { "(${render(it)})" }
        is ScanFilter.Or -> if (f.terms.isEmpty()) "FALSE" else f.terms.joinToString(" OR ") { "(${render(it)})" }
        is ScanFilter.Not -> "NOT (${render(f.term)})"
    }
    return SqlPredicate(render(this), params)
}
