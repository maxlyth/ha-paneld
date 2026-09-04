package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins semantic metadata selection at the operation producers while legacy prose APIs stay intact. */
class InstallOperationPresentationWiringContractTest {
    private val appInstaller = TestSources.kotlin("util/AppInstaller.kt").readText()
    private val companionInstaller = TestSources.kotlin("util/CompanionInstaller.kt").readText()
    private val selfUpdater = TestSources.kotlin("util/SelfUpdater.kt").readText()
    private val webViewInstaller = TestSources.kotlin("util/WebViewInstaller.kt").readText()
    private val service = TestSources.kotlin("PaneldService.kt").readText()

    @Test fun installerFailureFamiliesAreSelectedBeforeCompatibilityProseLeavesTheProducer() {
        setOf(
            "install-no-permitted-route",
            "install-download-too-large",
            "install-insufficient-storage",
            "install-staging-failed",
            "install-download-failed",
            "install-deferred-saving-state",
            "install-guard-db-owned",
            "install-durable-rejection",
            "install-retryable-failure",
        ).forEach { code -> assertTrue("missing $code", appInstaller.contains("\"$code\"")) }
    }

    @Test fun managedComponentProducersCoverEveryFrozenFiniteOutcome() {
        setOf(
            "managed-release-unresolved",
            "managed-apk-missing",
            "managed-up-to-date",
            "managed-update-committed",
            "managed-downgrade-committed",
        ).forEach { code ->
            assertTrue("self updater missing $code", selfUpdater.contains("\"$code\""))
        }
        setOf(
            "managed-release-unresolved",
            "managed-apk-missing",
            "managed-up-to-date",
            "managed-install-committed",
            "managed-update-committed",
            "managed-downgrade-committed",
            "managed-pinned",
            "managed-safety-cap-refused",
            "managed-manual-downgrade-required",
            "managed-play-managed",
        ).forEach { code ->
            assertTrue("Companion installer missing $code", companionInstaller.contains("\"$code\""))
        }
        setOf("managed-no-recommendation", "managed-up-to-date", "managed-no-newer", "managed-install-committed")
            .forEach { code -> assertTrue("WebView installer missing $code", webViewInstaller.contains("\"$code\"")) }
        assertTrue(service.contains("\"managed-attempt-recorded\""))
    }

    @Test fun publicStringApisRemainExactWrappersAndProgressCarriesTypedResults() {
        assertTrue(selfUpdater.contains("installVersionResult(context, tag).message"))
        assertTrue(selfUpdater.contains("checkAndUpdateResult(context, channel, force).message"))
        assertTrue(companionInstaller.contains("installVersionResult(context, tag, maxVersion).message"))
        assertTrue(companionInstaller.contains("installOrUpdateResult(context, force, channel, maxVersion).message"))
        assertTrue(service.contains("InstallProgress.finish(progress, result, presentation = resultPresentation)"))
        assertTrue(service.contains("InstallPresentation(\"operation-working\", mapOf(\"owner\" to owner))"))
    }
}
