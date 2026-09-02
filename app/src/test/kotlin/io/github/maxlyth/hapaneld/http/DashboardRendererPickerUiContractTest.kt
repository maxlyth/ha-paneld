package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardRendererPickerUiContractTest {
    private val server = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
    private val configure = File("src/main/assets/configure.js").readText()

    @Test fun autoDashboardPlaceholderUsesTheBuiltInRenderer() {
        assertTrue(server.contains("put(\"dashboard_package\", dashboardRendererAutoLabel(it))"))
        assertTrue(server.contains("resolved == SystemController.BUILTIN_DASHBOARD) \"Built-in renderer\""))
        assertTrue(server.contains("val placeholder = hints[spec.key]?.let { \"auto (\$it)\" }"))
    }

    @Test fun rendererPickerAutoEntryUsesTheSchemaAutoLabel() {
        val picker = configure.substring(
            configure.indexOf("if (f.picker === \"renderer\")"),
            configure.indexOf("if (f.picker === \"package\")"),
        )
        assertTrue(picker.contains("sel.appendChild(el(\"option\", { value: \"\", text: localizedPlaceholder(f.placeholder || \"auto\") }))"))
        assertFalse(picker.contains("f.placeholder || \"Auto-detect\""))
    }
}
