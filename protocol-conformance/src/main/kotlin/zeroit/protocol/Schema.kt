package zeroit.protocol

import java.math.BigInteger
import java.time.LocalDateTime
import java.util.regex.Pattern

/** Evaluates exactly the vocabulary used by the accepted schema, not arbitrary JSON Schema. */
class SchemaValidator(private val schema: JValue) {
    private val defs = schema.obj().getValue("\$defs").obj()
    private val supported = setOf("\$ref", "\$schema", "\$id", "\$defs", "title", "type", "properties", "required", "additionalProperties", "oneOf", "anyOf", "allOf", "if", "then", "enum", "const", "minLength", "maxLength", "pattern", "format", "items", "minItems", "maxItems", "uniqueItems", "minimum", "maximum")
    init { checkVocabulary(schema) }
    private fun checkVocabulary(node: JValue) {
        val fields = node.obj(); require(fields.keys.all { it in supported }) { "Unsupported schema keyword" }
        for ((key,value) in fields) when (key) {
            "properties", "\$defs" -> value.obj().values.forEach(::checkVocabulary)
            "oneOf", "anyOf", "allOf" -> value.arr().forEach(::checkVocabulary)
            "if", "then", "items" -> checkVocabulary(value)
            "additionalProperties" -> require(value == JBool(false)) { "Unsupported open object schema" }
            "format" -> require(value.str() == "date-time") { "Unsupported format" }
            "\$ref" -> require(value.str().startsWith("#/\$defs/")) { "External schema references forbidden" }
        }
    }
    fun validate(definition: String, value: JValue) {
        val rule = defs[definition] ?: throw IllegalArgumentException("Unknown schema definition")
        apply(rule, value, "$")
    }
    private fun matches(rule: JValue, value: JValue): Boolean = try { apply(rule,value,"$"); true } catch (_: IllegalArgumentException) { false }
    private fun apply(rule: JValue, value: JValue, path: String) {
        val f = rule.obj()
        f["\$ref"]?.let { ref -> apply(defs[ref.str().removePrefix("#/\$defs/")] ?: error("Missing schema reference"),value,path) }
        f["const"]?.let { require(value == it) { "$path: constant mismatch" } }
        f["enum"]?.let { require(value in it.arr()) { "$path: enum mismatch" } }
        f["oneOf"]?.let { require(it.arr().count { r -> matches(r,value) } == 1) { "$path: oneOf mismatch" } }
        f["anyOf"]?.let { require(it.arr().any { r -> matches(r,value) }) { "$path: anyOf mismatch" } }
        f["allOf"]?.arr()?.forEach { apply(it,value,path) }
        f["if"]?.let { if (matches(it,value)) f["then"]?.let { then -> apply(then,value,path) } }
        f["type"]?.let {
            val ok = when (it.str()) { "object" -> value is JObject; "array" -> value is JArray; "string" -> value is JString; "integer" -> value is JNumber; "boolean" -> value is JBool; "null" -> value == JNull; else -> false }
            require(ok) { "$path: type mismatch" }
        }
        if (value is JObject) {
            f["required"]?.arr()?.forEach { require(it.str() in value.fields) { "$path: required field absent" } }
            val properties = f["properties"]?.obj().orEmpty()
            if (f["additionalProperties"] == JBool(false)) require(value.fields.keys.all { it in properties }) { "$path: unknown field" }
            properties.forEach { (key,child) -> value.fields[key]?.let { apply(child,it,"$path.$key") } }
        }
        if (value is JArray) {
            f["minItems"]?.let { require(value.values.size >= it.int()) { "$path: short array" } }
            f["maxItems"]?.let { require(value.values.size <= it.int()) { "$path: large array" } }
            if (f["uniqueItems"] == JBool(true)) require(value.values.toSet().size == value.values.size) { "$path: duplicate item" }
            f["items"]?.let { child -> value.values.forEachIndexed { i,item -> apply(child,item,"$path[$i]") } }
        }
        if (value is JString) {
            val length = value.value.codePointCount(0,value.value.length)
            f["minLength"]?.let { require(length >= it.int()) { "$path: short string" } }
            f["maxLength"]?.let { require(length <= it.int()) { "$path: long string" } }
            f["pattern"]?.let { require(Pattern.compile(it.str(), Pattern.UNICODE_CHARACTER_CLASS).matcher(value.value).find()) { "$path: pattern mismatch" } }
            f["format"]?.let { require(validTimestamp(value.value)) { "$path: invalid UTC timestamp" } }
        }
        if (value is JNumber) {
            val n = BigInteger(value.lexical)
            f["minimum"]?.let { require(n >= BigInteger((it as JNumber).lexical)) { "$path: below minimum" } }
            f["maximum"]?.let { require(n <= BigInteger((it as JNumber).lexical)) { "$path: above maximum" } }
        }
    }
    private fun validTimestamp(value: String): Boolean = try {
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?Z").matches(value))
        val date = LocalDateTime.parse(value.removeSuffix("Z"))
        date.year in 1..9999
    } catch (_: Exception) { false }
}
