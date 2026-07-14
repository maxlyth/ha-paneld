package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class EntityTableAssetTest {
    private val asset = listOf(
        File("src/main/assets/entities.js"), File("app/src/main/assets/entities.js"), File("../app/src/main/assets/entities.js"),
    ).firstOrNull(File::isFile)

    @Test fun tableRequestsServerSortAndDoesNotSortOnlyTheCurrentPage() {
        val file = asset
        assumeTrue("entities.js unavailable", file != null)
        val source = file!!.readText()

        assertTrue(source.contains("'&sort='+encodeURIComponent(state.sortKey)+'&dir='"))
        assertTrue(source.contains("'&filter='+encodeURIComponent(card.dataset.filter)+'&q='+encodeURIComponent(search.value.trim())"))
        assertTrue(source.contains("state.offset=0;load()"))
        assertFalse("client-side page sorting must not return", source.contains("state.items.slice().sort"))
        assertFalse("selection must survive sort/search refresh", source.contains("state.selected.clear();state.offset=0"))
        assertTrue("pin-all confirmation must use the global suggested count", source.contains("'all '+suggestedCount+' suggested entities'"))
        assertFalse("pin-all must not describe only the searched/page count", source.contains("'all '+state.total+' suggested entities'"))
    }

    @Test fun entityAssetStillParsesAfterServerSortCutover() {
        val file = asset
        assumeTrue("entities.js unavailable", file != null)
        val node = runCatching { ProcessBuilder("node", "--version").start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue("node unavailable", node)
        val process = ProcessBuilder("node", "--check", file!!.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
    }
}
