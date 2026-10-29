package org.fairshare.app.fileio

/**
 * Lossless string codec, not an expense/import schema or spreadsheet-safe rendering policy.
 * Comma delimiter; CRLF/LF records; CR/LF inside quotes preserved. Bare CR outside quotes is invalid.
 * Only the first U+FEFF is a file BOM. Writer quotes a leading data U+FEFF to preserve it.
 * Empty input = zero records; blank line = one empty field. Empty fields are ALWAYS written as "".
 * Writer terminates every nonempty record with CRLF; zero-column records are rejected.
 * No money/date/Unicode/formula normalization. Malformed surrogate sequences fail, never replace.
 * All failures are atomic results with no partial records/output. Supplied String/List already exists;
 * limits bound this codec's additional allocations, not the caller's upstream file decoding.
 */
object CsvCodec {
    fun parse(input: String, limits: CsvLimits = CsvLimits()): CsvResult<List<List<String>>> {
        return try { Parser(input, limits).parse() } catch (bad: InvalidCsv) { CsvResult.Failure(bad.error) }
    }

    fun write(records: List<List<String>>, limits: CsvLimits = CsvLimits()): CsvResult<String> {
        var total = 0L
        fun failure(code: CsvErrorCode, row: Int, field: Int, offset: Int = 0) =
            CsvResult.Failure(CsvError(code, offset, row + 1, offset + 1, row + 1, field + 1))
        if (records.size > limits.maxRows) return failure(CsvErrorCode.ROW_LIMIT, limits.maxRows, 0)
        // Preflight complete output size before allocating its builder or copying any fields.
        for ((r, row) in records.withIndex()) {
            if (row.isEmpty()) return failure(CsvErrorCode.EMPTY_RECORD,r,0)
            if (row.size > limits.maxColumns) return failure(CsvErrorCode.COLUMN_LIMIT,r,limits.maxColumns)
            for ((c, field) in row.withIndex()) {
                var bytes = 0L; var quotes = 0; var i = 0
                while (i < field.length) {
                    val count = utf8Width(field,i)
                    if (count == 0) return failure(CsvErrorCode.MALFORMED_UNICODE,r,c,i)
                    bytes += count
                    if (bytes > limits.maxFieldBytes) return failure(CsvErrorCode.FIELD_LIMIT,r,c,i)
                    if (field[i] == '"') quotes++
                    i += if (count == 4) 2 else 1
                }
                val quoted = needsQuotes(field,r,c)
                total += bytes + (if (quoted) quotes.toLong()+2 else 0) + (if (c>0) 1 else 0)
                if (total > limits.maxBytes) return failure(CsvErrorCode.BYTE_LIMIT,r,c)
            }
            total += 2 // CRLF
            if (total > limits.maxBytes) return failure(CsvErrorCode.BYTE_LIMIT,r,row.lastIndex)
        }
        val out = StringBuilder(total.toInt())
        for ((r,row) in records.withIndex()) {
            for ((c,field) in row.withIndex()) {
                if(c>0) out.append(',')
                if(needsQuotes(field,r,c)) {
                    out.append('"'); for(ch in field) {out.append(ch);if(ch=='"')out.append('"')};out.append('"')
                } else out.append(field)
            }
            out.append("\r\n")
        }
        return CsvResult.Success(out.toString())
    }

    private fun needsQuotes(s:String,r:Int,c:Int) = s.isEmpty() || s.any {it==','||it=='"'||it=='\r'||it=='\n'} || (r==0&&c==0&&s.startsWith('\uFEFF'))
    /** 0 = malformed scalar. */
    private fun utf8Width(s:String,i:Int):Int {
        val c=s[i]
        return when {
            c.isHighSurrogate() -> if(i+1<s.length&&s[i+1].isLowSurrogate())4 else 0
            c.isLowSurrogate()->0
            c.code<128->1
            c.code<2048->2
            else->3
        }
    }
    private class InvalidCsv(val error:CsvError):Exception()
    private class Parser(val input:String,val limits:CsvLimits) {
        var pos=0;var line=1;var column=1
        fun fail(code:CsvErrorCode):Nothing=throw InvalidCsv(CsvError(code,pos,line,column))
        fun advance() {
            val ch=input[pos++]
            if(ch=='\n') {line++;column=1} else column++
        }
        fun parse():CsvResult<List<List<String>>> {
            // Validate/count scalar bytes without encoding/copying the untrusted input.
            var bytes=0L;var i=0
            while(i<input.length) {
                val n=utf8Width(input,i)
                if(n==0||bytes+n>limits.maxBytes) {
                    while(pos<i)advance()
                    fail(if(n==0)CsvErrorCode.MALFORMED_UNICODE else CsvErrorCode.BYTE_LIMIT)
                }
                bytes+=n;i+=if(n==4)2 else 1
            }
            if(input.startsWith('\uFEFF'))advance()
            val rows=mutableListOf<List<String>>()
            while(pos<input.length) {
                if(rows.size>=limits.maxRows)fail(CsvErrorCode.ROW_LIMIT)
                val row=mutableListOf<String>()
                do {
                    if(row.size>=limits.maxColumns)fail(CsvErrorCode.COLUMN_LIMIT)
                    val value=StringBuilder();var fieldBytes=0
                    fun appendScalar() {
                        val n=utf8Width(input,pos)
                        if(fieldBytes+n>limits.maxFieldBytes)fail(CsvErrorCode.FIELD_LIMIT)
                        fieldBytes+=n
                        value.append(input[pos]);advance()
                        if(n==4){value.append(input[pos]);advance()}
                    }
                    if(pos<input.length&&input[pos]=='"') {
                        advance();var closed=false
                        while(pos<input.length) {
                            if(input[pos]=='"') {
                                advance()
                                if(pos<input.length&&input[pos]=='"') {
                                    if(++fieldBytes>limits.maxFieldBytes)fail(CsvErrorCode.FIELD_LIMIT)
                                    value.append('"');advance()
                                } else {closed=true;break}
                            } else appendScalar()
                        }
                        if(!closed)fail(CsvErrorCode.MALFORMED_QUOTES)
                        if(pos<input.length&&input[pos]!=','&&input[pos]!='\r'&&input[pos]!='\n')fail(CsvErrorCode.MALFORMED_QUOTES)
                    } else {
                        while(pos<input.length&&input[pos]!=','&&input[pos]!='\r'&&input[pos]!='\n') {
                            if(input[pos]=='"')fail(CsvErrorCode.MALFORMED_QUOTES)
                            appendScalar()
                        }
                    }
                    row+=value.toString()
                    if(pos<input.length&&input[pos]==','){advance();continue}
                    break
                } while(true)
                rows+=row
                if(pos<input.length) {
                    if(input[pos]=='\r') {advance();if(pos>=input.length||input[pos]!='\n')fail(CsvErrorCode.MALFORMED_QUOTES)}
                    if(input[pos]!='\n')fail(CsvErrorCode.MALFORMED_QUOTES)
                    advance()
                }
            }
            return CsvResult.Success(rows)
        }
    }
}
