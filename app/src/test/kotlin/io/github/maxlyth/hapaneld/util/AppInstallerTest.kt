package io.github.maxlyth.hapaneld.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import io.github.maxlyth.hapaneld.persistence.StateQuiescence
import java.net.URL
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * [AppInstaller.httpsRedirect] — the download redirect gate: only ever follow a hop to an HTTPS
 * target, and resolve relative `Location` headers against the current URL. `java.net.URL` is a pure
 * JVM class, so this needs no device.
 */
class AppInstallerTest {
    @Test fun successfulSelfInstallKeepsStateQuiescedWhileFailedInstallReopensIt() {
        val successReopened = AtomicBoolean()
        val successful = StateQuiescence { successReopened.set(true) }
        AppInstaller.finishSelfReplaceQuiescence(successful, installSucceeded = true)
        assertFalse(successReopened.get())

        val failureReopened = AtomicBoolean()
        val failed = StateQuiescence { failureReopened.set(true) }
        AppInstaller.finishSelfReplaceQuiescence(failed, installSucceeded = false)
        assertTrue(failureReopened.get())
    }

    @Test fun anUnwritableConfigRevisionWarnsAndTheUpgradeContinues() {
        // The private revision is defense in depth, not a precondition — losing it must not strand
        // the panel on the old build, so quiescence still runs and its lease is still returned.
        val warnings = mutableListOf<String>()
        val lease = StateQuiescence { }

        val prepared = AppInstaller.prepareSelfReplace(
            snapshot = { false },
            quiesce = { lease },
            warn = warnings::add,
        )

        assertSame("the upgrade must continue into quiescence", lease, prepared)
        assertEquals(listOf(AppInstaller.SNAPSHOT_UNWRITABLE_WARNING), warnings)
    }

    @Test fun aWrittenConfigRevisionIsTakenBeforeStateIsQuiesced() {
        val order = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        AppInstaller.prepareSelfReplace(
            snapshot = { order.add("snapshot"); true },
            quiesce = { order.add("quiesce"); StateQuiescence { } },
            warn = warnings::add,
        )

        assertEquals(listOf("snapshot", "quiesce"), order)
        assertTrue("a written revision must not warn", warnings.isEmpty())
    }

    @Test fun storageTooSmallForTheActualApkIsRefused() {
        assertEquals("a 100 MB APK cannot be staged in 50 MB", 0L, AppInstaller.downloadCeiling(100 * MIB, 50 * MIB))
        assertEquals("an undeclared length on a full filesystem is refused", 0L, AppInstaller.downloadCeiling(-1L, 0L))
    }

    @Test fun noFixedSurplusIsReservedAboveTheApkItself() {
        // 110 MB free for a 100 MB APK is viable, and was refused only by the retired 64 MB reserve.
        val ceiling = AppInstaller.downloadCeiling(100 * MIB, 110 * MIB)

        assertTrue("an otherwise viable update must be admitted", ceiling > 0L)
        assertTrue("the download must not be bounded below the APK itself", ceiling >= 100 * MIB)
    }

    private val github = URL("https://github.com/maxlyth/ha-paneld/releases/download/v1/app.apk")

    @Test fun followsAbsoluteHttpsRedirect() {
        val next = AppInstaller.httpsRedirect(github, "https://objects.githubusercontent.com/x/app.apk")
        assertEquals("https", next?.protocol)
        assertEquals("objects.githubusercontent.com", next?.host)
    }

    @Test fun refusesHttpsToHttpDowngrade() =
        assertNull("http redirect target must be refused", AppInstaller.httpsRedirect(github, "http://cdn.example/app.apk"))

    @Test fun resolvesRelativeRedirectAgainstBaseAndKeepsHttps() {
        // A relative Location inherits the (https) base scheme — previously URL("/path") threw and failed the download.
        val next = AppInstaller.httpsRedirect(github, "/redirected/app.apk")
        assertEquals("https", next?.protocol)
        assertEquals("github.com", next?.host)
        assertEquals("/redirected/app.apk", next?.path)
    }

    /** The scheme gate refuses before any socket is opened, so these are deterministic and offline.
     *  They pin that a refusal is reported as [AppInstaller.DownloadResult.Failed] rather than as a
     *  size or deadline outcome — the Install page words those three differently for the operator. */
    @Test fun refusesUnusableDownloadUrlsWithoutTouchingTheNetwork() {
        val destination = File.createTempFile("download-refusal-", ".apk").also { it.deleteOnExit() }

        assertEquals(
            AppInstaller.DownloadResult.Failed,
            AppInstaller.download("http://cdn.example/app.apk", destination, 1024L),
        )
        assertEquals(
            AppInstaller.DownloadResult.Failed,
            AppInstaller.download("ftp://cdn.example/app.apk", destination, 1024L),
        )
        assertEquals(
            AppInstaller.DownloadResult.Failed,
            AppInstaller.download("not a url at all", destination, 1024L),
        )
        assertEquals(0L, destination.length())
    }

    /** A size breach, a stall and everything else must stay distinguishable: the Install page turns
     *  these into three different sentences, and an operator who is not at the panel has nothing else
     *  to go on. Collapsing any two of them would leave the page telling them the wrong thing. */
    @Test fun classifiesDownloadFailuresByWhatTheOperatorMustDoNext() {
        assertEquals(
            AppInstaller.DownloadResult.TooLarge,
            AppInstaller.downloadFailure(ByteLimitExceeded(4L)),
        )
        assertEquals(
            AppInstaller.DownloadResult.TimedOut,
            AppInstaller.downloadFailure(java.net.SocketTimeoutException("read timed out")),
        )
        assertEquals(
            AppInstaller.DownloadResult.Failed,
            AppInstaller.downloadFailure(java.io.IOException("connection reset")),
        )
    }

    @Test fun refusesNonHttpScheme() {
        assertNull("file:// target must be refused", AppInstaller.httpsRedirect(github, "file:///etc/passwd"))
        assertNull("ftp:// target must be refused", AppInstaller.httpsRedirect(github, "ftp://host/app.apk"))
    }

    @Test fun hashesDownloadedBlobForExactReleasePins() {
        val file = File.createTempFile("installer-hash-", ".bin")
        try {
            file.writeText("ha-paneld")
            assertEquals("9e3e7fce3ad3280fc638bb3c9dd1b8a5ea8b84ecd716129b6ce18afd946e3309", AppInstaller.sha256(file))
        } finally {
            file.delete()
        }
    }

    @Test fun shizukuRequiresExplicitCuratedInstallOptIn() {
        assertEquals(
            AppInstaller.InstallRoute.NONE,
            AppInstaller.selectInstallRoute(false, false, true, allowShizuku = false),
        )
        assertEquals(
            AppInstaller.InstallRoute.SHIZUKU,
            AppInstaller.selectInstallRoute(false, false, true, allowShizuku = true),
        )
    }

    @Test fun installRouteIsSelectedOnceInEstablishedPrecedenceOrder() {
        assertEquals(
            AppInstaller.InstallRoute.SU,
            AppInstaller.selectInstallRoute(true, true, true, allowShizuku = true),
        )
        assertEquals(
            AppInstaller.InstallRoute.DAEMON,
            AppInstaller.selectInstallRoute(false, true, true, allowShizuku = true),
        )
    }

    @Test fun packageManagerRejectionIsDurableWhileOtherOutputStaysRetryable() {
        // A `pm install` `Failure [...]` line is a durable rejection of THIS artifact — retrying the same
        // pin cannot help — so it is Rejected; anything else is a transient Retryable. The message
        // preserves the historical "install failed: <output>" text exactly.
        assertEquals(
            InstallOutcome.Rejected("install failed: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]"),
            AppInstaller.installFailure("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]"),
        )
        assertEquals(
            InstallOutcome.Retryable("install failed: Shizuku installer unavailable"),
            AppInstaller.installFailure("Shizuku installer unavailable"),
        )
        assertEquals(
            InstallOutcome.Retryable("install failed: "),
            AppInstaller.installFailure(""),
        )
    }

    @Test fun slowProgressCannotExtendTheWholeDownloadDeadline() {
        val remaining = ArrayDeque(listOf(10L, 0L))
        val output = ByteArrayOutputStream()

        val failure = runCatching {
            AppInstaller.copyBeforeDeadline(
                ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                output,
                maxBytes = 10L,
                remainingMs = { remaining.removeFirstOrNull() ?: 0L },
            )
        }.exceptionOrNull()

        assertTrue(failure is java.net.SocketTimeoutException)
        assertEquals(0, output.size())
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
