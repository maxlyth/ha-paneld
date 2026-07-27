package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSinkProbeUiContractTest {
    @Test
    fun `TCP wording requires marker verification instead of claiming collector acknowledgement`() {
        val script = asset("configure.js").readText()
        val start = script.indexOf("} else if (p.ok && p.protocol === \"syslog-tcp\")")
        val end = script.indexOf("} else if (p.ok)", start)
        val block = script.substring(start, end)
        assertTrue(block.contains("A TCP socket write is not collector"))
        assertTrue(block.contains("verify the marker in your collector"))
        assertFalse(block.contains("where + \" accepted a test record.\""))
    }

    private fun asset(name: String): File = sequenceOf(
        File("src/main/assets/$name"),
        File("app/src/main/assets/$name"),
    ).first { it.isFile }
}
