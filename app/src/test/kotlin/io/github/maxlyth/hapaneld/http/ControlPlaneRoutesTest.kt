package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.backup.PanelBackup
import io.github.maxlyth.hapaneld.security.SensitiveOperation
import io.github.maxlyth.hapaneld.util.AppInstaller
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

        // A body that genuinely does not fit the free space is still refused before anything is written.
        usableSpace = 2L
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
        // An upload that fits its actual staging requirement is admitted with no surplus demanded: four
        // usable bytes for a four-byte body. The retired fixed 64 MiB reserve refused exactly this.
        usableSpace = 4L
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

    /** A URL is a source for the SAME review flow, so it must reach the same staged file, the same
     *  token and the same commit route — and must refuse everything the upload route refuses, before
     *  it touches the network. Each `fetched` assertion pins whether the download was actually
     *  attempted, because a gate that passes by never running is the failure mode worth catching. */
    @Test
    fun apkFetchFromUrlRouteComposesCapabilityUrlAdmissionBoundedFailuresAndTokenHandoff() = testApplication {
        var enabled = false
        var rootAvailable = true
        var stagingFails = false
        var identity: UploadedApkIdentity? = UploadedApkIdentity("example.panel", "1.2.3", null)
        var result = AppInstaller.DownloadResult.Succeeded
        var usableSpace = Long.MAX_VALUE
        var installed: PendingUploadStore.Entry? = null
        var fileId = 0
        var approved = true
        val approvalAsked = mutableListOf<Triple<SensitiveOperation, String, String>>()
        val stagedFiles = mutableListOf<java.io.File>()
        val fetched = mutableListOf<Pair<String, Long>>()
        val owner = ApkFetchOwner()
        val pending = PendingUploadStore { "fetch-token" }
        val upload = ApkUploadRouteDependencies(
            enabled = { enabled },
            rootAvailable = { rootAvailable },
            pending = pending,
            createStagingFile = {
                if (stagingFails) error("no staging space")
                temporary.newFile("fetch-${++fileId}.apk").also { stagedFiles += it }
            },
            inspect = { identity },
            startInstall = { entry, progress ->
                installed = entry
                InstallProgress.finish(progress, "installed")
            },
            maxBytes = 4,
            usableSpace = { usableSpace },
            fetch = { url, dest, maxBytes, abort ->
                fetched += url to maxBytes
                if (result == AppInstaller.DownloadResult.Succeeded) dest.writeBytes("good".toByteArray())
                result
            },
            fetchOwner = owner,
        )
        application {
            routing {
                controlPlaneRoutes(
                    dependencies(
                        apkUpload = upload,
                        authorize = { call, operation, payload, summary ->
                            approvalAsked += Triple(operation, payload, summary)
                            if (approved) true else {
                                call.respondText(
                                    """{"ok":false,"error":"approval-required"}""",
                                    ContentType.Application.Json,
                                    HttpStatusCode.Accepted,
                                )
                                false
                            }
                        },
                    ),
                )
            }
        }
        val url = "https://example.test/app.apk"

        assertFetch(url, HttpStatusCode.Forbidden, """{"ok":false,"error":"disabled"}""")

        enabled = true
        rootAvailable = false
        assertFetch(url, HttpStatusCode.ServiceUnavailable, """{"ok":false,"error":"no-root"}""")
        rootAvailable = true

        // A refused scheme, a relative reference and an empty field are rejected as bad input, never as
        // a failed download, so an operator can tell a typo from an unreachable host.
        assertFetch("http://example.test/app.apk", HttpStatusCode.BadRequest, """{"ok":false,"error":"invalid-url"}""")
        assertFetch("example.test/app.apk", HttpStatusCode.BadRequest, """{"ok":false,"error":"invalid-url"}""")
        assertFetch("", HttpStatusCode.BadRequest, """{"ok":false,"error":"invalid-url"}""")

        // A malformed owner identifier is refused as input, never silently replaced with an unowned fetch.
        assertFetch(url, HttpStatusCode.BadRequest, """{"ok":false,"error":"invalid-request"}""", request = "")
        assertFetch(url, HttpStatusCode.BadRequest, """{"ok":false,"error":"invalid-request"}""", request = "bad id!")

        // THE gate this whole revision exists for: authority is settled BEFORE the panel is aimed at
        // anything. An unapproved caller must not be able to make the panel emit even one request.
        approved = false
        assertFetch(url, HttpStatusCode.Accepted, """{"ok":false,"error":"approval-required"}""")
        assertEquals(0, fetched.size)
        // The approver is told which host the panel would be aimed at, under its own operation.
        assertEquals(SensitiveOperation.APK_FETCH, approvalAsked.last().first)
        assertEquals("Download an APK from example.test", approvalAsked.last().third)

        // What is approved must be THE DESTINATION. An approval bound to anything else would let a
        // granted approval be replayed to send the panel somewhere the operator never saw.
        val approvedPayload = approvalAsked.last().second
        assertFetch("https://elsewhere.test/app.apk", HttpStatusCode.Accepted, """{"ok":false,"error":"approval-required"}""")
        assertNotEquals(
            "a different destination must not reuse the same approval payload",
            approvedPayload,
            approvalAsked.last().second,
        )
        assertFetch(url, HttpStatusCode.Accepted, """{"ok":false,"error":"approval-required"}""")
        assertEquals(
            "the same destination must present the same approval payload, so an approval is usable on retry",
            approvedPayload,
            approvalAsked.last().second,
        )
        assertEquals(0, fetched.size)
        approved = true

        assertFetch(url, HttpStatusCode.ServiceUnavailable, """{"ok":false,"error":"stopping"$OWNER}""")

        pending.open()
        stagingFails = true
        assertFetch(url, HttpStatusCode.InternalServerError, """{"ok":false,"error":"upload-staging-failed"$OWNER}""")
        stagingFails = false

        usableSpace = 0L
        assertFetch(url, HttpStatusCode.InsufficientStorage, """{"ok":false,"error":"insufficient-storage"$OWNER}""")
        assertFalse(stagedFiles.last().exists())

        // Nothing above this line may have reached the network: capability, URL shape, lifetime and
        // storage are all decided before the panel is asked to fetch anything.
        assertEquals(0, fetched.size)

        // A ceiling imposed by the panel's own free space reports as storage, not as an oversized file.
        usableSpace = 2L
        result = AppInstaller.DownloadResult.TooLarge
        assertFetch(url, HttpStatusCode.InsufficientStorage, """{"ok":false,"error":"insufficient-storage"$OWNER}""")
        assertEquals(1, fetched.size)
        assertEquals(url to 2L, fetched.last())
        assertFalse(stagedFiles.last().exists())

        usableSpace = Long.MAX_VALUE
        assertFetch(url, HttpStatusCode.PayloadTooLarge, """{"ok":false,"error":"fetch-too-large"$OWNER}""")
        assertEquals(url to 4L, fetched.last())
        assertFalse(stagedFiles.last().exists())

        result = AppInstaller.DownloadResult.TimedOut
        assertFetch(url, HttpStatusCode.RequestTimeout, """{"ok":false,"error":"fetch-timeout"$OWNER}""")
        assertFalse(stagedFiles.last().exists())

        result = AppInstaller.DownloadResult.Failed
        assertFetch(url, HttpStatusCode.BadGateway, """{"ok":false,"error":"fetch-failed"$OWNER}""")
        assertFalse(stagedFiles.last().exists())

        // Bytes that arrive but are not an APK are refused after inspection, and still leave nothing.
        result = AppInstaller.DownloadResult.Succeeded
        identity = null
        assertFetch(url, HttpStatusCode.BadRequest, """{"ok":false,"error":"not-an-apk"$OWNER}""")
        assertFalse(stagedFiles.last().exists())

        // Every failure above released its lease: this fetch is admitted rather than answered busy.
        identity = UploadedApkIdentity("example.panel", "1.2.3", null)
        assertFetch(
            url,
            HttpStatusCode.OK,
            """{"ok":true,"token":"fetch-token","package":"example.panel","version":"1.2.3","signer":"unsigned"$OWNER}""",
        )

        // One staged APK at a time, whichever source produced it — a second fetch and an upload both
        // contend for the same slot, so a replacement cannot appear behind an already-issued token.
        assertFetch(url, HttpStatusCode.Conflict, """{"ok":false,"error":"upload-busy"$OWNER}""")
        assertUpload("next", HttpStatusCode.Conflict, """{"ok":false,"error":"upload-busy"}""")
        assertCommit("wrong-token", HttpStatusCode.Conflict, """{"status":"stale-or-missing"}""")

        // The unchanged commit route installs exactly the fetched bytes.
        assertCommit("fetch-token", HttpStatusCode.OK, """{"status":"started"}""")
        assertNotNull(installed)
        assertTrue(installed!!.file.exists())
        assertArrayEquals("good".toByteArray(), installed!!.file.readBytes())
        installed!!.file.delete()
        assertFalse(InstallProgress.running)
    }

    /** Cancel must stop the operator's own download and nothing else. A cancel that stopped "whatever
     *  is running" would abort the replacement of a fetch the operator had already given up on — which
     *  is precisely the moment they are most likely to press it. */
    @Test
    fun apkFetchCancellationStopsOnlyTheRequestThatOwnsItAndFreesTheSlot() = testApplication {
        var cancelDuring: String? = null
        var cancelReported: Boolean? = null
        var fileId = 0
        val stagedFiles = mutableListOf<java.io.File>()
        val owner = ApkFetchOwner()
        val pending = PendingUploadStore { "fetch-token" }.apply { open() }
        val upload = ApkUploadRouteDependencies(
            enabled = { true },
            rootAvailable = { true },
            pending = pending,
            createStagingFile = { temporary.newFile("cancel-${++fileId}.apk").also { stagedFiles += it } },
            inspect = { UploadedApkIdentity("example.panel", "1.2.3", null) },
            startInstall = { _, ticket -> InstallProgress.finish(ticket, "done") },
            maxBytes = 16,
            usableSpace = { Long.MAX_VALUE },
            fetch = { _, dest, _, abort ->
                // A cancel arriving mid-transfer, driven deterministically instead of with threads.
                cancelDuring?.let { cancelReported = owner.cancel(it) }
                if (abort.isAborted) {
                    AppInstaller.DownloadResult.Aborted
                } else {
                    dest.writeBytes("good".toByteArray())
                    AppInstaller.DownloadResult.Succeeded
                }
            },
            fetchOwner = owner,
        )
        application { routing { controlPlaneRoutes(dependencies(apkUpload = upload)) } }
        val url = "https://example.test/app.apk"

        // A cancel naming somebody else's request stops nothing, and the download completes normally.
        cancelDuring = "someone-elses-request"
        assertFetch(
            url,
            HttpStatusCode.OK,
            """{"ok":true,"token":"fetch-token","package":"example.panel","version":"1.2.3","signer":"unsigned"$OWNER}""",
        )
        assertEquals(false, cancelReported)
        assertCommit("fetch-token", HttpStatusCode.OK, """{"status":"started"}""")
        assertFalse(InstallProgress.running)

        // The owning request stops its own download, and says so rather than blaming the link.
        cancelDuring = FETCH_REQUEST
        assertFetch(url, HttpStatusCode(499, "Client Closed Request"), """{"ok":false,"error":"cancelled"$OWNER}""")
        assertEquals(true, cancelReported)
        assertFalse("a cancelled download must leave nothing staged", stagedFiles.last().exists())

        // Cancelling released the slot immediately: the next fetch is admitted, not answered busy.
        cancelDuring = null
        assertFetch(
            url,
            HttpStatusCode.OK,
            """{"ok":true,"token":"fetch-token","package":"example.panel","version":"1.2.3","signer":"unsigned"$OWNER}""",
        )
        pending.clear()
    }

    @Test fun apkFetchCancelRouteRefusesMalformedOwnersAndReportsWhenNothingWasStopped() = testApplication {
        val upload = ApkUploadRouteDependencies(
            enabled = { true },
            rootAvailable = { true },
            pending = PendingUploadStore().apply { open() },
            createStagingFile = { error("unused") },
            inspect = { error("unused") },
            startInstall = { _, _ -> error("unused") },
        )
        application { routing { controlPlaneRoutes(dependencies(apkUpload = upload)) } }

        assertCancel("", HttpStatusCode.BadRequest, """{"ok":false,"error":"invalid-request"}""")
        assertCancel("has space", HttpStatusCode.BadRequest, """{"ok":false,"error":"invalid-request"}""")
        // Honest about doing nothing, rather than reporting a success it did not perform.
        assertCancel("nothing-in-flight", HttpStatusCode.OK, """{"ok":true,"cancelled":false}""")
    }

    /** The abort must win a race it cannot see: an operator can press Cancel while the panel is still
     *  connecting, before there is any connection to close. */
    @Test fun downloadAbortRefusesAConnectionAttachedAfterTheOperatorCancelled() {
        val abort = io.github.maxlyth.hapaneld.util.DownloadAbort()
        assertFalse(abort.isAborted)
        abort.abort()
        assertTrue(abort.isAborted)

        var disconnected = false
        val connection = object : java.net.HttpURLConnection(java.net.URL("https://example.test/app.apk")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnected = true }
        }
        assertFalse("a download must not start once its owner has cancelled", abort.attach(connection))
        assertTrue("the late connection must be closed, not left open", disconnected)
    }

    @Test fun apkFetchRequestIdAdmissionAcceptsOnlyBoundedOpaqueIdentifiers() {
        assertEquals("req-1", validApkFetchRequestId("  req-1  "))
        assertEquals("A_b-9", validApkFetchRequestId("A_b-9"))
        assertNull(validApkFetchRequestId(""))
        assertNull(validApkFetchRequestId("   "))
        assertNull(validApkFetchRequestId("has space"))
        assertNull(validApkFetchRequestId("quote\"injection"))
        assertNull(validApkFetchRequestId("newline\nin-it"))
        assertNull(validApkFetchRequestId("a".repeat(APK_FETCH_REQUEST_ID_MAX_CHARS + 1)))
        assertEquals(
            "a".repeat(APK_FETCH_REQUEST_ID_MAX_CHARS),
            validApkFetchRequestId("a".repeat(APK_FETCH_REQUEST_ID_MAX_CHARS)),
        )
    }

    @Test fun apkFetchUrlAdmissionAcceptsOnlyExplicitBoundedHttpsLinks() {
        assertEquals("https://example.test/app.apk", validApkFetchUrl("  https://example.test/app.apk  "))
        assertEquals("HTTPS://example.test/app.apk", validApkFetchUrl("HTTPS://example.test/app.apk"))

        assertNull(validApkFetchUrl("http://example.test/app.apk"))
        assertNull(validApkFetchUrl("ftp://example.test/app.apk"))
        assertNull(validApkFetchUrl("file:///data/local/tmp/app.apk"))
        assertNull(validApkFetchUrl("example.test/app.apk"))
        assertNull(validApkFetchUrl("/app.apk"))
        assertNull(validApkFetchUrl(""))
        assertNull(validApkFetchUrl("   "))
        assertNull(validApkFetchUrl("https:///app.apk"))
        assertNull(validApkFetchUrl("https://example.test/a b.apk"))

        // The bound is on the whole link, and it is inclusive at the limit.
        assertEquals("https://example.test/ok.apk", validApkFetchUrl("https://example.test/ok.apk", maxChars = 27))
        assertNull(validApkFetchUrl("https://example.test/ok.apk", maxChars = 26))
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

    @Test fun uploadCapacityAdmitsTheActualRequirementAndBudgetsUnknownBodiesAtTheMaximum() {
        assertEquals(0L, uploadStagingLimit(usableBytes = 0L, maxPayloadBytes = 4L))
        assertEquals(3L, uploadStagingLimit(usableBytes = 3L, maxPayloadBytes = 4L))
        assertEquals(4L, uploadStagingLimit(usableBytes = Long.MAX_VALUE, maxPayloadBytes = 4L))
        assertEquals(0L, uploadStagingLimit(usableBytes = Long.MAX_VALUE, maxPayloadBytes = -1L))
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

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.assertFetch(
        url: String,
        status: HttpStatusCode,
        responseBody: String,
        request: String = FETCH_REQUEST,
    ) {
        val response = client.post("/api/v1/install/apk/from-url") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                "url=" + java.net.URLEncoder.encode(url, "UTF-8") +
                    "&request=" + java.net.URLEncoder.encode(request, "UTF-8"),
            )
        }
        assertEquals(status, response.status)
        assertEquals(responseBody, response.bodyAsText())
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.assertCancel(
        request: String,
        status: HttpStatusCode,
        responseBody: String,
    ) {
        val response = client.post("/api/v1/install/apk/fetch/cancel") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("request=" + java.net.URLEncoder.encode(request, "UTF-8"))
        }
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

    private companion object {
        const val FETCH_REQUEST = "req-1"

        /** Every answer to a request-owned fetch echoes its owner, so a client can drop a stale reply. */
        const val OWNER = ",\"request\":\"$FETCH_REQUEST\""
    }
}
