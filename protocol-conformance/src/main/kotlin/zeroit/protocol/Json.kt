package zeroit.protocol

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.math.BigInteger
import java.security.MessageDigest

/** Lossless JSON tree for the closed, integer-only accepted wire profile. */
sealed interface JValue
class JObject(fields: Map<String, JValue>) : JValue {
    val fields: Map<String,JValue> = java.util.Collections.unmodifiableMap(LinkedHashMap(fields))
    override fun equals(other: Any?) = other is JObject && fields == other.fields
    override fun hashCode() = fields.hashCode()
    override fun toString() = "JObject(<redacted>)"
}
class JArray(values: List<JValue>) : JValue {
    val values: List<JValue> = java.util.Collections.unmodifiableList(ArrayList(values))
    override fun equals(other: Any?) = other is JArray && values == other.values
    override fun hashCode() = values.hashCode()
    override fun toString() = "JArray(<redacted>)"
}
data class JString(val value: String) : JValue
data class JNumber(val lexical: String) : JValue
data class JBool(val value: Boolean) : JValue
data object JNull : JValue
fun JValue.obj() = (this as? JObject)?.fields ?: throw IllegalArgumentException("Expected object")
fun JValue.arr() = (this as? JArray)?.values ?: throw IllegalArgumentException("Expected array")
fun JValue.str() = (this as? JString)?.value ?: throw IllegalArgumentException("Expected string")
fun JValue.bool() = (this as? JBool)?.value ?: throw IllegalArgumentException("Expected boolean")
fun JValue.int() = (this as? JNumber)?.lexical?.toIntOrNull() ?: throw IllegalArgumentException("Expected bounded integer")

object StrictJson {
    const val MAX_BYTES = 1024 * 1024
    fun parse(bytes: ByteArray): JValue {
        require(bytes.size <= MAX_BYTES) { "JSON byte limit exceeded" }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
            catch (e: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("Invalid UTF-8", e) }
        return parse(text)
    }
    fun parse(text: String): JValue {
        validUnicode(text)
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "JSON byte limit exceeded" }
        return Parser(text).parse()
    }
    internal fun validUnicode(text: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (c.isHighSurrogate()) require(i < text.length && text[i++].isLowSurrogate()) { "Lone surrogate" }
            else require(!c.isLowSurrogate()) { "Lone surrogate" }
        }
    }
    private class Parser(val text: String) {
        var at = 0
        fun parse(): JValue { val result = value(0); ws(); require(at == text.length) { "Trailing JSON data" }; return result }
        fun ws() { while (at < text.length && text[at] in " \r\n\t") at++ }
        fun value(depth: Int): JValue {
            require(depth <= 64) { "JSON depth limit" }; ws(); require(at < text.length) { "Unexpected JSON end" }
            return when (text[at]) {
                '{' -> {
                    at++; ws(); val fields = linkedMapOf<String,JValue>()
                    if (!take('}')) {
                        do {
                            ws(); require(at < text.length && text[at] == '"') { "Object key required" }
                            val key = string(); ws(); require(take(':')) { "Colon required" }
                            require(!fields.containsKey(key)) { "Duplicate JSON key" }
                            fields[key] = value(depth + 1); ws()
                            if (take('}')) break
                            require(take(',')) { "Comma required" }
                        } while (true)
                    }
                    JObject(fields.toMap())
                }
                '[' -> {
                    at++; ws(); val items = mutableListOf<JValue>()
                    if (!take(']')) {
                        do { items += value(depth + 1); ws(); if (take(']')) break; require(take(',')) { "Comma required" } } while (true)
                    }
                    JArray(items.toList())
                }
                '"' -> JString(string())
                't' -> { literal("true"); JBool(true) }
                'f' -> { literal("false"); JBool(false) }
                'n' -> { literal("null"); JNull }
                else -> number()
            }
        }
        fun take(c: Char): Boolean = if (at < text.length && text[at] == c) { at++; true } else false
        fun literal(s: String) { require(text.startsWith(s, at)) { "Invalid literal" }; at += s.length }
        fun string(): String {
            require(take('"')); val out = StringBuilder(); var closed = false
            while (at < text.length) {
                val c = text[at++]
                if (c == '"') { closed = true; break }
                require(c.code >= 0x20) { "Unescaped control" }
                if (c != '\\') out.append(c) else {
                    require(at < text.length) { "Truncated escape" }
                    when (val e = text[at++]) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b'); 'f' -> out.append('\u000c'); 'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                        'u' -> {
                            require(at + 4 <= text.length) { "Truncated Unicode escape" }
                            val hex = text.substring(at, at + 4)
                            require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid Unicode escape" }
                            out.append(hex.toInt(16).toChar()); at += 4
                        }
                        else -> throw IllegalArgumentException("Invalid string escape")
                    }
                }
            }
            require(closed) { "Unterminated string" }; val result = out.toString(); validUnicode(result); return result
        }
        fun number(): JNumber {
            val start = at
            if (take('-')) require(at < text.length)
            require(at < text.length && text[at] in '0'..'9') { "Invalid number" }
            if (text[at] == '0') at++ else while (at < text.length && text[at] in '0'..'9') at++
            require(at == text.length || text[at] in " \n\r\t,]}") { "Noninteger or malformed number" }
            val raw = text.substring(start, at); require(raw != "-0") { "Noncanonical integer" }
            require(raw.removePrefix("-").length <= 16) { "Unsafe JSON integer" }
            require(BigInteger(raw).abs() <= BigInteger("9007199254740991")) { "Unsafe JSON integer" }
            return JNumber(raw)
        }
    }
}

/** RFC8785-compatible only for the accepted closed ASCII-key/integer profile. */
object Canonical {
    fun sha256(value: JValue): String = digest(bytes(value))
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun bytes(value: JValue): ByteArray = buildString { appendValue(value) }.toByteArray(Charsets.UTF_8)
    private fun StringBuilder.appendValue(v: JValue) {
        when (v) {
            is JObject -> {
                require(v.fields.keys.all { key -> key.all { it.code <= 127 } }) { "Non-ASCII property outside accepted profile" }
                append('{'); v.fields.toSortedMap().entries.forEachIndexed { i, (k,item) -> if (i > 0) append(','); quoted(k); append(':'); appendValue(item) }; append('}')
            }
            is JArray -> { append('['); v.values.forEachIndexed { i,item -> if (i > 0) append(','); appendValue(item) }; append(']') }
            is JString -> quoted(v.value)
            is JNumber -> {
                require(Regex("-?(0|[1-9][0-9]*)").matches(v.lexical) && v.lexical != "-0") { "Noninteger profile" }
                require(v.lexical.removePrefix("-").length <= 16) { "Unsafe JSON integer" }
                require(BigInteger(v.lexical).abs() <= BigInteger("9007199254740991")) { "Unsafe JSON integer" }; append(v.lexical)
            }
            is JBool -> append(v.value)
            JNull -> append("null")
        }
    }
    private fun StringBuilder.quoted(text: String) {
        StrictJson.validUnicode(text); append('"')
        for (c in text) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\b' -> append("\\b"); '\u000c' -> append("\\f"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c.code < 32) append("\\u" + c.code.toString(16).padStart(4,'0')) else append(c)
        }
        append('"')
    }
}
