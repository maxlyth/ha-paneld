package io.github.maxlyth.hapaneld.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URL

/**
 * [AppInstaller.httpsRedirect] — the download redirect gate: only ever follow a hop to an HTTPS
 * target, and resolve relative `Location` headers against the current URL. `java.net.URL` is a pure
 * JVM class, so this needs no device.
 */
class AppInstallerTest {
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
}
