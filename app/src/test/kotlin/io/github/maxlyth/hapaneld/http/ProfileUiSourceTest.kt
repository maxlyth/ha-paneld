package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileUiSourceTest {
    private val server = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
    private val script = File("src/main/assets/profiles.js").readText()
    private val css = File("src/main/assets/profiles.css").readText()

    @Test
    fun profileTabIsPrimaryFullWidthLocalAndRestartExplicit() {
        assertTrue("tab(\"profiles\", \"/profiles\", \"Profile\")" in server)
        assertTrue("get(\"/profiles\")" in server)
        assertTrue("profile-workspace" in server)
        assertTrue("Confirm and restart" in script)
        assertTrue("last-known-good" in script)
        assertTrue("grid-template-columns:minmax(0,1fr)" in css)
        assertTrue("vendor/profile-editor/codemirror.js" in server)
        assertFalse("https://" in server.substringAfter("private fun profilesBody()").substringBefore("private fun installBody"))
    }

    @Test
    fun diagnosticsUseSafeDomAndUnsavedEditsDriveSharedDirtySentinel() {
        assertTrue("message.textContent =" in script)
        assertFalse("innerHTML" in script)
        assertTrue("id=\"savebtn\"" in server)
        assertTrue("model.preview.source === model.source" in script)
        assertTrue("expected_catalog_revision" in script)
        assertTrue("X-Profile-Preview-Token" in script)
        assertTrue(script.indexOf("file.size > model.maxBytes") < script.indexOf("file.text()"))
        assertTrue("id=\"profile-generic-draft\" hidden" in server)
        assertTrue("active.ref.id === \"generic\"" in script)
        assertTrue("Shizuku \" + shizuku" in script)
        assertTrue("id=\"profile-shizuku-guidance\" hidden" in server)
        assertTrue("live readiness remains separate" in server)
        assertTrue("review.content_version" in script)
        assertTrue("review.author" in script)
        assertTrue(script.substringAfter("function importFile").substringBefore("function exportSource").contains("model.originalSource = \"\""))
        assertTrue(script.substringAfter("function loadTemplate").substringBefore("function loadDeviceDraft").contains("model.originalSource = \"\""))
        assertTrue("jsonFetch(API + \"/report\")" in script)
        assertTrue("postYaml(\"/probe\", source)" in script)
        assertTrue("generation !== model.viewGeneration || source !== model.source" in script)
        assertTrue("refKey(model.selected) !== refKey(ref)" in script)
        assertTrue(script.substringAfter("function saveProfile").substringBefore("function beginEdit").contains("model.preview = null"))
        assertTrue("function reviewedSummary()" in script)
        assertTrue("model.preview.summary" in script)
        assertTrue("activation.state === \"applying\"" in script)
        assertTrue("activation.state === \"active\"" in script)
        assertTrue(script.substringAfter("function setEditor").substringBefore("function isDirty").contains("useDraft.hidden = true"))
        assertTrue("id=\"profile-auto\"" in server)
        assertTrue("activate(null, \"activate\")" in script)
        assertTrue("function confirmDiscard()" in script)
        assertTrue("window.addEventListener(\"beforeunload\"" in script)
        assertTrue("automatic.disabled = dirty" in script)
        assertTrue("rollback.disabled = !(model.status.rollback_ref || model.status.rollback_auto) || dirty" in script)
        assertTrue("id=\"profile-catalog-issues\"" in server)
        assertTrue("renderCatalogIssues(model.status.issues || [])" in script)
        assertTrue("summary.compatible === false" in script)
        assertTrue("summary && summary.issues || []" in script)
    }

    @Test
    fun codeMirrorBundleIsPinnedReproducibleAndNotRequiredByGradle() {
        val packageJson = File("../tools/profile-editor/package.json").takeIf { it.isFile }
            ?: File("tools/profile-editor/package.json")
        val lock = File(packageJson.parentFile, "package-lock.json")
        val build = File(packageJson.parentFile, "build.mjs")
        val bundle = File("src/main/assets/vendor/profile-editor/codemirror.js")
        val license = File(bundle.parentFile, "LICENSE.txt")
        val notice = File(bundle.parentFile, "NOTICE.txt")
        assertTrue(packageJson.isFile)
        assertTrue(lock.isFile)
        assertTrue(build.isFile)
        assertTrue(bundle.length() > 100_000)
        assertTrue(license.readText().contains("Permission is hereby granted"))
        assertTrue(notice.readText().contains("@codemirror/view 6.43.6"))
        assertTrue(bundle.readText().startsWith("/*! @license CodeMirror 6"))
        assertFalse(packageJson.readText().contains("\"latest\""))
        assertFalse(File("build.gradle.kts").readText().contains("profile-editor"))
    }
}
