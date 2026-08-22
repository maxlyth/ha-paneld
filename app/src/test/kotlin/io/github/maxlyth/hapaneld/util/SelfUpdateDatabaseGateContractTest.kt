package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mutation-resistant wiring proof for every managed in-app self-replacement entry point. */
class SelfUpdateDatabaseGateContractTest {
    private val updater = TestSources.kotlin("util/SelfUpdater.kt").readText()
    private val installer = TestSources.kotlin("util/AppInstaller.kt").readText()
    private val server = TestSources.kotlin("http/PaneldServer.kt").readText()
    private val service = TestSources.kotlin("PaneldService.kt").readText()

    @Test fun exactAndChannelUpdatesPrepareTheAuthenticatedCandidateBeforeInstall() {
        assertEquals(
            "exact-tag and channel preparation must independently enter the same staged gate",
            2,
            Regex("AppInstaller\\.prepareSelfInstall\\(").findAll(updater).count(),
        )
        assertTrue(updater.contains("prepareChannelUpdate(context, channel, force)"))
        assertTrue(updater.contains("ComponentUpdater.resolveUpdate(current, force)"))
        assertTrue(updater.contains("AppInstaller.installPrepared(context, prepared)"))
        assertFalse(
            "the retired app-version floor must not remain authoritative",
            updater.contains("MIN_DOWNGRADE_" + "VERSION"),
        )
        assertTrue(
            "periodic auto-update must remain behind the same channel preparation gate",
            service.contains("SelfUpdater.checkAndUpdate(this@PaneldService, config.updateChannel)"),
        )
    }

    @Test fun uploadedSelfApkAndDownloadedSelfApkConvergeAtTheLocalGate() {
        assertTrue(server.contains("AppInstaller.installLocalApk(appContext, apk)"))
        assertTrue(installer.contains("installLocalApk(context, apk, allowShizuku)"))
        assertTrue(installer.contains("val refusal = localInstallCandidateRefusal("))
        assertTrue(
            "prepared installs must re-observe DB state instead of trusting boundary equality alone",
            installer.contains("return preparedSelfReplacementRefusal("),
        )
    }

    @Test fun sharedGatePrecedesEverySelfReplacementMutation() {
        val gate = installer.indexOf("DB_COMPAT_MUTATION_ANCHOR: IN_APP_" + "GATE")
        val firstMutation = installer.indexOf("DB_COMPAT_MUTATION_ANCHOR: IN_APP_" + "FIRST_MUTATION")
        val snapshot = installer.indexOf("ConfigUpgradeBackup.snapshot(context)", firstMutation)
        val quiesce = installer.indexOf("AppState.quiesceForSelfReplace", firstMutation)
        val packageInstall = installer.indexOf("pm install -S", firstMutation)

        assertTrue(gate >= 0)
        assertTrue(firstMutation > gate)
        assertTrue(snapshot > firstMutation)
        assertTrue(quiesce > firstMutation)
        assertTrue(packageInstall > firstMutation)
    }

    @Test fun commonTailRejectsUnreadableAndPreparedIdentityFailuresBeforeFirstMutation() {
        val gate = installer.indexOf("DB_COMPAT_MUTATION_ANCHOR: IN_APP_" + "GATE")
        val firstMutation = installer.indexOf("DB_COMPAT_MUTATION_ANCHOR: IN_APP_" + "FIRST_MUTATION")
        assertTrue(gate >= 0)
        assertTrue(firstMutation > gate)
        val protectedTail = installer.substring(gate, firstMutation)

        val localGate = protectedTail.indexOf("localInstallCandidateRefusal(")
        val refusalBranch = protectedTail.indexOf("if (refusal != null)")
        val refusalReturn = protectedTail.indexOf("return@withContext InstallOutcome.Rejected")

        assertTrue("every local candidate must enter the common refusal gate", localGate >= 0)
        assertTrue(localGate < refusalBranch)
        assertTrue(refusalBranch < refusalReturn)
    }

    @Test fun localGateCannotDemoteUnreadableOrWrongPreparedApkToNonSelf() {
        val helper = installer.substring(
            installer.indexOf("internal fun localInstallCandidateRefusal("),
            installer.indexOf("/** Pure fail-closed seam for the final config-commit admission"),
        )
        val unreadable = helper.indexOf("if (info == null) return \"unreadable candidate APK\"")
        val admitted = helper.indexOf("if (admittedBoundary != null)")
        val wrongPackage = helper.indexOf("if (info.pkg != runningPackage) return \"prepared candidate is not the running package\"")
        val nonSelf = helper.lastIndexOf("if (info.pkg != runningPackage) return null")

        assertTrue(unreadable >= 0)
        assertTrue(unreadable < admitted)
        assertTrue(admitted < wrongPackage)
        assertTrue(wrongPackage < nonSelf)
    }

    @Test fun configCommitRevalidationRehashesReauthenticatesAndRequiresCurrentDirectDatabase() {
        val revalidation = installer.substring(
            installer.indexOf("internal fun revalidatePreparedDirectForConfigCommit("),
            installer.indexOf("private fun compatibilityRefusal("),
        )

        assertTrue(revalidation.contains("if (!prepared.requiresDirectAtConsumption())"))
        val rehash = revalidation.indexOf("prepared.bytesUnchanged()")
        val inspect = revalidation.indexOf("inspect(context, prepared.apkPath())")
        val decision = revalidation.indexOf("preparedDirectConfigCommitRefusal(")
        assertTrue("config admission must re-hash the exact prepared APK", rehash >= 0)
        assertTrue("candidate inspection must follow the exact-byte check", inspect > rehash)
        assertTrue("the compatibility decision must follow candidate inspection", decision > inspect)
        assertTrue(revalidation.contains("compatibilityRefusal(context, exactBoundary, requireDirect = true)"))
    }
}
