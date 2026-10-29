package org.fairshare.app.fileio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CsvCodecTest {
    private fun parsed(s:String,limits:CsvLimits=CsvLimits()) = assertIs<CsvResult.Success<List<List<String>>>>(CsvCodec.parse(s,limits)).value
    private fun written(rows:List<List<String>>,limits:CsvLimits=CsvLimits()) = assertIs<CsvResult.Success<String>>(CsvCodec.write(rows,limits)).value
    private fun error(s:String,code:CsvErrorCode,limits:CsvLimits=CsvLimits()):CsvError {
        val result=assertIs<CsvResult.Failure>(CsvCodec.parse(s,limits));assertEquals(code,result.error.code);return result.error
    }
    @Test fun emptyAndBlankLinesHaveDistinctMeaning() {
        assertEquals(emptyList(),parsed(""));assertEquals(emptyList(),parsed("\uFEFF"))
        assertEquals(listOf(listOf("")),parsed("\n"));assertEquals(listOf(listOf("")),parsed("\r\n"))
        assertEquals(listOf(listOf(""),listOf("")),parsed("\n\n"))
        assertEquals(listOf(listOf("","")),parsed(","));assertEquals(listOf(listOf("a","")),parsed("a,\n"))
    }
    @Test fun deterministicWriterUsesCrlfAndUnambiguousEmptyFields() {
        assertEquals("",written(emptyList()))
        assertEquals("\"\"\r\n",written(listOf(listOf(""))))
        assertEquals("a,\"\",\"a,b\",\"say \"\"hi\"\"\"\r\n",written(listOf(listOf("a","","a,b","say \"hi\""))))
    }
    @Test fun lineEndingsOutsideAndInsideQuotesArePreservedCorrectly() {
        assertEquals(listOf(listOf("a","b"),listOf("c","d")),parsed("a,b\nc,d\r\n"))
        val rows=listOf(listOf("a\r\nb\nc\rd","x"))
        assertEquals(rows,parsed(written(rows)))
    }
    @Test fun strictMalformedQuotesAndBareCrNeverReturnPartialRecords() {
        for(s in listOf("a\"b","\"unterminated","\"a\"x","\"a\" \n","a\rb","a\r","ok\n\"bad"))
            error(s,CsvErrorCode.MALFORMED_QUOTES)
    }
    @Test fun quoteEscapingWorksAtFieldAndRecordEdges() {
        val rows=listOf(listOf("\"","\"\"","a\"b"),listOf("",",","\n"))
        assertEquals(rows,parsed(written(rows)))
        assertEquals(listOf(listOf("a\"b")),parsed("\"a\"\"b\""))
    }
    @Test fun bomOnlyAtFilePrefixIsSyntaxAndElsewhereData() {
        assertEquals(listOf(listOf("a")),parsed("\uFEFFa"))
        val rows=listOf(listOf("\uFEFFstart","mid\uFEFFvalue"),listOf("\uFEFF"))
        val out=written(rows);assertTrue(out.startsWith("\"\uFEFF"));assertEquals(rows,parsed(out))
        assertEquals(listOf(listOf("\uFEFFa")),parsed("\uFEFF\"\uFEFFa\""))
    }
    @Test fun preservesFormulaWhitespaceMoneyDatesAndUnicodeVerbatim() {
        val rows=listOf(listOf("=SUM(A1:A2)","+cmd","-42","@data","  spaced  ","001.20","2026/10/29","e\u0301","é","😀"))
        assertEquals(rows,parsed(written(rows)))
    }
    @Test fun errorsCarryPhysicalUtf16Positions() {
        val e=error("a\r\nb\"c",CsvErrorCode.MALFORMED_QUOTES)
        assertEquals(4,e.offset);assertEquals(2,e.line);assertEquals(2,e.column)
        val end=error("\"a\nb",CsvErrorCode.MALFORMED_QUOTES)
        assertEquals(4,end.offset);assertEquals(2,end.line);assertEquals(2,end.column)
    }
    @Test fun malformedUnicodeNeverReplaced() {
        for(s in listOf("\uD800","\uDC00","a\uD800x","\"\uDC00\"")) {
            error(s,CsvErrorCode.MALFORMED_UNICODE)
            val result=assertIs<CsvResult.Failure>(CsvCodec.write(listOf(listOf(s))))
            assertEquals(CsvErrorCode.MALFORMED_UNICODE,result.error.code)
        }
    }
    @Test fun inputByteLimitCountsUtf8NotCharactersIncludingBom() {
        assertEquals(listOf(listOf("é")),parsed("é",CsvLimits(maxBytes=2)))
        error("éé",CsvErrorCode.BYTE_LIMIT,CsvLimits(maxBytes=3))
        assertEquals(listOf(listOf("😀")),parsed("😀",CsvLimits(maxBytes=4)))
        error("\uFEFFa",CsvErrorCode.BYTE_LIMIT,CsvLimits(maxBytes=3))
    }
    @Test fun outputByteLimitIncludesQuotesEscapesSeparatorsAndTerminators() {
        val rows=listOf(listOf("é",""))
        assertEquals("é,\"\"\r\n",written(rows,CsvLimits(maxBytes=7)))
        assertEquals(CsvErrorCode.BYTE_LIMIT,assertIs<CsvResult.Failure>(CsvCodec.write(rows,CsvLimits(maxBytes=6))).error.code)
        assertEquals("\"\"\"\"\r\n",written(listOf(listOf("\"")),CsvLimits(maxBytes=6)))
    }
    @Test fun fieldLimitsCountDecodedUtf8AndDoubledQuotesOnce() {
        assertEquals(listOf(listOf("é")),parsed("\"é\"",CsvLimits(maxFieldBytes=2)))
        error("\"éé\"",CsvErrorCode.FIELD_LIMIT,CsvLimits(maxFieldBytes=3))
        assertEquals(listOf(listOf("\"")),parsed("\"\"\"\"",CsvLimits(maxFieldBytes=1)))
        assertEquals(CsvErrorCode.FIELD_LIMIT,assertIs<CsvResult.Failure>(CsvCodec.write(listOf(listOf("😀")),CsvLimits(maxFieldBytes=3))).error.code)
    }
    @Test fun rowAndColumnLimitsAreCheckedAtBoundaries() {
        assertEquals(listOf(listOf("a")),parsed("a\n",CsvLimits(maxRows=1)))
        error("a\nb",CsvErrorCode.ROW_LIMIT,CsvLimits(maxRows=1))
        error("a,",CsvErrorCode.COLUMN_LIMIT,CsvLimits(maxColumns=1))
        assertEquals(CsvErrorCode.ROW_LIMIT,assertIs<CsvResult.Failure>(CsvCodec.write(listOf(listOf("a"),listOf("b")),CsvLimits(maxRows=1))).error.code)
        assertEquals(CsvErrorCode.COLUMN_LIMIT,assertIs<CsvResult.Failure>(CsvCodec.write(listOf(listOf("a","b")),CsvLimits(maxColumns=1))).error.code)
    }
    @Test fun zeroColumnRecordsAreRejectedRatherThanSilentlyDropped() {
        val result=assertIs<CsvResult.Failure>(CsvCodec.write(listOf(listOf("valid"),emptyList())))
        assertEquals(CsvErrorCode.EMPTY_RECORD,result.error.code);assertEquals(2,result.error.record)
    }
    @Test fun actualHardFieldAndColumnBoundaries() {
        val max="x".repeat(65536)
        assertEquals(listOf(listOf(max)),parsed(max))
        error(max+"x",CsvErrorCode.FIELD_LIMIT)
        val row=List(256){""};assertEquals(listOf(row),parsed(written(listOf(row))))
        error(",".repeat(256),CsvErrorCode.COLUMN_LIMIT)
    }
    @Test fun generatedAdversarialRoundtrips() {
        val alphabet=listOf("","plain",",","\"","\r","\n","\r\n","\uFEFF","é","😀","=1","a\"b,c\n")
        for(seed in 0..99) {
            val rows=List(1+seed%7){r->List(1+(seed+r)%9){c->alphabet[(seed+r*3+c*7)%alphabet.size]}}
            assertEquals(rows,parsed(written(rows)))
            assertEquals(written(rows),written(parsed(written(rows))))
        }
    }
    @Test fun limitsCannotExceedHardCeilings() {
        for(make in listOf<()->CsvLimits>({CsvLimits(maxBytes=8388609)},{CsvLimits(maxRows=100001)},
            {CsvLimits(maxColumns=257)},{CsvLimits(maxFieldBytes=65537)},{CsvLimits(maxRows=0)})) {
            kotlin.test.assertFailsWith<IllegalArgumentException>{make()}
        }
    }
}
