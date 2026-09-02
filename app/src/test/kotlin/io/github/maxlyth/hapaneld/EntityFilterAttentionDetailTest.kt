package io.github.maxlyth.hapaneld

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class EntityFilterAttentionDetailTest {
    @Test
    fun `count changes without exporting an English plural branch`() {
        val singular = entityFilterAttentionDetail(1)
        val plural = entityFilterAttentionDetail(7)

        assertEquals(
            "Nothing is wrong with Home Assistant. The panel needs an answer about safety checks " +
                "found while reading your entities before it can open the dashboard. " +
                "Number requiring review: 1.",
            singular,
        )
        assertEquals(singular.replace("1", "7"), plural)
    }

    @Test
    fun `names the entities page when the panel knows its address`() {
        val plain = entityFilterAttentionDetail(2)
        val remote = entityFilterAttentionDetail(2, "http://192.0.2.10:8888/entities")

        assertEquals(
            plain + " The same choices are available from another device at http://192.0.2.10:8888/entities.",
            remote,
        )
        assertEquals(plain, entityFilterAttentionDetail(2, ""))
        assertEquals(plain, entityFilterAttentionDetail(2, null))
    }

    @Test
    fun `non-positive issue count is rejected`() {
        assertFailsWith<IllegalArgumentException> { entityFilterAttentionDetail(0) }
        assertFailsWith<IllegalArgumentException> { entityFilterAttentionDetail(-1) }
    }
}
