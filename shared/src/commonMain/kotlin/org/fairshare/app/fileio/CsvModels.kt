package org.fairshare.app.fileio

/** Limits may be lowered by a caller, never raised past the codec hard ceilings. Bytes mean UTF-8. */
data class CsvLimits(
    val maxBytes: Int = 8 * 1024 * 1024,
    val maxRows: Int = 100_000,
    val maxColumns: Int = 256,
    val maxFieldBytes: Int = 64 * 1024,
) {
    init {
        require(maxBytes in 1..8 * 1024 * 1024)
        require(maxRows in 1..100_000)
        require(maxColumns in 1..256)
        require(maxFieldBytes in 1..64 * 1024)
    }
}

enum class CsvErrorCode { MALFORMED_QUOTES, MALFORMED_UNICODE, BYTE_LIMIT, ROW_LIMIT, COLUMN_LIMIT, FIELD_LIMIT, EMPTY_RECORD }
/** Zero-based UTF-16 offset; one-based physical line/UTF-16 column. Writer also identifies record/field. */
data class CsvError(val code: CsvErrorCode, val offset: Int, val line: Int, val column: Int, val record: Int? = null, val field: Int? = null)
sealed interface CsvResult<out T> {
    data class Success<T>(val value: T) : CsvResult<T>
    data class Failure(val error: CsvError) : CsvResult<Nothing>
}
