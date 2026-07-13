package io.github.maxlyth.hapaneld.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BoundedStreamsTest {
    @Test fun acceptsTheExactLimitAndPreservesBytes() {
        val bytes = byteArrayOf(0, 1, 2, 3)
        val output = ByteArrayOutputStream()

        assertEquals(4L, BoundedStreams.copy(ByteArrayInputStream(bytes), output, 4L))
        assertArrayEquals(bytes, output.toByteArray())
        assertArrayEquals(bytes, BoundedStreams.readBytes(ByteArrayInputStream(bytes), 4L))
    }

    @Test fun rejectsOneByteBeyondTheLimitEvenWithoutLengthMetadata() {
        val input = ByteArrayInputStream(byteArrayOf(0, 1, 2, 3, 4))
        try {
            BoundedStreams.copy(input, ByteArrayOutputStream(), 4L)
            fail("over-limit stream must fail")
        } catch (e: ByteLimitExceeded) {
            assertEquals(4L, e.limit)
        }
    }

    @Test fun zeroLimitAcceptsOnlyEmptyInput() {
        assertArrayEquals(byteArrayOf(), BoundedStreams.readBytes(ByteArrayInputStream(byteArrayOf()), 0L))
        try {
            BoundedStreams.readBytes(ByteArrayInputStream(byteArrayOf(1)), 0L)
            fail("non-empty stream must exceed a zero limit")
        } catch (_: ByteLimitExceeded) {
        }
    }

    @Test fun aggregateBudgetRejectsAComponentThatWouldCrossTheLimit() {
        val budget = BoundedStreams.Budget(4L)

        assertArrayEquals(byteArrayOf(1, 2, 3), budget.accept(byteArrayOf(1, 2, 3)))
        assertEquals(1L, budget.remaining)
        try {
            budget.accept(byteArrayOf(4, 5))
            fail("aggregate payload must not cross its byte budget")
        } catch (e: ByteLimitExceeded) {
            assertEquals(4L, e.limit)
            assertEquals(1L, budget.remaining)
        }
        assertArrayEquals(byteArrayOf(4), budget.accept(byteArrayOf(4)))
        assertEquals(0L, budget.remaining)
    }
}
