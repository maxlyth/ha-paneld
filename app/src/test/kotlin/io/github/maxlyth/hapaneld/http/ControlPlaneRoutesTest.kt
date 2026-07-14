package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.util.ByteLimitExceeded
import io.github.maxlyth.hapaneld.util.InstallProgress
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ControlPlaneRoutesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun playRoutesParseBoundedBodiesAndReflectAudioAdmission() = testApplication {
        val accepted = mutableListOf<String>()
        var admitting = true
        application {
            routing {
                controlPlaneRoutes(
                    dependencies(
                        playAudio = { url -> admitting.also { if (it) accepted += url } },
                    ),
                )
            }
        }

        val root = client.post("/play") { setBody("https://panel.test/root.mp3") }
        assertEquals(HttpStatusCode.OK, root.status)
        assertEquals("playing\n", root.bodyAsText())

        val versioned = client.post("/api/v1/play") { setBody("""{"url":"https://panel.test/v1.mp3"}""") }
        assertEquals(HttpStatusCode.OK, versioned.status)
        assertEquals("playing\n", versioned.bodyAsText())
        assertEquals(listOf("https://panel.test/root.mp3", "https://panel.test/v1.mp3"), accepted)

        val missing = client.post("/play") { setBody("not a URL") }
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        assertEquals("no-url\n", missing.bodyAsText())

        admitting = false
        val stopping = client.post("/play") { setBody("https://panel.test/stopping.mp3") }
        assertEquals(HttpStatusCode.ServiceUnavailable, stopping.status)
        assertEquals("stopping\n", stopping.bodyAsText())
        assertEquals(2, accepted.size)

        val oversized = client.post("/play") { setBody("x".repeat(PaneldServer.MAX_PLAY_BODY_BYTES.toInt() + 1)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
        assertEquals("body-too-large\n", oversized.bodyAsText())
    }

    @Test
    fun componentRouteValidatesBeforeAdmissionAndPreservesBusyContract() = testApplication {
        val admitted = mutableListOf<Triple<String, String, String>>()
        var admitting = true
        application {
            routing {
                controlPlaneRoutes(
                    dependencies(
                        installComponent = { name, action, version ->
                            admitted += Triple(name, action, version)
                            admitting
                        },
                    ),
                )
            }
        }

        assertJsonPost("name=unknown", HttpStatusCode.BadRequest, """{"status":"bad-component"}""")
        assertJsonPost("name=paneld&action=remove", HttpStatusCode.BadRequest, """{"status":"bad-action"}""")
        assertJsonPost("name=paneld&version=bad%2Ftag", HttpStatusCode.BadRequest, """{"status":"bad-version"}""")
        assertEquals(emptyList<Triple<String, String, String>>(), admitted)

        assertJsonPost("name=paneld", HttpStatusCode.OK, """{"status":"started"}""")
        assertEquals(listOf(Triple("paneld", "update", "")), admitted)

        admitting = false
        assertJsonPost(
            "name=companion&action=reinstall&version=v0.9.2-rc2",
            HttpStatusCode.OK,
            """{"status":"busy"}""",
        )
        assertEquals(Triple("companion", "reinstall", "v0.9.2-rc2"), admitted.last())
    }

    @Test
    fun backupRouteComposesSharedAdmissionBoundedFailuresAndDownloadMetadata() = testApplication {
        var build: suspend (Boolean, String) -> ByteArray = { _, _ -> byteArrayOf(1, 2, 3) }
        val buildCalls = mutableListOf<Pair<Boolean, String>>()
        application {
            routing {
                controlPlaneRoutes(
                    dependencies(
                        buildBackup = { includeCompanion, passphrase ->
                            buildCalls += includeCompanion to passphrase
                            build(includeCompanion, passphrase)
                        },
                    ),
                )
            }
        }

        val held = assertNotNullTicket(InstallProgress.start("held"))
        try {
            assertJsonPost(
                "include_companion=true",
                HttpStatusCode.Conflict,
                """{"ok":false,"error":"busy"}""",
                path = "/api/v1/backup",
            )
            assertEquals(emptyList<Pair<Boolean, String>>(), buildCalls)
        } finally {
            InstallProgress.finish(held, "released")
        }

        build = { _, _ -> throw ByteLimitExceeded(32) }
        assertJsonPost(
            "include_companion=1",
            HttpStatusCode.PayloadTooLarge,
            """{"ok":false,"error":"companion-backup-too-large"}""",
            path = "/api/v1/backup",
        )
        assertOperationLaneReleased()

        build = { _, _ -> throw CompanionBackupUnavailable("Companion needs \"su\"") }
        assertJsonPost(
            "include_companion=true",
            HttpStatusCode.UnprocessableEntity,
            """{"ok":false,"error":"Companion needs \"su\""}""",
            path = "/api/v1/backup",
        )
        assertOperationLaneReleased()

        build = { includeCompanion, passphrase ->
            assertEquals(true, includeCompanion)
            assertEquals("secret", passphrase)
            byteArrayOf(7, 8, 9)
        }
        val success = client.post("/api/v1/backup") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("include_companion=true&passphrase=secret")
        }
        assertEquals(HttpStatusCode.OK, success.status)
        assertEquals(ContentType.Application.OctetStream, success.contentType())
        assertEquals("attachment; filename=\"test-panel-backup.hpb\"", success.headers[HttpHeaders.ContentDisposition])
        assertArrayEquals(byteArrayOf(7, 8, 9), success.bodyAsBytes())
        assertOperationLaneReleased()
    }

    @Test
    fun apkUploadRouteComposesCapabilityLifetimeBoundsInspectionAndTokenHandoff() = testApplication {
        var enabled = false
        var rootAvailable = true
        var stagingFails = false
        var identity: UploadedApkIdentity? = UploadedApkIdentity("example.panel", "1.2.3", null)
        var closeDuringInspection = false
        var installed: PendingUploadStore.Entry? = null
        var fileId = 0
        val stagedFiles = mutableListOf<java.io.File>()
        val pending = PendingUploadStore { "upload-token" }
        val upload = ApkUploadRouteDependencies(
            enabled = { enabled },
            rootAvailable = { rootAvailable },
            pending = pending,
            createStagingFile = {
                if (stagingFails) error("no staging space")
                temporary.newFile("upload-${++fileId}.apk").also { stagedFiles += it }
            },
            inspect = {
                if (closeDuringInspection) pending.close()
                identity
            },
            startInstall = { entry, progress ->
                installed = entry
                InstallProgress.finish(progress, "installed")
            },
            maxBytes = 4,
        )
        application { routing { controlPlaneRoutes(dependencies(apkUpload = upload)) } }

        assertUpload("apk", HttpStatusCode.Forbidden, """{"ok":false,"error":"disabled"}""")
        assertCommit("upload-token", HttpStatusCode.Forbidden, """{"status":"disabled"}""")

        enabled = true
        rootAvailable = false
        assertUpload("apk", HttpStatusCode.ServiceUnavailable, """{"ok":false,"error":"no-root"}""")

        rootAvailable = true
        assertUpload("apk", HttpStatusCode.ServiceUnavailable, """{"ok":false,"error":"stopping"}""")

        pending.open()
        stagingFails = true
        assertUpload("apk", HttpStatusCode.InternalServerError, """{"ok":false,"error":"upload-staging-failed"}""")

        stagingFails = false
        assertUpload("12345", HttpStatusCode.PayloadTooLarge, """{"ok":false,"error":"upload-too-large"}""")
        assertFalse(stagedFiles.last().exists())

        assertUpload("", HttpStatusCode.BadRequest, """{"ok":false,"error":"upload-failed"}""")
        assertFalse(stagedFiles.last().exists())

        identity = null
        assertUpload("apk", HttpStatusCode.BadRequest, """{"ok":false,"error":"not-an-apk"}""")
        assertFalse(stagedFiles.last().exists())

        identity = UploadedApkIdentity("example.panel", "1.2.3", null)
        closeDuringInspection = true
        assertUpload("late", HttpStatusCode.ServiceUnavailable, """{"ok":false,"error":"stopping"}""")
        assertFalse(stagedFiles.last().exists())

        pending.open()
        closeDuringInspection = false
        assertUpload(
            "good",
            HttpStatusCode.OK,
            """{"ok":true,"token":"upload-token","package":"example.panel","version":"1.2.3","signer":"unsigned"}""",
        )
        assertCommit("wrong-token", HttpStatusCode.Conflict, """{"status":"stale-or-missing"}""")

        val held = assertNotNullTicket(InstallProgress.start("held"))
        try {
            assertCommit("upload-token", HttpStatusCode.OK, """{"status":"busy"}""")
        } finally {
            InstallProgress.finish(held, "released")
        }

        assertCommit("upload-token", HttpStatusCode.OK, """{"status":"started"}""")
        assertNotNull(installed)
        assertTrue(installed!!.file.exists())
        assertArrayEquals("good".toByteArray(), installed!!.file.readBytes())
        installed!!.file.delete()
        assertFalse(InstallProgress.running)
    }

    private fun dependencies(
        playAudio: (String) -> Boolean = { true },
        installComponent: (String, String, String) -> Boolean = { _, _, _ -> true },
        buildBackup: suspend (Boolean, String) -> ByteArray = { _, _ -> byteArrayOf() },
        apkUpload: ApkUploadRouteDependencies = unusedApkUpload(),
    ) = ControlPlaneRouteDependencies(
        playAudio = playAudio,
        installComponent = installComponent,
        buildBackup = buildBackup,
        backupFileStem = { "test-panel" },
        apkUpload = apkUpload,
    )

    private fun unusedApkUpload() = ApkUploadRouteDependencies(
        enabled = { false },
        rootAvailable = { false },
        pending = PendingUploadStore(),
        createStagingFile = { error("unused") },
        inspect = { error("unused") },
        startInstall = { _, _ -> error("unused") },
    )

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.assertJsonPost(
        body: String,
        status: HttpStatusCode,
        responseBody: String,
        path: String = "/api/v1/install/component",
    ) {
        val response = client.post(path) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(body)
        }
        assertEquals(status, response.status)
        assertEquals(responseBody, response.bodyAsText())
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.assertUpload(
        body: String,
        status: HttpStatusCode,
        responseBody: String,
    ) {
        val response = client.post("/api/v1/install/apk") { setBody(body) }
        assertEquals(status, response.status)
        assertEquals(responseBody, response.bodyAsText())
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.assertCommit(
        token: String,
        status: HttpStatusCode,
        responseBody: String,
    ) {
        val response = client.post("/api/v1/install/apk/commit") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("token=$token")
        }
        assertEquals(status, response.status)
        assertEquals(responseBody, response.bodyAsText())
    }

    private fun assertOperationLaneReleased() {
        val ticket = assertNotNullTicket(InstallProgress.start("probe"))
        InstallProgress.finish(ticket, "released")
        assertFalse(InstallProgress.running)
    }

    private fun assertNotNullTicket(ticket: InstallProgress.Ticket?): InstallProgress.Ticket {
        assertNotNull(ticket)
        return ticket!!
    }
}
