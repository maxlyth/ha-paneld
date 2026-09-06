package io.github.maxlyth.hapaneld.i18n

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallStaticI18nContractTest {
    private val server = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()

    private fun visibleLiteralTextNodes(source: String): List<String> {
        val withoutInterpolations = Regex("\\\$\\{[^}\\n]*}").replace(source, "")
        return Regex(">([^<>{}\\n]*[A-Za-z][^<>{}\\n]*)<")
            .findAll(withoutInterpolations)
            .map { it.groupValues[1].trim() }
            .toList()
    }

    @Test fun `Install static cards and package fragments are catalogue backed`() {
        val required = setOf(
            "install.ready",
            "install.radio.title",
            "install.audit.title",
            "install.components.title",
            "install.backup.title",
            "install.apk.title",
            "install.uninstall.title",
            "install.card.vendor_packages",
            "install.display.title",
            "install.lock.root_required",
            "install.lock.privileged_required",
            "dashboard.live.led",
            "dashboard.screenshot.unavailable",
        )
        required.forEach { key -> assertTrue("missing static Install consumer $key", server.contains("\"$key\"")) }

        val install = server.substring(server.indexOf("private fun installBody"), server.indexOf("private fun logsBody"))
        listOf(
            ">Radio firmware<",
            ">Health audit<",
            ">Managed components<",
            ">Backup &amp; restore<",
            ">Install an APK<",
            ">Uninstall an app<",
            ">Vendor packages<",
            ">Display sizing<",
        ).forEach { literal -> assertFalse("production-visible English bypassed the catalogue: $literal", install.contains(literal)) }
        listOf(
            "Needs a rooted panel — this one has no root",
            "Needs privileged panel access — no approved route is ready",
        ).forEach { literal -> assertFalse("production-visible English bypassed the catalogue: $literal", server.contains(literal)) }
    }

    @Test fun `Install owned HTML fails closed on new literal text and accessible labels`() {
        val scope = server.substring(server.indexOf("private fun installBody"), server.indexOf("private fun logsBody")) +
            server.substring(server.indexOf("private fun localizedTameGroupTitle"), server.indexOf("private fun asset"))
        val allowedCodeFragments = setOf("\"\"\" else \"\"\"")
        val literalTextNodes = visibleLiteralTextNodes(scope)
            .filterNot { it in allowedCodeFragments || it.matches(Regex("(?:\\$[A-Za-z][A-Za-z0-9]*)+")) }
        assertTrue("Install HTML contains uncatalogued visible text nodes: $literalTextNodes", literalTextNodes.isEmpty())

        val nonLinguisticAttributes = setOf("https://example.com/app.apk", "io.example.app")
        val literalAccessibleAttributes = Regex("(?:title|aria-label|placeholder)=\"([^\"\\n]*)\"")
            .findAll(scope)
            .map { it.groupValues[1] }
            .filterNot { it.startsWith("\${") || it in nonLinguisticAttributes }
            .toList()
        assertTrue(
            "Install HTML contains uncatalogued accessible text: $literalAccessibleAttributes",
            literalAccessibleAttributes.isEmpty(),
        )
    }

    @Test fun `literal scanner catches text on either side of a catalogue interpolation`() {
        assertTrue(visibleLiteralTextNodes("<p>Hardcoded \${esc(strings.get(\"key\"))}</p>").contains("Hardcoded"))
        assertTrue(visibleLiteralTextNodes("<p>\${esc(strings.get(\"key\"))} hardcoded tail</p>").contains("hardcoded tail"))
    }

    @Test fun `Install locale survives lazy package fragments and form outcomes`() {
        val tameRoute = server.substring(server.indexOf("post(\"/tame\")"), server.indexOf("post(\"/display/density\")"))
        val tameCard = server.substring(server.indexOf("private fun tameCardHtml"), server.indexOf("private fun displayCardHtml"))
        val displayRoute = server.substring(server.indexOf("post(\"/display/density\")"), server.indexOf("get(\"/inspect\")"))

        assertTrue(tameRoute.contains("val strings = requestStrings(call)"))
        assertTrue(tameRoute.contains("localizedHref(\"/install#cfg-tame\", strings)"))
        assertTrue("browser failures must use the localized mini-page helper", tameRoute.contains("respondInstallFormError("))
        assertTrue(tameCard.contains("localizedHref(\"/api/v1/tame/suggest\", strings)"))
        assertTrue(tameCard.contains("localizedHref(\"/api/v1/tame\", strings)"))
        assertTrue(displayRoute.contains("val strings = requestStrings(call)"))
        assertTrue(displayRoute.contains("localizedHref(\"/install#cfg-display\", strings)"))

        val failureHelper = server.substring(server.indexOf("private suspend fun respondInstallFormError"), server.indexOf("private fun localizedSetupNeeds"))
        assertTrue("JSON clients must retain the stable machine token", failureHelper.contains("call.respondText(\"\$machineText\\n\", status = status)"))
        assertTrue("only an explicit browser navigation may receive localized HTML", failureHelper.contains("!installFormWantsHtml(call.request.headers[\"Accept\"])"))
        assertTrue("browser failures must escape catalogue text", failureHelper.contains("esc(strings.get(key))"))
    }

    @Test fun `screenshot failure pseudo-content comes from the localized document`() {
        val css = File("src/main/assets/info.css").readText()
        assertTrue(css.contains("content:attr(data-error-label)"))
        assertFalse(css.contains("screenshot unavailable"))
        assertTrue(server.contains("data-error-label=\"${'$'}{esc(strings.get(\"dashboard.screenshot.unavailable\"))}\""))
    }
}
