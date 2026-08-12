package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.BuildConfig
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HardenedApprovalAssetContractTest {
    private val assetsDir: File by lazy {
        listOf(File("src/main/assets"), File("app/src/main/assets"), File("../app/src/main/assets"))
            .first(File::isDirectory)
    }

    private fun asset(name: String): String = File(assetsDir, name).readText()

    private fun nodeAvailable(): Boolean = runCatching {
        ProcessBuilder("node", "--version").start().waitFor() == 0
    }.getOrDefault(false)

    private fun runNode(script: String, vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(listOf("node", "-e", script) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    @Test fun openApiDescribesEveryHardenedHttpOutcome() {
        val document = JSONObject(asset("openapi.json"))
        assertEquals(BuildConfig.VERSION_NAME, document.getJSONObject("info").getString("version"))
        val guidance = document.getJSONObject("components").getJSONObject("responses")
            .getJSONObject("ApprovalRequired").getString("description").lowercase()
        assertTrue(guidance.contains("physically at the panel"))
        assertTrue(guidance.contains("panel's screen"))
        assertTrue(guidance.contains("cannot be approved remotely"))
        assertTrue(guidance.contains("retry the identical request from the same peer"))
        assertTrue(guidance.contains("one-use"))

        val paths = document.getJSONObject("paths")
        val protected = listOf(
            "POST /play",
            "POST /api/v1/play",
            "POST /api/v1/profiles/select",
            "POST /api/v1/profiles/activate",
            "POST /api/v1/profiles/rollback",
            "POST /api/v1/config",
            "GET /api/v1/config/export",
            "POST /api/v1/config/import",
            "POST /api/v1/restore",
            "POST /api/v1/config/revisions/{id}/restore",
            "POST /api/v1/install/component",
            "POST /api/v1/install/apk/commit",
            "POST /api/v1/install/apk/from-url",
            "POST /api/v1/backup",
            "POST /api/v1/uninstall",
            "POST /api/v1/webview/heal",
            "POST /api/v1/dashboard/clear-storage",
            "POST /api/v1/companion/repair-url",
            "POST /api/v1/action",
            "POST /api/v1/tame",
            "POST /api/v1/display/density",
            "POST /api/v1/power-safety/repair",
        )
        protected.forEach { route ->
            val (method, path) = route.split(" ", limit = 2)
            val response = paths.getJSONObject(path).getJSONObject(method.lowercase())
                .getJSONObject("responses").getJSONObject("202")
            val documented = response.optString("\$ref").contains("ApprovalRequired") ||
                response.optString("description").contains("approval-required")
            assertTrue("$route must document its Hardened-mode 202 approval challenge", documented)
        }

        val input = paths.getJSONObject("/api/v1/input").getJSONObject("post").getJSONObject("responses")
        assertTrue(input.getJSONObject("403").getString("description").contains("remote-input-disabled"))
        assertFalse(input.getJSONObject("202").getString("description").contains("approval-required"))

        val configConflict = paths.getJSONObject("/api/v1/config").getJSONObject("post")
            .getJSONObject("responses").getJSONObject("409").getString("description")
        assertTrue(configConflict.contains("network ADB cannot be enabled"))
        assertTrue(configConflict.contains("saved separately"))

        val inspectResponses = paths.getJSONObject("/api/v1/inspect/start").getJSONObject("post")
            .getJSONObject("responses")
        assertFalse(inspectResponses.has("202"))
        assertTrue(inspectResponses.getJSONObject("409").getString("description")
            .contains("devtools-incompatible-with-hardened-mode"))
    }

    @Test fun everyRemoteUiUsesTheStructuredApprovalChallengeBeforeSuccess() {
        val install = asset("install.js")
        assertTrue(install.contains("response.status === 202 && body && body.error === 'approval-required'"))
        assertTrue(install.indexOf("if (r.status === 202)") < install.indexOf("if (r.ok) return r.blob()"))
        assertTrue(install.contains(".then(approvalAwareJson).then(function (d)"))
        assertTrue(install.contains("path !== '/api/v1/tame' && path !== '/api/v1/display/density'"))
        listOf(
            "/api/v1/install/component",
            "/api/v1/webview/heal",
            "/api/v1/companion/repair-url",
            "/api/v1/install/apk/commit",
            "/api/v1/install/apk/from-url",
            "/api/v1/uninstall",
            "/api/v1/backup",
            "/api/v1/restore",
            "/api/v1/config/import",
        ).forEach { assertTrue("install.js must handle $it", install.contains(it)) }

        val configure = asset("configure.js")
        assertTrue(configure.contains("response.status === 202 && body && body.error === \"approval-required\""))
        assertTrue(configure.contains("fetch(\"/api/v1/config\""))
        assertTrue(configure.contains("fetch(\"/api/v1/dashboard/clear-storage\""))
        assertTrue(configure.contains("e && e.message ? e.message"))
        assertTrue(configure.contains("keep_awake: true"))
        assertTrue(configure.contains("prevent_idle_dim: true"))

        val powerSafety = asset("power-safety.js")
        assertTrue(powerSafety.contains("body.error === 'approval-required'"))
        assertTrue(powerSafety.contains("form[data-power-safety-repair]"))
        assertTrue(powerSafety.contains("method: 'POST'"))

        val profiles = asset("profiles.js")
        val fetch = profiles.substringAfter("function jsonFetch").substringBefore("function yamlFetch")
        assertTrue(fetch.indexOf("body.error === \"approval-required\"") < fetch.indexOf("if (!response.ok)"))
        val activate = profiles.substringAfter("function activate").substringBefore("function pollAfterRestart")
        assertTrue(activate.contains("error.body.error === \"approval-required\""))
        assertTrue(activate.contains("approvalMessage(error.body)"))

        val info = asset("info.js")
        assertTrue(info.contains("response.status===202&&body&&body.error==='approval-required'"))
        assertTrue(info.contains("fetch('/api/v1/inspect/start'"))
        assertTrue(info.contains("fetch('/api/v1/action'"))
        assertTrue(info.contains("error&&error.message?error.message"))
    }

    @Test fun protectedHtmlActionsCarryAnAlwaysVisibleAccessibleMarker() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        val css = asset("info.css")
        val info = asset("info.js")
        val install = asset("install.js")
        val configure = asset("configure.js")
        val api = asset("api.html")

        assertTrue(css.contains("[data-hardened-approval]::after"))
        assertTrue(css.contains(".hardened-approval-key"))
        assertTrue(css.contains(".hardened-approval-key.top"))
        val infoMarkerSize = Regex("""\[data-hardened-approval]::after\{[^}]*width:([^;]+);height:([^;]+)""")
            .find(css)?.destructured?.let { (width, height) -> width to height }
        val apiMarkerSize = Regex("""\[data-hardened-approval]::after\{[^}]*width:([^;]+);height:([^;]+)""")
            .find(api)?.destructured?.let { (width, height) -> width to height }
        assertEquals("standard shield should remain legible without dominating its label", ".81rem" to ".95rem", infoMarkerSize)
        assertEquals("API explorer must use the same shield size", infoMarkerSize, apiMarkerSize)
        val compactMarkerSize = Regex("""\.pbtn\[data-hardened-approval]::after\{width:([^;]+);height:([^;]+)""")
            .find(css)?.destructured?.let { (width, height) -> width to height }
        assertEquals("compact controls should use a smaller shield", ".68rem" to ".8rem", compactMarkerSize)
        assertTrue("protected setting labels must keep the final word and shield in one inline run", configure.contains("class: \"hardened-label-tail\""))
        assertTrue("the shield tail must not wrap away from the final label word", css.contains(".hardened-label-tail{white-space:nowrap}"))
        assertTrue("the shield tail must retain visible separation from the final label word", css.contains(".hardened-label-tail::after{margin-left:.34rem}"))
        assertTrue(source.contains("Shielded actions need physical approval on this panel in Hardened mode; they cannot be approved remotely."))
        assertTrue(source.contains("aria-describedby=\"hardened-approval-description\""))

        val pageShell = source.substringAfter("private fun page(").substringBefore("private fun configureBody")
        assertTrue(pageShell.contains("hardenedApprovalKey(top = active == \"install\")"))
        assertTrue(pageShell.contains("active in setOf(\"configure\", \"install\")"))
        assertFalse("Profile users rely on the action markers without a repeated legend", pageShell.contains("\"profiles\", \"install\""))
        assertTrue(pageShell.contains("approvalKey.takeIf { active == \"install\" }"))
        assertTrue("Install approval narrative must precede its shield-heavy content", pageShell.indexOf("\$approvalKeyBefore") < pageShell.indexOf("\$body"))
        assertTrue("other approval narratives must follow page content", pageShell.indexOf("\$body") < pageShell.indexOf("\$approvalKeyAfter"))
        val dashboard = source.substringAfter("private fun infoHtml()").substringBefore("private fun esc(")
        assertFalse("Dashboard explains its sole protected action at confirmation time", dashboard.contains("\${hardenedApprovalKey()}"))
        assertFalse("Dashboard has no persistent shield markers", dashboard.contains("hardenedApprovalDescription()"))
        assertTrue(dashboard.contains("data-hardened=\"\${if (config.hardenedSecurityEnabled) \"1\" else \"0\"}\""))
        assertTrue(info.contains("Hardened mode requires physical approval on this panel; it cannot be approved remotely."))
        assertTrue(info.contains("note.setAttribute('role','status');note.setAttribute('aria-live','polite')"))
        assertTrue(source.contains("Approve this request physically on the panel, then retry it; it cannot be approved remotely."))
        assertTrue("API approval narrative must follow the endpoint catalog", api.indexOf("<div id=\"root\"></div>") < api.indexOf("<p class=\"approval-key\">"))

        listOf(
            "id=\"profile-activate\" type=\"button\"\${hardenedApprovalAttrs()}",
            "id=\"profile-auto\" type=\"button\"\${hardenedApprovalAttrs()}",
            "id=\"profile-rollback\" type=\"button\"\${hardenedApprovalAttrs()}",
            "<button class=\"pbtn\"\${hardenedApprovalAttrs()} onclick=\"healWebView(this)\">⬇ Update WebView now",
            "<button class=\"pbtn\"\${hardenedApprovalAttrs()} onclick=\"installComp('companion','update',this)\">⬇ Install HA Companion",
            "<button class=\"pbtn\"\${hardenedApprovalAttrs()} onclick=\"repairCompUrl(this)\">⚙ Repair internal URL",
        ).forEach { snippet -> assertTrue("missing protected-action marker near $snippet", source.contains(snippet)) }

        assertTrue(install.contains("'<button class=\"pbtn\"' + hardenedApprovalAttrs + ' data-token=\"' + esc(d.token) + '\" onclick=\"apkInstall(this)\""))
        assertTrue(
            "the preview Cancel removes uncommitted bytes and must stay unshielded in Hardened mode",
            install.contains("'<button class=\"pbtn\" data-token=\"' + esc(d.token) + '\" onclick=\"apkDiscard(this)\">✕ Cancel</button>'"),
        )
        assertTrue(
            "the reload-recovery discard is the same unshielded cancellation, scoped by the probe's reference",
            install.contains("'<button class=\"pbtn\" data-token=\"' + esc(d.discard) + '\" onclick=\"apkDiscard(this)\">✕ Discard pending upload</button>'"),
        )
        assertTrue(install.contains("'<button class=\"pbtn\"' + hardenedApprovalA11yAttrs + ' style=\"margin-top:8px\" onclick=\"restoreConfirm(this)\""))
        listOf(
            "hardenedApprovalCardTitle(\"Managed components\", conditional = true)",
            "hardenedApprovalCardTitle(\"Uninstall an app\")",
            "hardenedApprovalCardTitle(\"Vendor packages\", conditional = true)",
            "hardenedApprovalCardTitle(\"Display sizing\"",
            "hardenedApprovalCardTitle(\"Backup &amp; restore\", conditional = true)",
        ).forEach { assertTrue("missing shielded Install card title $it", source.contains(it)) }
        val installCards = source.substringAfter("private fun componentsCardHtml").substringBefore("private fun logsBody")
        assertFalse("shielded-card actions must not repeat the visible shield", installCards.contains("hardenedApprovalAttrs()"))
        listOf(
            "onclick=\"installComp('webview','update',this)\"",
            "onclick=\"doBackup(this)\"",
            "onclick=\"configExport(true,this)\"",
            "onchange=\"configImport(this)\"",
            "onclick=\"doUninstall(this)\"",
            "onclick=\"installSel('\$name',this)\"",
        ).forEach { action ->
            val line = installCards.lineSequence().firstOrNull { it.contains(action) }.orEmpty()
            assertTrue("$action must retain its accessible approval description", line.contains("hardenedApprovalA11yAttrs()"))
        }
        assertTrue(source.lineSequence().first { it.contains("Tame all recommended</button>") }.contains("hardenedApprovalA11yAttrs()"))
        val vendorCard = source.substringAfter("private fun tameRowHtml").substringBefore("/** Display-sizing card")
        assertFalse(vendorCard.contains("hardenedApprovalAttrs()"))
        assertFalse("Vendor packages is no longer an experimental feature", vendorCard.contains("cardbadge exp"))
        assertTrue(Regex("hardenedApprovalA11yAttrs\\(\\)").findAll(vendorCard).count() >= 2)
        assertTrue(vendorCard.contains("<h3 data-hardened-approval=\"conditional\""))
        val displayCard = source.substringAfter("private fun displayCardHtml").substringBefore("/** Read a bundled static asset")
        assertFalse(displayCard.contains("hardenedApprovalAttrs()"))
        assertTrue(Regex("hardenedApprovalA11yAttrs\\(\\)").findAll(displayCard).count() >= 2)
        assertTrue(source.contains("hardened-approval-section-conditional-description"))
        assertTrue(source.contains("if (installer || (wv.tooOld && rec != null && root))"))
        assertTrue(source.contains("if (root) hardenedApprovalCardTitle(\"Uninstall an app\")"))
        assertTrue(source.contains("if (!locked) hardenedApprovalCardTitle(\"Vendor packages\""))
        assertTrue(source.contains("if (!locked) hardenedApprovalCardTitle(\"Display sizing\""))
        listOf("self_update", "update_channel", "companion_auto_update", "companion_update_channel", "webview_auto_update")
            .forEach { assertTrue(configure.contains("$it: true")) }
        assertTrue(configure.contains("shieldTail.setAttribute(\"data-hardened-approval\", \"conditional\")"))
        assertTrue(configure.contains("valueControl.setAttribute(\"aria-describedby\", \"hardened-approval-conditional-description\")"))
        assertTrue(configure.contains("text: \"Renderer storage\", \"data-hardened-approval\": \"\""))
        assertTrue(configure.contains("text: \"Clear cached dashboard data. Keeps sign-in.\""))
        assertTrue(configure.contains("setClearStatus(r.ok ? \"Clear requested.\""))
        assertTrue(configure.contains("}, 4000);"))
        assertTrue(configure.contains("class: \"pbtn\", text: \"Clear renderer storage\""))
        assertFalse(configure.contains("🧹"))
        assertFalse(configure.contains("text: \"🧹 Clear renderer storage\", \"data-hardened-approval\": \"\""))
        assertTrue(api.contains("var approvalKind=JSON.stringify(op.responses||{}).indexOf('ApprovalRequired')>=0?'required':null"))
        listOf(
            "/api/v1/config",
            "/api/v1/config/export",
            "/api/v1/config/import",
            "/api/v1/restore",
            "/api/v1/action",
        ).forEach { assertTrue("$it must be described as conditionally protected", api.contains("'$it'")) }
        assertTrue(api.contains("approvalKind='conditional'"))
        assertTrue(api.contains("approvalKind==='conditional'?'hardened-approval-conditional-description':'hardened-approval-description'"))

        val profiles = asset("profiles.js")
        val modal = profiles.substringAfter("function openModal").substringBefore("function closeModal")
        assertTrue(modal.contains("confirm.setAttribute(\"data-hardened-approval\", \"\")"))
        assertTrue(modal.contains("confirm.setAttribute(\"aria-describedby\", \"hardened-approval-description\")"))
        assertTrue(modal.contains("confirm.removeAttribute(\"data-hardened-approval\")"))
        val activation = profiles.substringAfter("function activate").substringBefore("function pollAfterRestart")
        assertTrue(activation.contains("\"Confirm and restart\", true"))

        val attrs = source.substringAfter("private fun hardenedApprovalAttrs(").substringBefore("private fun hardenedApprovalDescription")
        assertFalse(attrs.contains("hardenedSecurityEnabled"))

        val dashboardButton = source.substringAfter("fun pbtn(").substringBefore("// \"Launcher\"")
        assertTrue(dashboardButton.contains("return dashboardControlButtonHtml(action, label, disabledReason, style)"))
        assertFalse(dashboardButton.contains("hardenedApprovalAttrs()"))
        assertFalse(dashboardButton.contains("hardenedApproval"))

        val devtools = source.substringAfter("id=\"inspstart\"").substringBefore("id=\"insthint\"")
        assertFalse(devtools.contains("hardenedApprovalAttrs"))
        val profileDelete = source.substringAfter("id=\"profile-delete\"").substringBefore("</div>")
        assertFalse(profileDelete.contains("hardenedApprovalAttrs"))
        assertTrue(source.contains("<a class=\"pbtn\" href=\"/api/v1/config/export\">⭳ Export settings</a>"))
    }

    @Test fun backupApprovalJsonIsNeverSavedAsAnArchive() {
        assumeTrue("node not available", nodeAvailable())
        val script = """
            const fs=require('fs'),vm=require('vm');
            let blobReads=0,objectUrls=0,clicks=0;
            const ids={
              'bk-pw':{value:'secret'},'bk-plain':{checked:false},'bk-comp':{checked:true},
              'bk-msg':{textContent:''},'pswitch':{dataset:{selfName:'Test Panel'}}
            };
            global.window=global;
            global.location={href:'http://panel/install',hash:'',reload(){throw new Error('unexpected reload')}};
            global.document={
              body:{appendChild(){}},
              getElementById(id){return ids[id]||null},querySelector(){return null},querySelectorAll(){return []},
              addEventListener(){},
              createElement(tag){return {tagName:tag.toUpperCase(),remove(){},click(){clicks++}}}
            };
            global.fetch=(url)=>Promise.resolve(url==='/api/v1/backup'?{
              status:202,ok:true,json:()=>Promise.resolve({ok:false,error:'approval-required',approval_id:'abc',message:'Approve this request on the panel, then retry it.'}),
              blob:()=>{blobReads++;return Promise.resolve(new Blob(['wrong']))}
            }:{status:200,ok:true,json:()=>Promise.resolve({present:false})});
            const NativeURL=global.URL;
            NativeURL.createObjectURL=()=>{objectUrls++;return 'blob:wrong'};
            NativeURL.revokeObjectURL=()=>{};
            vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
            const button={disabled:false};
            global.doBackup(button);
            setImmediate(()=>setImmediate(()=>{
              if(blobReads||objectUrls||clicks)process.exit(2);
              if(ids['bk-msg'].textContent!=='Approve this request on the panel, then retry it.')process.exit(3);
              if(button.disabled)process.exit(4);
            }));
        """.trimIndent()
        val (code, output) = runNode(script, File(assetsDir, "install.js").absolutePath)
        assertEquals("backup approval response reached the archive download path:\n$output", 0, code)
    }

    @Test fun profileSelectionTreatsOnlyStructuredApproval202AsFailure() {
        assumeTrue("node not available", nodeAvailable())
        val script = """
            const fs=require('fs'),vm=require('vm'),source=fs.readFileSync(process.argv[1],'utf8');
            const start=source.indexOf('function string('),end=source.indexOf('function yamlFetch(');
            if(start<0||end<=start)process.exit(2);
            vm.runInThisContext(source.slice(start,end));
            const response=(body)=>({status:202,ok:true,text:()=>Promise.resolve(JSON.stringify(body))});
            global.fetch=()=>Promise.resolve(response({status:'accepted',restart_required:true}));
            jsonFetch('/profiles/select').then(result=>{
              if(result.status!=='accepted')process.exit(3);
              global.fetch=()=>Promise.resolve(response({ok:false,error:'approval-required',message:'Approve this request on the panel, then retry it.'}));
              return jsonFetch('/profiles/select').then(()=>process.exit(4),error=>{
                if(error.status!==202||error.body.error!=='approval-required')process.exit(5);
                if(error.message!=='Approve this request on the panel, then retry it.')process.exit(6);
              });
            });
        """.trimIndent()
        val (code, output) = runNode(script, File(assetsDir, "profiles.js").absolutePath)
        assertEquals("profile selection confused staged and approval-required HTTP 202 responses:\n$output", 0, code)
    }
}
