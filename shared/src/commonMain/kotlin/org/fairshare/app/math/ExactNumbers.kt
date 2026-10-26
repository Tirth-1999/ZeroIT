package org.fairshare.app.math

import com.ionspin.kotlin.bignum.integer.BigInteger as PortableInteger

/** One portable exact arithmetic implementation on both mobile targets. */
class BigInteger private constructor(private val value: PortableInteger) : Comparable<BigInteger> {
    companion object {
        val ZERO = valueOf(0)
        val ONE = valueOf(1)
        fun valueOf(value: Long) = BigInteger(PortableInteger.fromLong(value))
        fun parse(value: String) = BigInteger(PortableInteger.parseString(value, 10))
        fun powerOfTen(exponent: Int): BigInteger { require(exponent in 0..256); return parse("1" + "0".repeat(exponent)) }
    }
    fun add(other: BigInteger) = BigInteger(value + other.value)
    operator fun plus(other: BigInteger) = add(other)
    operator fun minus(other: BigInteger) = BigInteger(value - other.value)
    operator fun times(other: BigInteger) = BigInteger(value * other.value)
    operator fun unaryMinus() = BigInteger(-value)
    fun divideAndRemainder(other: BigInteger): List<BigInteger> = value.divideAndRemainder(other.value).let { listOf(BigInteger(it.first), BigInteger(it.second)) }
    fun abs() = if (signum() < 0) -this else this
    fun signum() = value.compareTo(PortableInteger.ZERO).coerceIn(-1, 1)
    fun min(other: BigInteger) = if (this < other) this else other
    fun toLong() = toString().toLong()
    fun toInt() = toString().toInt()
    override fun compareTo(other: BigInteger) = value.compareTo(other.value)
    override fun equals(other: Any?) = other is BigInteger && value == other.value
    override fun hashCode() = value.hashCode()
    override fun toString() = value.toString()
}

/** Finite decimal: an arbitrary precision integer and a scale, never floating point. */
class BigDecimal private constructor(private val units: BigInteger, private val places: Int) : Comparable<BigDecimal> {
    constructor(text: String) : this(parseUnits(text), decimalPlaces(text))
    companion object {
        val ZERO = BigDecimal("0")
        fun valueOf(value: Long, scale: Int = 0) = BigDecimal(BigInteger.valueOf(value), scale)
        private fun decimalPlaces(text: String): Int = text.substringAfter('.', "").length
        private fun parseUnits(text: String): BigInteger {
            require(text.length in 1..128 && Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)").matches(text)) { "Enter a valid decimal number" }
            return BigInteger.parse(text.replace(".", ""))
        }
    }
    fun scale() = places
    fun unscaledValue() = units
    fun signum() = units.signum()
    fun setScale(scale: Int): BigDecimal {
        require(scale in 0..256)
        if (scale >= places) return BigDecimal(units * BigInteger.powerOfTen(scale - places), scale)
        val (value, remainder) = units.divideAndRemainder(BigInteger.powerOfTen(places - scale))
        if (remainder != BigInteger.ZERO) throw ArithmeticException("Decimal has more precision than allowed")
        return BigDecimal(value, scale)
    }
    fun movePointRight(digits: Int) = if (places >= digits) BigDecimal(units, places - digits) else BigDecimal(units * BigInteger.powerOfTen(digits - places), 0)
    fun toBigIntegerExact() = setScale(0).units
    fun add(other: BigDecimal): BigDecimal { val scale = maxOf(places, other.places); return BigDecimal(setScale(scale).units + other.setScale(scale).units, scale) }
    operator fun plus(other: BigDecimal) = add(other)
    operator fun minus(other: BigDecimal) = add(BigDecimal(-other.units, other.places))
    override fun compareTo(other: BigDecimal): Int { val scale = maxOf(places, other.places); return setScale(scale).units.compareTo(other.setScale(scale).units) }
    fun stripTrailingZeros(): BigDecimal { var digits = units; var scale = places; while (scale > 0) { val (q, r) = digits.divideAndRemainder(BigInteger.valueOf(10)); if (r != BigInteger.ZERO) break; digits = q; scale-- }; return BigDecimal(digits, scale) }
    fun toPlainString(): String {
        val magnitude = units.abs().toString().padStart(places + 1, '0')
        val decimal = if (places == 0) magnitude else magnitude.dropLast(places) + "." + magnitude.takeLast(places)
        return (if (units.signum() < 0) "-" else "") + decimal
    }
}
fun String.toBigDecimal() = BigDecimal(trim())
