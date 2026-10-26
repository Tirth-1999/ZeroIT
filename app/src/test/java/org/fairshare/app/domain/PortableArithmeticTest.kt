package org.fairshare.app.domain

import org.fairshare.app.math.BigInteger as SharedInteger
import org.fairshare.app.math.BigDecimal as SharedDecimal
import java.math.BigInteger
import java.math.BigDecimal
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/** Independent Java oracle verifies the portable implementation before using it on iOS. */
class PortableArithmeticTest {
    @Test fun portableSignedIntegerOperationsMatchJava() {
        val random = Random(89138)
        repeat(1000) {
            val a = random.nextLong(); val b = random.nextLong().let { if (it == 0L) 1L else it }
            val sharedA = SharedInteger.valueOf(a); val sharedB = SharedInteger.valueOf(b)
            val javaA = BigInteger.valueOf(a); val javaB = BigInteger.valueOf(b)
            assertEquals((javaA + javaB).toString(), (sharedA + sharedB).toString())
            assertEquals((javaA - javaB).toString(), (sharedA - sharedB).toString())
            assertEquals((javaA * javaB).toString(), (sharedA * sharedB).toString())
            assertEquals(javaA.divideAndRemainder(javaB).map { it.toString() }, sharedA.divideAndRemainder(sharedB).map { it.toString() })
        }
        assertEquals(Long.MIN_VALUE.toString(), SharedInteger.valueOf(Long.MIN_VALUE).toString())
    }
    @Test fun portableDecimalScaleAndSignMatchJava() {
        listOf("0", "-0.00", ".5", "-.5", "+1.200", "1.", "-999999999999999999.999", "92233720368547758.07").forEach { text ->
            val shared = SharedDecimal(text); val java = BigDecimal(text)
            assertEquals(java.toPlainString(), shared.toPlainString())
            assertEquals(java.stripTrailingZeros().toPlainString(), shared.stripTrailingZeros().toPlainString())
            assertEquals(java.movePointRight(3).toPlainString(), shared.movePointRight(3).toPlainString())
            assertEquals((java + BigDecimal("1.25")).toPlainString(), (shared + SharedDecimal("1.25")).toPlainString())
        }
    }
}
