package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSensorsBrightnessDisplayContractTest {
    @Test fun `live sensors shows rounded brightness percentage with bounded raw diagnostics`() {
        val source = asset("info.js").readText()
        val formatter = source.substring(
            source.indexOf("function formatBrightness(raw)"),
            source.indexOf("function sensorsCard", source.indexOf("function formatBrightness(raw)")),
        )

        assertTrue("invalid or unavailable readings must stay hidden", "typeof raw!=='number'||!isFinite(raw)||raw<0" in formatter)
        assertTrue("displayed raw brightness must remain within the API range", "Math.max(0,Math.min(255,Math.round(raw)))" in formatter)
        assertTrue("percentage must be rounded from the bounded raw value", "Math.round(value*100/255)+'% ('" in formatter)
        assertTrue("raw diagnostics must remain visible beside the percentage", "+value+' / 255)'" in formatter)
        assertTrue("the live row must use the shared formatter", "var brightness=formatBrightness(d.brightness);" in source)
        assertTrue("unavailable brightness must not create a row", "if(brightness!=null)rows.push({label:'Brightness',val:brightness});" in source)
        assertFalse("the raw-only presentation must not return", "val:d.brightness+' / 255'" in source)
    }

    private fun asset(name: String): File {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(File(working, "app/src/main/assets/$name"), File(working, "src/main/assets/$name"))
            .first { it.isFile }
    }
}
