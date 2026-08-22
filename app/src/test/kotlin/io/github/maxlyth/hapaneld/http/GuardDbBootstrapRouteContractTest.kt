package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardDbBootstrapRouteContractTest {
    private val routes by lazy { TestSources.kotlin("http/GuardDbBootstrapRoutes.kt").readText() }
    private val server by lazy { TestSources.kotlin("http/PaneldServer.kt").readText() }

    @Test fun `maintenance bootstrap admits direct LAN peers but never loopback`() {
        listOf(ipv4(192, 168, 40, 12), ipv4(10, 0, 0, 7), ipv4(172, 31, 12, 50), "fc00::1234", "fe80::1234%eth0")
            .forEach { peer -> assertTrue(peer, guardDbDirectLanPeer(peer)) }

        listOf("127.0.0.1", "/127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost")
            .forEach { peer -> assertFalse(peer, guardDbDirectLanPeer(peer)) }
        listOf("198.51.100.4", "2001:4860:4860::8888", "not-an-address")
            .forEach { peer -> assertFalse(peer, guardDbDirectLanPeer(peer)) }
    }

    private fun ipv4(a: Int, b: Int, c: Int, d: Int): String = listOf(a, b, c, d).joinToString(".")

    @Test fun `production server mounts the exact Guard DB bootstrap clock and posts`() {
        assertEquals(1, Regex("guardDbBootstrapRoutes\\(").findAll(server).count())
        assertTrue(server.contains("GuardDbBootstrapRouteDependencies("))
        assertTrue(server.contains("pendingUploads = pendingApks"))
        assertTrue(server.contains("staging = guardDbStaging"))
        assertTrue(server.contains("guardDbSettingsAuthorityStore(appContext).materializeExact()"))
        assertTrue(server.contains("terminalRetirement = GuardDbTerminalRetirementRouteDependencies("))
        assertTrue(server.contains("store = guardDbTerminalRetirementStore(appContext)"))

        val registration = routes.substring(
            routes.indexOf("internal fun Route.guardDbBootstrapRoutes"),
            routes.indexOf("private suspend fun stageGuardDbCandidate"),
        )
        assertTrue(registration.contains("route(\"/api/v1/guard-db\")"))
        assertEquals(
            listOf(
                "GET /clock", "GET /status", "GET /evidence", "POST /stage", "POST /discard",
                "POST /arm", "POST /evidence/retire",
            ),
            Regex("(get|post)\\(\"([^\"]+)\"\\)").findAll(registration)
                .map { "${it.groupValues[1].uppercase()} ${it.groupValues[2]}" }.toList(),
        )
        assertTrue(registration.contains("dependencies.monotonicMs()"))
        assertTrue(registration.contains("MIN_OVERALL_BUDGET_MS"))
        assertTrue(registration.contains("MAX_OVERALL_BUDGET_MS"))
        assertTrue(registration.contains("RECOVERY_RESERVE_MS"))
    }

    @Test fun `production sentinel commit serializes debug authority through durable readback`() {
        val commit = server.substring(
            server.indexOf("commitSentinel = { expectedEpoch, sentinel ->"),
            server.indexOf("authorize = { call, operation, payload, summary ->",
                server.indexOf("commitSentinel = { expectedEpoch, sentinel ->")),
        )
        assertTrue(commit.contains("RemoteDebugSecurityTransitionGate.withEpoch(expectedEpoch)"))
        assertTrue(commit.contains("!config.hardenedSecurityEnabled || CdpRelay.running"))
        assertTrue(commit.contains("!adb.hardenedRemoteDebugOff()"))
        assertTrue(commit.indexOf("store.write(sentinel)") < commit.indexOf("val load = store.load()"))
        assertTrue(commit.contains("load is io.github.maxlyth.hapaneld.util.GuardDbSentinelLoad.Valid"))
        assertTrue(commit.contains("load.sentinel == sentinel"))
        assertTrue(commit.contains("RemoteDebugAuthorityResult.Changed -> GuardDbSentinelCommit.SecurityRefused"))
    }

    @Test fun `stage approval binds the raw request and every inspected candidate field`() {
        val stage = routes.substring(
            routes.indexOf("private suspend fun stageGuardDbCandidate"),
            routes.indexOf("private suspend fun armGuardDbCanary"),
        )
        assertTrue(stage.indexOf("guardDbDirectLanPeer") < stage.indexOf("guardDbBody()"))
        assertTrue(stage.indexOf("pendingUploads.peek") < stage.indexOf("dependencies.authorize("))
        assertTrue(stage.contains("body + \"\\u0000\" + inspection.canonical(request.role)"))
        assertTrue(stage.contains("SensitiveOperation.GUARD_DB_MAINTENANCE"))
        assertTrue(stage.contains("exactHttpApprovalPayload(call, approvalDigest)"))
        assertTrue(stage.contains("SECURITY_EPOCH"))
        assertTrue(stage.contains("RemoteDebugSecurityTransitionGate.withEpoch(securityEpoch)"))
        assertTrue(stage.indexOf("dependencies.authorize(") < stage.indexOf("dependencies.staging.claim("))
        val claim = stage.indexOf("dependencies.staging.claim(")
        assertTrue("post-approval claim must retain the upload token", stage.indexOf("request.token,", claim) > claim)
        assertTrue(
            "post-approval claim must reject changed candidate bytes",
            stage.indexOf("inspection,", claim) > stage.indexOf("request.token,", claim),
        )

        val canonical = routes.substring(
            routes.indexOf("private fun GuardDbCandidateInspection.canonical"),
            routes.indexOf("private fun GuardDbArmManifest.canonical"),
        )
        listOf(
            "role.name", "bytes", "sha256", "versionCode", "signerSha256",
            "contractMinimum", "contractMaximum", "expectedSchema", "settingsAuthorityVersion",
            "settingsAuthorityBytes", "settingsAuthoritySha256",
        ).forEach { field -> assertTrue("stage approval omits $field", canonical.contains(field)) }
    }

    @Test fun `arm approval and post approval recheck bind the exact same boot A and B authority`() {
        val arm = routes.substring(
            routes.indexOf("private suspend fun armGuardDbCanary"),
            routes.indexOf("private data class GuardDbStageRequest"),
        )
        assertTrue(arm.indexOf("guardDbDirectLanPeer") < arm.indexOf("guardDbBody()"))
        assertTrue(arm.indexOf("exactArmManifest") < arm.indexOf("dependencies.authorize("))
        assertTrue(arm.contains("body + \"\\u0000\" + preview.canonical()"))
        assertTrue(arm.contains("SensitiveOperation.GUARD_DB_MAINTENANCE"))
        assertTrue(arm.contains("exactHttpApprovalPayload(call, approvalDigest)"))
        assertTrue(arm.contains("SECURITY_EPOCH"))

        val afterApproval = arm.substringAfter("if (!dependencies.authorize(")
            .substringAfter(") return")
        assertTrue(afterApproval.contains("exact != preview"))
        assertTrue(afterApproval.contains("dependencies.bootNonce() != boot"))
        assertTrue(afterApproval.contains("dependencies.client.supported()"))
        assertTrue(afterApproval.contains("dependencies.client.statusProbe()"))
        assertTrue(afterApproval.contains("dependencies.commitSentinel(securityEpoch, sentinel)"))
        assertTrue(afterApproval.contains("GuardDbSentinelCommit.SecurityRefused"))
        assertTrue(afterApproval.contains("settingsAuthorityVersion = exact.settingsAuthority.version"))
        assertTrue(afterApproval.contains("settingsAuthorityBytes = exact.settingsAuthority.bytes"))
        assertTrue(afterApproval.contains("settingsAuthoritySha256 = exact.settingsAuthority.sha256"))
        assertTrue(afterApproval.contains("securityAuthorityEpoch = exact.securityAuthorityEpoch"))
        assertTrue(afterApproval.indexOf("dependencies.commitSentinel(securityEpoch, sentinel)") <
            afterApproval.indexOf("GuardDbProcessAdmission.update(durable)"))
        assertTrue(afterApproval.indexOf("GuardDbProcessAdmission.update(durable)") <
            afterApproval.indexOf("dependencies.prepare(exact)"))

        val canonical = routes.substring(
            routes.indexOf("private fun GuardDbArmManifest.canonical"),
            routes.indexOf("private suspend fun ApplicationCall.guardDbBody"),
        )
        listOf(
            "session", "bootNonce", "overallBudgetMs", "settingsAuthority.version", "settingsAuthority.bytes",
            "settingsAuthority.sha256", "securityAuthorityEpoch", "a.role.name", "a.bytes", "a.sha256", "a.versionCode",
            "a.contractMinimum", "a.contractMaximum", "a.expectedSchema", "b.role.name", "b.bytes",
            "b.sha256", "b.versionCode", "b.contractMinimum", "b.contractMaximum", "b.expectedSchema",
        ).forEach { field -> assertTrue("ARM approval omits $field", canonical.contains(field)) }
    }

    @Test fun `accepted response pipeline completes before the prepared shutdown callback runs`() {
        val arm = routes.substring(
            routes.indexOf("private suspend fun armGuardDbCanary"),
            routes.indexOf("private data class GuardDbStageRequest"),
        )
        assertTrue(arm.contains("dependencies.prepare(exact) { callback -> shutdown = callback }"))
        val accepted = arm.substringAfter("handedOff = true")
        assertTrue(accepted.contains("HttpStatusCode.Accepted"))
        assertTrue(accepted.contains("requireNotNull(shutdown).invoke()"))
        assertTrue(
            "the service-drain callback must not run before the 202 response pipeline completes",
            accepted.indexOf("call.respondText(") < accepted.indexOf("requireNotNull(shutdown).invoke()"),
        )
    }

    @Test fun `failed sentinel and shutdown handoffs either clear exactly or contain the process`() {
        val arm = routes.substring(
            routes.indexOf("private suspend fun armGuardDbCanary"),
            routes.indexOf("private data class GuardDbStageRequest"),
        )
        val failedCommit = arm.substringAfter("is GuardDbSentinelCommit.Failed -> {")
            .substringBefore("GuardDbProcessAdmission.update(durable)")
        assertTrue(failedCommit.contains("commit.load !is GuardDbSentinelLoad.Absent"))
        assertTrue(failedCommit.contains("GuardDbProcessAdmission.update(commit.load)"))
        assertTrue(failedCommit.contains("dependencies.contain()"))

        val failedPrepare = arm.substringAfter("if (!dependencies.prepare(exact)")
            .substringBefore("handedOff = true")
        assertTrue(failedPrepare.contains("dependencies.sentinelStore.clear(request.session)"))
        assertTrue(failedPrepare.contains("if (cleared) GuardDbProcessAdmission.update(GuardDbSentinelLoad.Absent)"))
        assertTrue(failedPrepare.contains("else dependencies.contain()"))
        assertTrue(failedPrepare.contains("HttpStatusCode.Locked"))
    }

    @Test fun `OpenAPI describes the exact direct LAN attended bootstrap surface`() {
        val paths = JSONObject(TestSources.asset("openapi.json").readText()).getJSONObject("paths")
        val clock = paths.getJSONObject("/api/v1/guard-db/clock").getJSONObject("get")
        assertTrue(clock.getString("description").contains("elapsed_realtime_ms"))
        assertTrue(clock.getString("description").contains("minimum_overall_budget_ms"))
        assertTrue(clock.getString("description").contains("recovery_reserve_ms"))
        assertTrue(clock.getJSONObject("responses").getJSONObject("403")
            .getString("description").contains("Loopback"))
        val stage = paths.getJSONObject("/api/v1/guard-db/stage").getJSONObject("post")
        val stageSchema = stage.getJSONObject("requestBody").getJSONObject("content")
            .getJSONObject("application/json").getJSONObject("schema")
        assertEquals(setOf("token", "role"), stageSchema.getJSONArray("required").toSet())
        assertEquals(listOf("A", "B"), stageSchema.getJSONObject("properties")
            .getJSONObject("role").getJSONArray("enum").toList())
        assertTrue(stage.getString("description").contains("direct LAN"))
        assertTrue(stage.getJSONObject("responses").getJSONObject("202")
            .getString("description").contains("approval-required"))
        assertTrue(stage.getJSONObject("responses").getJSONObject("403")
            .getString("description").contains("Loopback"))

        val arm = paths.getJSONObject("/api/v1/guard-db/arm").getJSONObject("post")
        val armSchema = arm.getJSONObject("requestBody").getJSONObject("content")
            .getJSONObject("application/json").getJSONObject("schema")
        assertEquals(setOf("session", "overall_budget_ms"), armSchema.getJSONArray("required").toSet())
        assertEquals("^[0-9a-f]{64}$", armSchema.getJSONObject("properties")
            .getJSONObject("session").getString("pattern"))
        assertEquals("int64", armSchema.getJSONObject("properties")
            .getJSONObject("overall_budget_ms").getString("format"))
        val accepted = arm.getJSONObject("responses").getJSONObject("202")
        assertTrue(accepted.getString("description").contains("preparing-clean-proof"))
        assertTrue(accepted.getString("description").contains("approval-required"))
        assertTrue(arm.getString("description").contains("same-boot"))
        assertTrue(arm.getString("description").contains("physical approval"))
        assertTrue(arm.getString("description").contains("SETTINGS authority"))

        val retire = paths.getJSONObject("/api/v1/guard-db/evidence/retire").getJSONObject("post")
        val retireSchema = retire.getJSONObject("requestBody").getJSONObject("content")
            .getJSONObject("application/json").getJSONObject("schema")
        assertEquals(
            setOf("session", "generation", "evidence_sha256"),
            retireSchema.getJSONArray("required").toSet(),
        )
        assertEquals(
            9223372036854775806L,
            retireSchema.getJSONObject("properties").getJSONObject("generation").getLong("maximum"),
        )
        assertTrue(retire.getString("description").contains("GUARDRETIRE TERMINAL"))
        assertTrue(retire.getString("description").contains("never blindly replayed"))
        assertTrue(retire.getString("description").contains("no-backup storage"))
        val retireResponses = retire.getJSONObject("responses")
        val completed = retireResponses.getJSONObject("200").getJSONObject("content")
            .getJSONObject("application/json").getJSONObject("schema")
        assertEquals(
            setOf("ok", "state", "session", "retirement_generation", "settlement"),
            completed.getJSONArray("required").toSet(),
        )
        assertEquals(listOf("empty"), completed.getJSONObject("properties")
            .getJSONObject("state").getJSONArray("enum").toList())
        val acceptedDescription = retireResponses.getJSONObject("202").getString("description")
        assertTrue(acceptedDescription.contains("same direct LAN peer within 10 minutes"))
        assertTrue(acceptedDescription.contains("durable INTENT"))
        assertTrue(acceptedDescription.contains("mutation fence stays held"))
        assertTrue(acceptedDescription.contains("FINALIZED is never reported as actively RETIRING"))
        val pending = retireResponses.getJSONObject("202").getJSONObject("content")
            .getJSONObject("application/json").getJSONObject("schema")
            .getJSONArray("oneOf").getJSONObject(1)
        assertEquals(listOf("retiring"), pending.getJSONObject("properties")
            .getJSONObject("state").getJSONArray("enum").toList())
        assertFalse(retireResponses.getJSONObject("400").getString("description").contains("oversized"))
        assertTrue(retireResponses.has("413"))
        assertTrue(retireResponses.getJSONObject("403").getString("description").contains("Host/Origin"))
        assertTrue(retireResponses.has("423"))
    }

    private fun org.json.JSONArray.toList(): List<String> =
        (0 until length()).map(::getString)

    private fun org.json.JSONArray.toSet(): Set<String> = toList().toSet()
}
