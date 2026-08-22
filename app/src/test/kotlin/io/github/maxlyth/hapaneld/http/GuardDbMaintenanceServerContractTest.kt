package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardDbMaintenanceServerContractTest {
    private val source by lazy { TestSources.kotlin("http/GuardDbMaintenanceServer.kt").readText() }
    private val service by lazy { TestSources.kotlin("GuardDbMaintenanceService.kt").readText() }
    private val adb by lazy { TestSources.kotlin("control/AdbController.kt").readText() }

    @Test fun `writer free successor exposes only the exact same boot maintenance surface`() {
        val routes = source.substring(source.indexOf("routing {"), source.indexOf("fun stop()"))
        assertEquals(
            listOf(
                "GET /health", "GET /status", "GET /evidence", "POST /arm/commit", "POST /refusal",
                "POST /cancel", "POST /action",
            ),
            Regex("(get|post)\\(\"([^\"]+)\"\\)").findAll(routes)
                .map { "${it.groupValues[1].uppercase()} ${it.groupValues[2]}" }.toList(),
        )
        val admission = source.substring(source.indexOf("intercept(ApplicationCallPipeline.Plugins)"),
            source.indexOf("routing {"))
        assertTrue(admission.contains("!isLocalSource(peer) || isLoopbackPeer(peer)"))
        assertTrue(admission.indexOf("!isLocalSource(peer) || isLoopbackPeer(peer)") <
            admission.indexOf("OriginGuard.allowed("))
        assertTrue(admission.contains("OriginGuard.hostAllowed"))
        assertTrue(source.contains("configureGuardDbMaintenanceApplication(this)"))

        val status = source.substring(
            source.indexOf("private suspend fun respondStatus"),
            source.indexOf("private suspend fun ApplicationCall.respondJsonError"),
        )
        assertTrue(status.contains("\\\"error\\\":\${Json.str(error.orEmpty())}"))
    }

    @Test fun `ARM commit binds prepared proof helper zero state and exact duration budget across approval`() {
        val commit = source.substring(
            source.indexOf("private suspend fun commitPreparedArm"),
            source.indexOf("private suspend fun submitExactARefusal"),
        )
        assertTrue(commit.contains("sentinel.state != io.github.maxlyth.hapaneld.util.GuardDbSentinelState.BASELINE_READY"))
        assertTrue(commit.contains("request.session != sentinel.session"))
        assertTrue(commit.contains("prepared.matches(sentinel)"))
        assertTrue(commit.contains("body + \"\\u0000\" + prepared.canonical()"))
        assertFalse("duration budget must not be reinterpreted against the successor clock",
            commit.contains("prepared.overallBudgetMs - monotonicMs()"))
        assertTrue(commit.contains("empty?.phase != GuardDbMaintenanceProtocol.Phase.EMPTY"))
        assertTrue(commit.contains("empty.generation != request.generation"))
        assertTrue(commit.contains("exactHttpApprovalPayload(call"))
        assertTrue(commit.contains("SensitiveOperation.GUARD_DB_MAINTENANCE"))

        val afterApproval = commit.substringAfter("ApprovalBroker.Decision.APPROVED")
        assertTrue(afterApproval.contains("currentPrepared != prepared"))
        assertTrue(afterApproval.contains("currentManifest != manifest"))
        assertTrue(afterApproval.contains("current?.phase != GuardDbMaintenanceProtocol.Phase.EMPTY"))
        assertTrue(afterApproval.contains("current.generation != request.generation"))
        assertTrue(afterApproval.indexOf("call.respondText(") <
            afterApproval.indexOf("GuardDbArmCoordinator.submitPrepared"))
        assertTrue(afterApproval.contains("HttpStatusCode.Accepted"))
        assertTrue(afterApproval.contains("\\\"settlement\\\":\\\"poll-status\\\""))
    }

    @Test fun `every successor mutation binds and atomically reproves exact security authority`() {
        val routes = listOf(
            "ARM commit" to source.substring(
                source.indexOf("private suspend fun commitPreparedArm"),
                source.indexOf("private suspend fun submitExactARefusal"),
            ),
            "refusal" to source.substring(
                source.indexOf("private suspend fun submitExactARefusal"),
                source.indexOf("private suspend fun cancelBeforeCustody"),
            ),
            "cancel" to source.substring(
                source.indexOf("private suspend fun cancelBeforeCustody"),
                source.indexOf("private suspend fun respondMutation"),
            ),
            "action" to source.substring(
                source.indexOf("post(\"/action\")"),
                source.indexOf("fun stop()"),
            ),
        )
        routes.forEach { (name, route) ->
            assertTrue("$name omits ready proof", route.contains("security.readyEpoch()"))
            assertTrue("$name challenge omits epoch", route.contains("SECURITY_EPOCH"))
            assertTrue("$name omits atomic commit", route.contains("security.commit(securityEpoch)"))
            assertTrue("$name omits changed handling", route.contains("GuardDbMaintenanceSecurityResult.Changed"))
            assertTrue("$name omits refused handling", route.contains("GuardDbMaintenanceSecurityResult.Refused"))
            assertTrue("$name commits before mutation", route.indexOf("security.commit(securityEpoch)") <
                route.indexOf(
                    when (name) {
                        "ARM commit" -> "GuardDbArmCoordinator.submitPrepared"
                        "refusal" -> "client.refusal("
                        "cancel" -> "client.cancel("
                        else -> "settleGuardDbAction("
                    },
                ))
        }
    }

    @Test fun `successor security authority composes Hardened relay and durable ADB proof under one epoch`() {
        val ready = service.substring(
            service.indexOf("fun securityReady()"),
            service.indexOf("server = GuardDbMaintenanceServer"),
        )
        assertFalse("writer-free successor must not construct a Config or SQLite owner", service.contains("Config("))
        assertFalse(service.contains("AppState("))
        assertFalse(service.contains("EntityCatalogStore("))
        assertFalse(service.contains("SQLiteDatabase"))
        assertTrue(ready.contains("expectedEpoch = sentinel.securityAuthorityEpoch"))
        assertTrue(ready.contains("RemoteDebugSecurityTransitionGate::hardenedAuthorityEpoch"))
        assertTrue(ready.contains("relayRunning = { CdpRelay.running }"))
        assertTrue(ready.contains("AdbController.proveMaintenanceRemoteDebugOff(applicationContext)"))
        assertTrue(ready.contains("RemoteDebugSecurityTransitionGate.withLock"))
        assertTrue(ready.contains("sentinel.securityAuthorityEpoch.takeIf { securityReady() }"))
        assertTrue(ready.contains("RemoteDebugSecurityTransitionGate.withEpoch(expectedEpoch)"))
        assertTrue(ready.indexOf("if (securityReady())") < ready.indexOf("action()"))

        val adbProof = adb.substring(
            adb.indexOf("internal fun proveMaintenanceRemoteDebugOff"),
            adb.indexOf("internal object RemoteDebugSecurityTransitionGate"),
        )
        assertTrue(adbProof.contains("marker.isAbsentDurably()"))
        assertTrue(adbProof.contains("proveSettledNetworkAdbInactive("))
        assertTrue(adbProof.contains("networkAdbListenerActiveState("))
    }

    @Test fun `action challenge and commit replay the complete canonical preview status`() {
        val action = source.substring(
            source.indexOf("post(\"/action\")"),
            source.indexOf("fun stop()"),
        )
        assertTrue(action.contains("previewStatus.canonical()"))
        assertTrue(action.contains("STATUS"))
        assertTrue(action.contains("status != previewStatus"))
        assertTrue(action.indexOf("status != previewStatus") < action.indexOf("settleGuardDbAction("))

        val canonical = source.substring(
            source.indexOf("private fun GuardDbMaintenanceProtocol.Status.canonical"),
            source.indexOf("private fun refusalPending"),
        )
        listOf(
            "generation", "phase.name", "session", "bootNonce", "role", "apkSha256", "versionCode",
            "schema", "baselineAppStateCount", "error", "outcome", "overallDeadlineElapsedMs",
            "forwardDeadlineElapsedMs",
        ).forEach { field -> assertTrue("action status canonical omits $field", canonical.contains(field)) }
    }

    @Test fun `pre custody cancel proves exact authority and clears local holds only after helper EMPTY`() {
        val cancel = source.substring(
            source.indexOf("private suspend fun cancelBeforeCustody"),
            source.indexOf("private suspend fun respondMutation"),
        )
        assertTrue(cancel.contains("guardDbCancellationAllowed(status, request, sentinel)"))
        assertTrue(cancel.contains("body + \"\\u0000\" + prepared.canonical()"))
        assertTrue(cancel.contains("exactHttpApprovalPayload(call"))
        assertTrue(cancel.contains("ApprovalBroker.Decision.APPROVED"))
        val afterApproval = cancel.substringAfter("ApprovalBroker.Decision.APPROVED")
        assertTrue(afterApproval.contains("current != status"))
        assertTrue(afterApproval.contains("client.cancel(sentinel.session, current.generation)"))
        assertTrue(afterApproval.contains("client.statusProbe()"))
        assertTrue(afterApproval.indexOf("if (current.phase != GuardDbMaintenanceProtocol.Phase.EMPTY)") <
            afterApproval.indexOf("preparedStore.clear(sentinel.session)"))
        assertTrue(afterApproval.contains("sentinelStore.clear(sentinel.session)"))
        assertTrue(afterApproval.indexOf("sentinelStore.clear(sentinel.session)") <
            afterApproval.indexOf("onFinalized()"))

        val admission = source.substring(
            source.indexOf("private fun guardDbCancellationAllowed"),
            source.indexOf("private fun settleGuardDbRefusal"),
        )
        assertTrue(admission.contains(
            "GuardDbMaintenanceProtocol.Phase.STAGING -> status.error == null || " +
                "status.error == \"FAILED_NO_MUTATION\"",
        ))
        assertFalse("CAPTURE_INTENT must remain hold-only", admission.contains(
            "status.error == \"CAPTURE_INTENT\"",
        ))
        assertTrue(admission.contains("GuardDbMaintenanceProtocol.Phase.PREPARED -> status.error == null"))
    }

    @Test fun `exact A refusal is admitted only in the explicit wait phase with installed B identity`() {
        val admission = source.substring(
            source.indexOf("private fun refusalPending"),
            source.indexOf("private fun guardDbCancellationAllowed"),
        )
        assertTrue(admission.contains("status.phase == GuardDbMaintenanceProtocol.Phase.WAIT_A_REFUSAL"))
        assertTrue(admission.contains("status.role == GuardDbMaintenanceProtocol.Role.B"))
        assertTrue(admission.contains("status.apkSha256 == sentinel.bSha256"))
        assertTrue(admission.contains("status.versionCode == sentinel.bVersionCode"))
        assertTrue(admission.contains("status.schema == sentinel.bSchema"))
        assertTrue("recovery-withheld must not admit a new exact-A refusal",
            !admission.contains("GuardDbMaintenanceProtocol.Phase.RECOVERY_WITHHELD"))
    }

    @Test fun `exact A refusal canonicalizes checkpoint closes and stabilizes before both proofs`() {
        val refusal = source.substring(
            source.indexOf("private suspend fun submitExactARefusal"),
            source.indexOf("private suspend fun cancelBeforeCustody"),
        )
        val initial = refusal.substringBefore("val payload = exactHttpApprovalPayload")
        assertTrue(initial.contains("refusalProof(prepared)"))

        val repeated = refusal.substringAfter("mutation.withLock")
        assertTrue(repeated.indexOf("refusalProof(prepared)") < repeated.indexOf("repeated != proof"))

        val defaultProof = source.substring(
            source.indexOf("private val refusalProof"),
            source.indexOf("private val exactFinalStatus"),
        )
        assertTrue(defaultProof.indexOf("canonicalizeGuardDbMainForRefusal(exactContext)") <
            defaultProof.indexOf("proveExactARefusalUnderB(exactContext, prepared, staging)"))

        val canonicalSource = TestSources.kotlin("util/GuardDbStartupHealth.kt").readText()
        val canonical = canonicalSource.substring(
            canonicalSource.indexOf("internal fun canonicalizeGuardDbMainForRefusal"),
            canonicalSource.indexOf("internal fun exactGuardDbFinalStatus", canonicalSource.indexOf(
                "internal fun canonicalizeGuardDbMainForRefusal",
            )),
        )
        assertTrue(canonical.contains("collectClosedCanonicalGuardDbProof("))
        assertTrue(canonical.contains("checkpoint = { database?.let(::checkpointGuardDbTruncate) == true }"))
        assertTrue(canonical.contains("close = { requireNotNull(database).close() }"))
        assertTrue(canonical.contains("stable = { stableGuardDbCanonicalMain(context.applicationContext) }"))
    }

    @Test fun `ARM commit parser is exact and the route requires helper generation zero`() {
        val parser = source.substring(
            source.indexOf("private fun parsePreparedCommit"),
            source.indexOf("private fun parseAction"),
        )
        assertTrue(parser.contains("setOf(\"session\", \"generation\")"))
        assertTrue(parser.contains("GuardDbMaintenanceProtocol.validSession(session)"))
        assertTrue(parser.contains("generation < 0L"))
        val commit = source.substring(
            source.indexOf("private suspend fun commitPreparedArm"),
            source.indexOf("private suspend fun submitExactARefusal"),
        )
        assertTrue(commit.contains("request.generation != 0L"))
    }

    @Test fun `indeterminate mutations reconcile by status and never replay`() {
        val settlement = source.substring(
            source.indexOf("private fun settleGuardDbAction"),
            source.indexOf("internal fun guardDbActionSettlementPhases"),
        )
        assertEquals(1, Regex("client\\.action\\(").findAll(settlement).count())
        assertEquals(1, Regex("client\\.statusProbe\\(").findAll(settlement).count())
        assertTrue(settlement.indexOf("result is GuardDbMaintenanceProtocol.Result.Rejected") <
            settlement.indexOf("val probe = client.statusProbe()"))
        assertTrue(settlement.indexOf("result == GuardDbMaintenanceProtocol.Result.Unreachable") <
            settlement.indexOf("val probe = client.statusProbe()"))
        assertTrue(settlement.contains("status.generation > request.generation"))
        assertTrue(settlement.contains("status.session == sentinel.session"))
        assertTrue(settlement.contains("status.bootNonce == sentinel.bootNonce"))
        assertTrue(settlement.contains("GuardDbMutationSettlement.Held(probe)"))
    }

    @Test fun `finalized handoff requires typed terminal outcome and exact local final proof`() {
        val routes = source.substring(source.indexOf("post(\"/action\")"),
            source.indexOf("fun stop()"))
        assertTrue(routes.contains("exactFinalStatus(initial.status)"))
        assertTrue(routes.contains("settlement.status.phase == GuardDbMaintenanceProtocol.Phase.FINALIZED"))
        assertTrue(routes.contains("settlement.status.outcome != null"))
        assertTrue(routes.indexOf("settlement.status.outcome != null") < routes.indexOf("onFinalized()"))
    }

    @Test fun `OpenAPI separates preparation from writer free custody commit`() {
        val paths = JSONObject(TestSources.asset("openapi.json").readText()).getJSONObject("paths")
        val commit = paths.getJSONObject("/api/v1/guard-db/arm/commit").getJSONObject("post")
        val schema = commit.getJSONObject("requestBody").getJSONObject("content")
            .getJSONObject("application/json").getJSONObject("schema")
        assertEquals(setOf("session", "generation"), schema.getJSONArray("required").toSet())
        assertEquals(0, schema.getJSONObject("properties").getJSONObject("generation").getInt("minimum"))
        assertEquals(0, schema.getJSONObject("properties").getJSONObject("generation").getInt("maximum"))
        assertTrue(commit.getString("description").contains("writer-free"))
        assertTrue(commit.getString("description").contains("budget"))
        assertTrue(commit.getString("description").contains("SETTINGS authority"))
        assertTrue(commit.getString("description").contains("security epoch"))
        assertFalse(commit.getJSONObject("responses").has("410"))
        listOf("arm/commit", "refusal", "cancel", "action").forEach { route ->
            val responses = paths.getJSONObject("/api/v1/guard-db/$route").getJSONObject("post")
                .getJSONObject("responses")
            assertTrue("$route omits Hardened debug-off refusal", responses.has("412"))
        }
        val cancelDescription = paths.getJSONObject("/api/v1/guard-db/cancel").getJSONObject("post")
            .getString("description")
        assertTrue(cancelDescription.contains("FAILED_NO_MUTATION"))
        assertTrue(cancelDescription.contains("CAPTURE_INTENT is hold-only"))
        val accepted = commit.getJSONObject("responses").getJSONObject("202").getString("description")
        assertTrue(accepted.contains("approval-required"))
        assertTrue(accepted.contains("poll-status"))
    }

    private fun org.json.JSONArray.toSet(): Set<String> =
        (0 until length()).map(::getString).toSet()
}
