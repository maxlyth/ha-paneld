package io.github.maxlyth.hapaneld.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
}
