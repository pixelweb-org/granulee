package io.granulee.sql

/** Column types and codecs as system.columns words them. */
object Types {
    fun isNullable(type: String) = type.startsWith("Nullable(") || type.startsWith("LowCardinality(Nullable(")
    fun isLowCardinality(type: String) = type.startsWith("LowCardinality(")

    /** The type without Nullable / LowCardinality wrappers. */
    fun base(type: String): String {
        var t = type
        while (true) {
            t = when {
                t.startsWith("Nullable(") && t.endsWith(")") -> t.substring(9, t.length - 1)
                t.startsWith("LowCardinality(") && t.endsWith(")") -> t.substring(15, t.length - 1)
                else -> return t
            }
        }
    }

    fun isString(type: String) = base(type) == "String"
    fun isTime(type: String) = base(type).let { it == "Date" || it == "Date32" || it.startsWith("DateTime") }
    fun isInteger(type: String) = Regex("^U?Int(8|16|32|64)$").matches(base(type))
    fun isFloat(type: String) = base(type).let { it == "Float32" || it == "Float64" }

    /**
     * A JSON, Dynamic or Variant column is stored as one set of files per path, easily thousands per wide part, and
     * every reading or writing thread opens its own.
     */
    fun hasDynamicSubcolumns(type: String): Boolean =
        Regex("\\b(JSON|Dynamic|Variant)\\b").containsMatchIn(type.replace(Regex("'(?:[^'\\\\]|\\\\.)*'"), ""))

    /** "CODEC(ZSTD(1))" -> "ZSTD(1)"; "" stays "". */
    fun codecBody(codec: String): String =
        if (codec.startsWith("CODEC(") && codec.endsWith(")")) codec.substring(6, codec.length - 1) else codec

    fun codecLabel(codec: String): String = codecBody(codec).ifEmpty { "LZ4 (default)" }
}
