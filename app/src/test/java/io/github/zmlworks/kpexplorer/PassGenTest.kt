package io.github.zmlworks.kpexplorer

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

class PassGenTest {
    @Test fun includesEveryClassAndLength() {
        val c = PassGen.Classes(lower = true, upper = true, digits = true, symbols = true)
        repeat(40) {
            val s = PassGen.generate(c, 32, SecureRandom())
            assertEquals(32, s.length)
            assertTrue(s.any { it in 'a'..'z' })
            assertTrue(s.any { it in 'A'..'Z' })
            assertTrue(s.any { it in '0'..'9' })
            assertTrue(s.any { !it.isLetterOrDigit() })
        }
    }

    @Test fun singleClassOnly() {
        val s = PassGen.generate(PassGen.Classes(true, false, false, false), 24)
        assertEquals(24, s.length)
        assertTrue(s.all { it in 'a'..'z' })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyClasses() {
        PassGen.generate(PassGen.Classes(false, false, false, false))
    }

    @Test fun unbiasedIndexInRange() {
        val r = SecureRandom()
        repeat(200) {
            val v = PassGen.unbiasedIndex(r, 10)
            assertTrue(v in 0 until 10)
        }
    }
}
