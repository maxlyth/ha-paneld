package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.device.profile.DeviceFacts
import io.github.maxlyth.hapaneld.device.profile.PassiveProfileConfidence
import io.github.maxlyth.hapaneld.device.profile.PassiveProfileDraft
import io.github.maxlyth.hapaneld.device.profile.PassiveProfileObservation
import io.github.maxlyth.hapaneld.device.profile.PassiveProfileReport
import io.github.maxlyth.hapaneld.device.profile.ProfileActivationPhase
import io.github.maxlyth.hapaneld.device.profile.ProfileActivationState
import io.github.maxlyth.hapaneld.device.profile.ProfileAdmin
import io.github.maxlyth.hapaneld.device.profile.ProfileDiff
import io.github.maxlyth.hapaneld.device.profile.ProfileDriverDescriptor
import io.github.maxlyth.hapaneld.device.profile.ProfileDriverKind
import io.github.maxlyth.hapaneld.device.profile.ProfileFieldDescriptor
import io.github.maxlyth.hapaneld.device.profile.ProfileIssue
import io.github.maxlyth.hapaneld.device.profile.ProfileIssueSeverity
import io.github.maxlyth.hapaneld.device.profile.ProfileMutation
import io.github.maxlyth.hapaneld.device.profile.ProfileOrigin
import io.github.maxlyth.hapaneld.device.profile.ProfilePreview
import io.github.maxlyth.hapaneld.device.profile.ProfileRef
import io.github.maxlyth.hapaneld.device.profile.ProfileRisk
import io.github.maxlyth.hapaneld.device.profile.ProfileSchemaDescriptor
import io.github.maxlyth.hapaneld.device.profile.ProfileSelection
import io.github.maxlyth.hapaneld.device.profile.ProfileStatus
import io.github.maxlyth.hapaneld.device.profile.ProfileSummary
import io.github.maxlyth.hapaneld.device.profile.ShizukuRecommendation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class ProfileRoutesTest {
    @Test
    fun catalogSchemaAndDriversExposeOnlyStableAuthoringMetadata() = testApplication {
        val admin = FakeProfileAdmin()
        application { routing { route("/api/v1") { profileRoutes(ProfileRouteDependencies(admin)) } } }

        val response = client.get("/api/v1/profiles")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val body = JSONObject(response.bodyAsText())
        assertEquals(3L, body.getLong("catalog_revision"))
        assertEquals(2, body.getJSONArray("profiles").length())
        val local = body.getJSONArray("profiles").getJSONObject(1)
        assertEquals("recommended", local.getString("shizuku_recommendation"))
        assertTrue(local.getBoolean("compatible"))
        assertEquals(0, local.getJSONArray("issues").length())
        val bundled = body.getJSONArray("profiles").getJSONObject(0)
        assertTrue(bundled.getBoolean("last_known_good"))
        assertEquals("0.1.0", bundled.getString("content_version"))
        assertEquals("draft", bundled.getString("maturity"))
        assertFalse(response.bodyAsText().contains("consent", ignoreCase = true))
        assertFalse(response.bodyAsText().contains("readiness", ignoreCase = true))

        val schema = JSONObject(client.get("/api/v1/profiles/schema").bodyAsText())
        assertEquals(1, schema.getInt("schema"))
        assertEquals(64, schema.getInt("max_bytes"))
        assertEquals("identity.id", schema.getJSONArray("fields").getJSONObject(0).getString("path"))

        val drivers = JSONObject(client.get("/api/v1/profiles/drivers").bodyAsText()).getJSONArray("drivers")
        assertEquals("relay", drivers.getJSONObject(0).getString("kind"))
        assertTrue(drivers.getJSONObject(0).getBoolean("privileged"))
    }

    @Test
    fun yamlRoutesEnforceContentTypeBoundsPreviewTokenAndExactRevisionExport() = testApplication {
        val admin = FakeProfileAdmin()
        application { routing { route("/api/v1") { profileRoutes(ProfileRouteDependencies(admin)) } } }

        assertEquals(
            HttpStatusCode.UnsupportedMediaType,
            client.post("/api/v1/profiles/preview") { contentType(ContentType.Application.Json); setBody("{}") }.status,
        )
        assertEquals(
            HttpStatusCode.PayloadTooLarge,
            client.post("/api/v1/profiles/preview") {
                contentType(ContentType.parse("application/yaml"))
                setBody("x".repeat(65))
            }.status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/api/v1/profiles/preview") {
                contentType(ContentType.parse("application/yaml"))
                setBody(byteArrayOf(0xc3.toByte(), 0x28))
            }.status,
        )

        val yaml = "schema: 2\nid: imported.test\n"
        val previewResponse = client.post("/api/v1/profiles/preview") {
            contentType(ContentType.parse("application/yaml"))
            setBody(yaml)
        }
        assertEquals(HttpStatusCode.OK, previewResponse.status)
        val preview = JSONObject(previewResponse.bodyAsText())
        assertEquals("preview-token", preview.getString("preview_token"))
        assertTrue(preview.getBoolean("compatible"))
        assertEquals("identity.id", preview.getJSONArray("diff_from_active").getJSONObject(0).getString("path"))

        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/api/v1/profiles/import") {
                contentType(ContentType.parse("application/yaml")); setBody(yaml)
            }.status,
        )
        val imported = client.post("/api/v1/profiles/import") {
            contentType(ContentType.parse("application/yaml"))
            header("X-Profile-Preview-Token", "preview-token")
            setBody(yaml)
        }
        assertEquals(HttpStatusCode.OK, imported.status)
        val importedRef = JSONObject(imported.bodyAsText()).getJSONObject("imported_ref")
        assertEquals(sha256(yaml), importedRef.getString("revision"))
        assertEquals(yaml, admin.importedYaml)

        val exported = client.get("/api/v1/profiles/${admin.bundled.ref.id}/revisions/${admin.bundled.ref.revision}")
        assertEquals(HttpStatusCode.OK, exported.status)
        assertEquals("application/yaml", exported.headers[HttpHeaders.ContentType])
        assertTrue(exported.headers[HttpHeaders.ContentDisposition].orEmpty().endsWith(".yaml\""))
        assertEquals(admin.bundledYaml, exported.bodyAsText())
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/api/v1/profiles/bad%2Fid/revisions/not-a-hash").status,
        )
    }

    @Test
    fun activationRollbackAndDeleteRequireConfirmationAndCatalogCompareAndSet() = testApplication {
        val admin = FakeProfileAdmin()
        var restarts = 0
        application {
            routing {
                route("/api/v1") {
                    profileRoutes(ProfileRouteDependencies(admin, requestRestart = { restarts++; true }))
                }
            }
        }

        val selection = """{"id":"${admin.local.ref.id}","revision":"${admin.local.ref.revision}","expected_catalog_revision":3}"""
        assertEquals(HttpStatusCode.Conflict, postJson("/api/v1/profiles/activate", selection).status)
        assertEquals(0, admin.selections.size)

        val stale = """{"id":"${admin.local.ref.id}","revision":"${admin.local.ref.revision}","expected_catalog_revision":2,"confirm":true}"""
        assertEquals(HttpStatusCode.Conflict, postJson("/api/v1/profiles/activate", stale).status)
        assertEquals(0, restarts)

        val activate = selection.dropLast(1) + ",\"confirm\":true}"
        val accepted = postJson("/api/v1/profiles/activate", activate)
        assertEquals(HttpStatusCode.Accepted, accepted.status)
        assertEquals(1, restarts)
        assertEquals(ProfileSelection.Pinned(admin.local.ref), admin.selections.last())

        val automatic = """{"auto":true,"expected_catalog_revision":3,"confirm":true}"""
        assertEquals(HttpStatusCode.Accepted, postJson("/api/v1/profiles/select", automatic).status)
        assertEquals(ProfileSelection.Auto, admin.selections.last())
        assertEquals(2, restarts)

        val wrongRollback = """{"expected_catalog_revision":2,"confirm":true}"""
        assertEquals(HttpStatusCode.Conflict, postJson("/api/v1/profiles/rollback", wrongRollback).status)
        val rollback = """{"expected_catalog_revision":3,"confirm":true}"""
        assertEquals(HttpStatusCode.Accepted, postJson("/api/v1/profiles/rollback", rollback).status)
        assertEquals(ProfileSelection.Pinned(admin.bundled.ref), admin.selections.last())

        val deleteWithoutConfirmation = """{"id":"${admin.local.ref.id}","revision":"${admin.local.ref.revision}","expected_catalog_revision":3}"""
        assertEquals(HttpStatusCode.Conflict, postJson("/api/v1/profiles/delete", deleteWithoutConfirmation).status)
        val delete = deleteWithoutConfirmation.dropLast(1) + ",\"confirm\":true}"
        assertEquals(HttpStatusCode.OK, postJson("/api/v1/profiles/delete", delete).status)
        assertEquals(admin.local.ref, admin.deleted)
    }

    @Test
    fun activationIsAbortedWhenDestructiveLaneIsBusyOrRestartSchedulingFails() = testApplication {
        val admin = FakeProfileAdmin()
        var aborts = 0
        var restartAllowed = false
        var scheduleAccepted = true
        var abortPersisted = true
        application {
            routing {
                route("/api/v1") {
                    profileRoutes(
                        ProfileRouteDependencies(
                            admin = admin,
                            requestRestart = { scheduleAccepted },
                            restartAllowed = { restartAllowed },
                            abortPendingRestart = { aborts++; abortPersisted },
                        ),
                    )
                }
            }
        }
        val request = """{"id":"${admin.local.ref.id}","revision":"${admin.local.ref.revision}","expected_catalog_revision":3,"confirm":true}"""

        val busy = postJson("/api/v1/profiles/activate", request)
        assertEquals(HttpStatusCode.ServiceUnavailable, busy.status)
        assertEquals("destructive-operation-in-progress", JSONObject(busy.bodyAsText()).getString("error"))
        assertEquals(1, aborts)

        restartAllowed = true
        scheduleAccepted = false
        val unavailable = postJson("/api/v1/profiles/activate", request)
        assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
        assertEquals("profile-restart-unavailable", JSONObject(unavailable.bodyAsText()).getString("error"))
        assertEquals(2, aborts)

        abortPersisted = false
        val latent = postJson("/api/v1/profiles/activate", request)
        assertEquals(HttpStatusCode.ServiceUnavailable, latent.status)
        val latentBody = JSONObject(latent.bodyAsText())
        assertEquals("profile-activation-abort-persist-failed", latentBody.getString("error"))
        assertTrue(latentBody.getBoolean("activation_pending"))
        assertTrue(latentBody.getString("message").contains("remains pending"))
        assertEquals(3, aborts)
    }

    @Test fun restartRejectionDistinguishesDurableAbortFailureForRestoreCallers() {
        val recovered = rejectFailedProfileRestart(
            restartAllowed = true,
            requestRestart = { false },
            abortPendingRestart = { true },
        )
        assertEquals("profile-restart-unavailable", recovered?.error)
        assertTrue(recovered!!.abortPersisted)

        val latent = rejectFailedProfileRestart(
            restartAllowed = true,
            requestRestart = { false },
            abortPendingRestart = { false },
        )
        assertEquals("profile-activation-abort-persist-failed", latent?.error)
        assertFalse(latent!!.abortPersisted)
        assertTrue(latent.message.contains("remains pending"))
    }

    @Test
    fun passiveDraftAndProbeUseOnlyInjectedReadOnlyProviderAndSanitizedProjection() = testApplication {
        val admin = FakeProfileAdmin()
        val report = PassiveProfileReport(
            generatedAtEpochMs = 123,
            facts = DeviceFacts("model", "device", "version"),
            observations = listOf(
                PassiveProfileObservation("display.width", "1280", "android-display", PassiveProfileConfidence.OBSERVED),
            ),
        )
        var probes = 0
        application {
            routing {
                route("/api/v1") {
                    profileRoutes(
                        ProfileRouteDependencies(
                            admin,
                            readOnly = ProfileRouteReadOnlyProviders(
                                template = { "schema: 2\n" },
                                deviceDraft = { PassiveProfileDraft("schema: 2\n# TODO\n", report, emptyList()) },
                                latestReport = { report },
                                probe = { probes++; report },
                            ),
                        ),
                    )
                }
            }
        }

        assertEquals("schema: 2\n", client.get("/api/v1/profiles/template").bodyAsText())
        assertEquals("schema: 2\n# TODO\n", client.get("/api/v1/profiles/device-draft").bodyAsText())
        val latest = JSONObject(client.get("/api/v1/profiles/report").bodyAsText())
        assertEquals("observed", latest.getJSONArray("items").getJSONObject(0).getString("status"))
        val probe = client.post("/api/v1/profiles/probe") {
            contentType(ContentType.parse("application/yaml")); setBody("schema: 2\n")
        }
        assertEquals(HttpStatusCode.OK, probe.status)
        assertNotNull(JSONObject(probe.bodyAsText()).getJSONObject("report"))
        assertEquals(1, probes)
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.postJson(path: String, body: String) =
        client.post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private class FakeProfileAdmin : ProfileAdmin {
        val bundled = summary("generic", "b".repeat(64), ProfileOrigin.BUNDLED, active = true)
        var local = summary("local.test", "a".repeat(64), ProfileOrigin.IMPORTED, selected = true)
        val bundledYaml = "schema: 2\nid: generic\n"
        var importedYaml: String? = null
        val selections = mutableListOf<ProfileSelection>()
        var deleted: ProfileRef? = null
        private var profiles = mutableListOf(bundled, local)

        override fun schema() = ProfileSchemaDescriptor(
            schema = 1,
            maxBytes = 64,
            fields = listOf(ProfileFieldDescriptor("identity.id", "string", required = true, description = "Stable id")),
        )

        override fun drivers() = listOf(
            ProfileDriverDescriptor("relay.sysfs", ProfileDriverKind.RELAY, "Compiled relay driver", privileged = true),
        )

        override fun status() = ProfileStatus(
            catalogRevision = 3,
            selection = ProfileSelection.Pinned(local.ref),
            active = bundled,
            activation = ProfileActivationState(
                phase = ProfileActivationPhase.ACTIVE,
                generation = 8,
                previous = ProfileSelection.Pinned(bundled.ref),
                desired = ProfileSelection.Pinned(local.ref),
            ),
            lastKnownGood = ProfileSelection.Pinned(bundled.ref),
        )

        override fun list(): List<ProfileSummary> = profiles

        override fun preview(rawYaml: String) = ProfilePreview(
            previewToken = "preview-token",
            contentSha256 = sha256(rawYaml),
            expiresAtEpochMs = 999,
            summary = local.copy(ref = ProfileRef("imported.test", sha256(rawYaml))),
            issues = emptyList(),
            diffFromActive = listOf(ProfileDiff("identity.id", "generic", "imported.test")),
            compatible = true,
        )

        override fun importProfile(rawYaml: String, previewToken: String): ProfileMutation {
            if (previewToken != "preview-token") return rejected(ProfileIssue("preview_token", "stale preview token"))
            importedYaml = rawYaml
            local = local.copy(ref = ProfileRef("imported.test", sha256(rawYaml)))
            profiles = mutableListOf(bundled, local)
            return ProfileMutation.Success(status(), restartRequired = false, message = "saved")
        }

        override fun exportProfile(ref: ProfileRef): String? = if (ref == bundled.ref) bundledYaml else null

        override fun select(selection: ProfileSelection, expectedCatalogRevision: Long): ProfileMutation {
            if (expectedCatalogRevision != 3L) return rejected(ProfileIssue("catalog_revision", "stale catalog revision"))
            selections += selection
            return ProfileMutation.Success(status(), restartRequired = true, message = "restart scheduled")
        }

        override fun rollbackToLastKnownGood(expectedCatalogRevision: Long): ProfileMutation {
            val currentStatus = status()
            val target = currentStatus.lastKnownGood
                ?: return ProfileMutation.Rejected(
                    currentStatus,
                    listOf(ProfileIssue(ProfileIssueSeverity.ERROR, "rollback", "No last-known-good profile.")),
                )
            return select(target, expectedCatalogRevision)
        }

        override fun deleteProfile(ref: ProfileRef, expectedCatalogRevision: Long): ProfileMutation {
            if (expectedCatalogRevision != 3L) return rejected(ProfileIssue("catalog_revision", "stale catalog revision"))
            deleted = ref
            return ProfileMutation.Success(status(), restartRequired = false, message = "deleted")
        }

        private fun rejected(issue: ProfileIssue) = ProfileMutation.Rejected(status(), listOf(issue))

        private fun ProfileIssue(path: String, message: String) =
            ProfileIssue(ProfileIssueSeverity.ERROR, path, message)

        private fun summary(
            id: String,
            revision: String,
            origin: ProfileOrigin,
            active: Boolean = false,
            selected: Boolean = false,
        ) = ProfileSummary(
            ref = ProfileRef(id, revision),
            displayName = id,
            origin = origin,
            schema = 1,
            minCoreVersion = null,
            matchesThisDevice = true,
            active = active,
            selected = selected,
            shizukuRecommendation = if (origin == ProfileOrigin.IMPORTED) ShizukuRecommendation.RECOMMENDED else ShizukuRecommendation.NONE,
            risks = if (origin == ProfileOrigin.IMPORTED) setOf(ProfileRisk.ROOT_PATHS) else emptySet(),
            contentVersion = "0.1.0",
        )
    }

    companion object {
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
