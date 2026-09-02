package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSensorsBrightnessDisplayContractTest {
    @Test fun `dashboard live state shows percentage before bounded native brightness`() {
        val source = kotlinSource("PaneldServer.kt").readText()
        val formatter = source.substring(
            source.indexOf("val brightness = effectiveBrightness()"),
            source.indexOf("return listOf(", source.indexOf("val brightness = effectiveBrightness()")),
        )

        assertTrue("native brightness must be bounded to the Android 0-255 range", "brightness?.coerceIn(0, 255)" in formatter)
        assertTrue("percentage must use rounded integer conversion", "(value * 100 + 127) / 255" in formatter)
        assertTrue("percentage must precede the compact native value", "\"\${(value * 100 + 127) / 255}% (\$value)\"" in formatter)
        assertFalse("raw-only Live-state brightness must not return", "?.toString() ?: \"?\"" in formatter)
    }

    @Test fun `live sensors shows rounded brightness percentage with bounded raw diagnostics`() {
        val source = asset("info.js").readText()
        val formatter = source.substring(
            source.indexOf("function formatBrightness(raw)"),
            source.indexOf("function sensorsCard", source.indexOf("function formatBrightness(raw)")),
        )

        assertTrue("invalid or unavailable readings must stay hidden", "typeof raw!=='number'||!isFinite(raw)||raw<0" in formatter)
        assertTrue("displayed raw brightness must remain within the API range", "Math.max(0,Math.min(255,Math.round(raw)))" in formatter)
        assertTrue("percentage must be rounded from the bounded raw value", "Math.round(value*100/255)+'% ('" in formatter)
        assertTrue("native value must remain visible beside the percentage", "+value+')'" in formatter)
        assertTrue("the live row must use the shared formatter", "var brightness=formatBrightness(d.brightness);" in source)
        assertTrue("unavailable brightness must not create a row", "if(brightness!=null)rows.push({label:i18nText('dashboard.sensors.brightness','Brightness'),val:brightness});" in source)
        assertFalse("the raw-only presentation must not return", "val:d.brightness+' / 255'" in source)
    }

    @Test fun `dashboard tuning values use compact percentages and expose native minimum`() {
        val source = kotlinSource("PaneldServer.kt").readText()
        val formatter = source.substring(
            source.indexOf("private fun displayRowsHtml"),
            source.indexOf("private fun updatesRowsHtml"),
        )

        assertTrue("minimum must use the runtime brightness conversion", "AdaptiveLuxCurve.percentToBrightness(percent)" in formatter)
        assertTrue("minimum must show compact percentage before the native value", "\"\$percent% (\${AdaptiveLuxCurve.percentToBrightness(percent)})\"" in formatter)
        assertTrue("sensitivity must show its 0-100 scale as a compact percentage", "let { \"\$it%\" }" in formatter)
        assertFalse("dashboard tuning percentages must not contain a space before percent", Regex("\\$[A-Za-z_][A-Za-z0-9_]* %").containsMatchIn(formatter))
    }

    private fun asset(name: String): File {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(File(working, "app/src/main/assets/$name"), File(working, "src/main/assets/$name"))
            .first { it.isFile }
    }

    private fun kotlinSource(name: String): File {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(
            File(working, "app/src/main/kotlin/io/github/maxlyth/hapaneld/http/$name"),
            File(working, "src/main/kotlin/io/github/maxlyth/hapaneld/http/$name"),
        ).first { it.isFile }
    }
}
