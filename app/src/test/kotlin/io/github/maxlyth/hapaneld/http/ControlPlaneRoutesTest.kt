package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.backup.PanelBackup
import io.github.maxlyth.hapaneld.security.SensitiveOperation
import io.github.maxlyth.hapaneld.util.ByteLimitExceeded
import io.github.maxlyth.hapaneld.util.BoundedStreams
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
import io.ktor.server.routing.post
import io.ktor.server.response.respondText
import io.ktor.server.application.ApplicationCall
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

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
                        installedComponentVersion = { name -> if (name == "paneld") "0.9.3" else null },
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

        assertJsonPost(
            "name=paneld&version=v0.9.2",
            HttpStatusCode.Conflict,
            """{"status":"downgrade-refused"}""",
        )
        assertEquals(emptyList<Triple<String, String, String>>(), admitted)

        assertJsonPost(
            "name=paneld&version=v0.9.2&allow_downgrade=true",
            HttpStatusCode.OK,
            """{"status":"started"}""",
        )
        assertEquals(Triple("paneld", "update", "v0.9.2"), admitted.last())
        admitted.clear()

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
        var artifactId = 0
        fun artifact(bytes: ByteArray): PanelBackup.Artifact = PanelBackup.Artifact(
            temporary.newFile("backup-${++artifactId}.hpb").apply { writeBytes(bytes) },
        )
        var build: suspend (Boolean, String) -> PanelBackup.Artifact = { _, _ -> artifact(byteArrayOf(1, 2, 3)) }
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

        assertJsonPost(
            "include_companion=true",
            HttpStatusCode.BadRequest,
            """{"ok":false,"error":"passphrase-required","message":"A backup contains credentials. Supply a passphrase, or explicitly acknowledge plaintext export with allow_plaintext=1."}""",
            path = "/api/v1/backup",
        )
        assertEquals(emptyList<Pair<Boolean, String>>(), buildCalls)

        val held = assertNotNullTicket(InstallProgress.start("held"))
        try {
            assertJsonPost(
                "include_companion=true&passphrase=protected",
                HttpStatusCode.Conflict,
                """{"ok":false,"error":"busy"}""",
                path = "/api/v1/backup",
            )
            assertJsonPost(
                "include_companion=false&passphrase=expensive",
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
            "include_companion=1&allow_plaintext=1",
            HttpStatusCode.PayloadTooLarge,
            """{"ok":false,"error":"companion-backup-too-large"}""",
            path = "/api/v1/backup",
        )
        assertOperationLaneReleased()

        build = { _, _ -> throw CompanionBackupUnavailable("Companion needs \"su\"") }
        assertJsonPost(
            "include_companion=true&allow_plaintext=true",
            HttpStatusCode.UnprocessableEntity,
            """{"ok":false,"error":"Companion needs \"su\""}""",
            path = "/api/v1/backup",
        )
        assertOperationLaneReleased()

        val retainedSource = temporary.newFile("retained-route.zip").apply { writeText("secret") }
        val retainedPlaintext = object : java.io.File(retainedSource.path) {
            override fun delete(): Boolean = false
        }
        val withdrawnSource = temporary.newFile("withdrawn-route.hpb").apply { writeText("sealed") }
        var artifactDeleteAttempts = 0
        val withdrawnArtifact = object : java.io.File(withdrawnSource.path) {
            override fun delete(): Boolean {
                artifactDeleteAttempts++
                throw java.io.IOException("sealed cleanup failed")
            }
        }
        val ownedSource = temporary.newFile("owned-route.payload").apply { writeText("temporary") }
        var ownedDeleteAttempts = 0
        val ownedFile = object : java.io.File(ownedSource.path) {
            override fun delete(): Boolean {
                ownedDeleteAttempts++
                throw java.io.IOException("owned cleanup failed")
            }
        }
        var captureCloseAttempts = 0
        build = { _, _ ->
            withBackupCaptureAndPlaintext(
                capture = java.io.Closeable {
                    captureCloseAttempts++
                    throw java.io.IOException("capture close failed")
                },
                createPlaintext = { retainedPlaintext },
            ) { _, plain ->
                withBackupArtifactCleanup(
                    plain = plain,
                    sealed = { withdrawnArtifact },
                    ownedFiles = { listOf(ownedFile) },
                ) { encryptedBackupArtifact(plain, withdrawnArtifact) }
            }
        }
        assertJsonPost(
            "include_companion=true&passphrase=protected",
            HttpStatusCode.InsufficientStorage,
            """{"ok":false,"error":"backup-staging-retained","message":"Sensitive temporary backup data could not be removed. Check panel storage, then retry; no backup was downloaded."}""",
            path = "/api/v1/backup",
        )
        assertTrue(retainedSource.exists())
        assertTrue(withdrawnSource.exists())
        assertTrue(ownedSource.exists())
        assertEquals(2, artifactDeleteAttempts)
        assertEquals(1, ownedDeleteAttempts)
        assertEquals(1, captureCloseAttempts)
        assertFalse(BackupDeliveryGate.occupied())
        assertOperationLaneReleased()

        var laneHeldWhenArtifactDeleted = false
        var deliveryHeldWhenArtifactDeleted = false
        build = { includeCompanion, passphrase ->
            assertEquals(true, includeCompanion)
            assertEquals("secret", passphrase)
            val source = temporary.newFile("tracked-backup.hpb").apply { writeBytes(byteArrayOf(7, 8, 9)) }
            val tracked = object : java.io.File(source.path) {
                override fun delete(): Boolean {
                    laneHeldWhenArtifactDeleted = InstallProgress.running
                    deliveryHeldWhenArtifactDeleted = BackupDeliveryGate.occupied()
                    return super.delete()
                }
            }
            PanelBackup.Artifact(tracked)
        }
        val success = client.post("/api/v1/backup") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("include_companion=true&passphrase=secret")
        }
        assertEquals(HttpStatusCode.OK, success.status)
        assertEquals(ContentType.Application.OctetStream, success.contentType())
        assertEquals("attachment; filename=\"test-panel-backup.hpb\"", success.headers[HttpHeaders.ContentDisposition])
        assertArrayEquals(byteArrayOf(7, 8, 9), success.bodyAsBytes())
        assertFalse("slow delivery does not monopolize the destructive operation lane", laneHeldWhenArtifactDeleted)
        assertTrue("artifact lifetime remains inside the single-delivery gate", deliveryHeldWhenArtifactDeleted)
        assertOperationLaneReleased()

        build = { includeCompanion, passphrase ->
            assertFalse(includeCompanion)
            assertEquals("", passphrase)
            PanelBackup.Artifact(
                temporary.newFile("plaintext-backup.zip").apply { writeBytes(byteArrayOf(4, 5, 6)) },
                "zip",
            )
        }
        val plaintext = client.post("/api/v1/backup") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("include_companion=false&allow_plaintext=1")
        }
        assertEquals(HttpStatusCode.OK, plaintext.status)
        assertEquals("attachment; filename=\"test-panel-backup.zip\"", plaintext.headers[HttpHeaders.ContentDisposition])
        assertArrayEquals(byteArrayOf(4, 5, 6), plaintext.bodyAsBytes())
        assertOperationLaneReleased()
    }

    @Test
    fun apkUploadRouteComposesCapabilityLifetimeBoundsInspectionAndTokenHandoff() = testApplication {
        var enabled = false
        var rootAvailable = true
        var stagingFails = false
        var identity: UploadedApkIdentity? = UploadedApkIdentity("example.panel", "1.2.3", null)
        var closeDuringInspection = false
        var usableSpace = Long.MAX_VALUE
        var timeOutReceipt = false
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
            usableSpace = { usableSpace },
            receiveBody = { input, output, maxBytes ->
                if (timeOutReceipt) throw BodyReceiptTimeout()
                BoundedStreams.copy(input, output, maxBytes)
            },
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
        val beforeOversized = stagedFiles.size
        assertUpload("12345", HttpStatusCode.PayloadTooLarge, """{"ok":false,"error":"upload-too-large"}""")
        assertEquals(beforeOversized, stagedFiles.size)

        usableSpace = 64L * 1024L * 1024L + 2L
        assertUpload("apk", HttpStatusCode.InsufficientStorage, """{"ok":false,"error":"insufficient-storage"}""")
        assertFalse(stagedFiles.last().exists())

        usableSpace = Long.MAX_VALUE
        timeOutReceipt = true
        assertUpload("apk", HttpStatusCode.RequestTimeout, """{"ok":false,"error":"upload-timeout"}""")
        assertFalse(stagedFiles.last().exists())
        timeOutReceipt = false

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
        assertUpload("next", HttpStatusCode.Conflict, """{"ok":false,"error":"upload-busy"}""")
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

    @Test fun bodyReaderEnforcesAbsoluteElapsedDeadlineWithoutSleeping() {
        var now = 0L
        val source = object : ByteArrayInputStream("payload".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, length).also { now = 2_000_000L }
        }

        assertThrows(BodyReceiptTimeout::class.java) {
            DeadlineBoundedBody.readBytes(
                source,
                maxBytes = 64,
                timeoutMs = 1,
                nanoTime = { now },
                deadlineFactory = { _, _ -> AutoCloseable {} },
            )
        }
    }

    @Test fun sharedMaterializedBodyReaderMapsTheDeadlineWithoutWaiting() = testApplication {
        application {
            routing {
                post("/bounded") {
                    val receipt = receiveBoundedBody(
                        call,
                        maxBytes = 64,
                        timeoutMs = 7,
                        reader = { _, maxBytes, timeoutMs ->
                            assertEquals(64L, maxBytes)
                            assertEquals(7L, timeoutMs)
                            throw BodyReceiptTimeout()
                        },
                    )
                    when (receipt) {
                        BoundedBodyReceipt.TimedOut ->
                            call.respondText("timeout", status = HttpStatusCode.RequestTimeout)
                        BoundedBodyReceipt.TooLarge ->
                            call.respondText("too-large", status = HttpStatusCode.PayloadTooLarge)
                        is BoundedBodyReceipt.Received ->
                            call.respondText("received")
                    }
                }
            }
        }

        val response = client.post("/bounded") { setBody("payload") }
        assertEquals(HttpStatusCode.RequestTimeout, response.status)
        assertEquals("timeout", response.bodyAsText())
    }

    @Test fun uploadCapacityKeepsSafetyMarginAndBudgetsUnknownBodiesAtTheMaximum() {
        val margin = 64L * 1024L * 1024L
        assertEquals(0L, uploadStagingLimit(margin, maxPayloadBytes = 4L))
        assertEquals(3L, uploadStagingLimit(margin + 3L, maxPayloadBytes = 4L))
        assertEquals(4L, uploadStagingLimit(margin + 5L, maxPayloadBytes = 4L))
        assertEquals(0L, uploadStagingLimit(Long.MAX_VALUE, maxPayloadBytes = -1L))
    }

    @Test fun apkCommitApprovalNamesTheInspectedPackageVersionAndSigner() = testApplication {
        val pending = PendingUploadStore(newToken = { "inspected-token" }).apply { open() }
        val lease = (pending.begin() as PendingUploadStore.BeginResult.Granted).lease
        val identity = UploadedApkIdentity("com.example.panel", "2.4.1", "abcdef0123456789deadbeef")
        pending.stage(lease, temporary.newFile("inspected.apk").apply { writeText("apk") }, identity)
        var summary = ""
        val upload = ApkUploadRouteDependencies(
            enabled = { true },
            rootAvailable = { true },
            pending = pending,
            createStagingFile = { error("unused") },
            inspect = { error("unused") },
            startInstall = { _, ticket -> InstallProgress.finish(ticket, "done") },
        )
        application {
            routing {
                controlPlaneRoutes(dependencies(apkUpload = upload, authorize = { _, _, _, value ->
                    summary = value
                    true
                }))
            }
        }

        assertCommit("inspected-token", HttpStatusCode.OK, """{"status":"started"}""")
        assertTrue(summary.contains("com.example.panel"))
        assertTrue(summary.contains("2.4.1"))
        assertTrue(summary.contains("abcdef0123456789"))
    }

    @Test fun apkApprovalSummaryStripsDisplayControlsBoundsEveryFieldAndKeepsSignerAheadOfVersion() {
        val summary = uploadedApkApprovalSummary(
            UploadedApkIdentity(
                pkg = "p".repeat(32) + "\u202E\n" + "p".repeat(64),
                signerSha256 = "a".repeat(32) + "\u2066\u0000" + "a".repeat(64),
                version = "v".repeat(10) + "\u202D\u2029" + "v".repeat(64),
            ),
        )

        val boundedPackage = "p".repeat(64)
        val boundedSigner = "a".repeat(64)
        val boundedVersion = "v".repeat(20)
        assertEquals(
            "Install $boundedPackage (signer $boundedSigner) version $boundedVersion",
            summary,
        )
        assertTrue(summary.indexOf(boundedPackage) < summary.indexOf(boundedSigner))
        assertTrue(summary.indexOf(boundedSigner) < summary.indexOf(boundedVersion))
        assertTrue("summary must fit the approval broker's 180-character cap", summary.length <= 180)
        assertFalse(summary.any { Character.getType(it) == Character.CONTROL.toInt() })
        assertFalse(summary.any { Character.getType(it) == Character.FORMAT.toInt() })
    }

    private fun dependencies(
        playAudio: (String) -> Boolean = { true },
        installComponent: (String, String, String) -> Boolean = { _, _, _ -> true },
        installedComponentVersion: (String) -> String? = { null },
        buildBackup: suspend (Boolean, String) -> PanelBackup.Artifact = { _, _ ->
            PanelBackup.Artifact(temporary.newFile("unused-backup.hpb"))
        },
        apkUpload: ApkUploadRouteDependencies = unusedApkUpload(),
        authorize: suspend (ApplicationCall, SensitiveOperation, String, String) -> Boolean = { _, _, _, _ -> true },
    ) = ControlPlaneRouteDependencies(
        playAudio = playAudio,
        installComponent = installComponent,
        installedComponentVersion = installedComponentVersion,
        buildBackup = buildBackup,
        backupFileStem = { "test-panel" },
        apkUpload = apkUpload,
        authorize = authorize,
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
