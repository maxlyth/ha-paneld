package io.github.maxlyth.hapaneld

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfUpdateChannelEntryPointContractTest {
    private fun source(relative: String): String = listOf(File(relative), File("app/$relative"))
        .first(File::isFile)
        .readText()

    @Test
    fun `exact forced channel and periodic self installs converge on exact prepared admission`() {
        val updater = source("src/main/kotlin/io/github/maxlyth/hapaneld/util/SelfUpdater.kt")
        val exact = updater.substring(updater.indexOf("suspend fun installVersion"), updater.indexOf("fun resolveTarget"))
        val channel = updater.substring(updater.indexOf("internal suspend fun prepareChannelUpdate"), updater.length)
        assertTrue(exact.indexOf("prepareSelfInstall(context, url)") < exact.indexOf("installPrepared(context, prepared)"))
        assertTrue(channel.contains("ComponentUpdater.resolveUpdate(current, force)"))
        assertTrue(channel.indexOf("prepareChannelUpdate(context, channel, force)") <
            channel.lastIndexOf("installPrepared(context, prepared)"))

        val service = source("src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt")
        val periodic = service.substring(service.indexOf("name = \"update-check\""), service.indexOf("startMqttWatchdog()"))
        assertTrue(periodic.contains("SelfUpdater.checkAndUpdate(this@PaneldService, config.updateChannel)"))
    }

    @Test
    fun `mqtt channel change leaves persistence to exact candidate transaction`() {
        val bridge = source("src/main/kotlin/io/github/maxlyth/hapaneld/MqttBridge.kt")
        val stage = bridge.substring(bridge.indexOf("internal fun stageSelfUpdateChannelChange"), bridge.indexOf("internal class MqttBridge"))
        assertTrue(stage.indexOf("requestAdmittedInstall(requested, current)") < stage.lastIndexOf("publishCurrent()"))
        val handler = bridge.substring(bridge.indexOf("private fun handleUpdateChannel("), bridge.indexOf("private fun handleCompanionChannel("))
        assertTrue(handler.contains("stageSelfUpdateChannelChange("))
        assertFalse(handler.contains("config.setUpdateChannel(requested)"))

        val service = source("src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt")
        val transaction = service.substring(
            service.indexOf("private fun launchSelfUpdateChannelChange("),
            service.indexOf("private fun completeSelfUpdateChannelChange("),
        )
        assertTrue(transaction.indexOf("prepareSelfUpdateChannel(") < transaction.indexOf("config.applyBatch"))
        assertTrue(transaction.indexOf("config.applyBatch") <
            transaction.indexOf("installCommittedSelfUpdateChannel("))
    }

    @Test
    fun `mqtt ready branch consumes its exact capability without a second resolution`() {
        val service = source("src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt")
        val transaction = service.substring(
            service.indexOf("private fun launchSelfUpdateChannelChange("),
            service.indexOf("private fun completeSelfUpdateChannelChange("),
        )
        assertTrue(transaction.contains("install = it.install"))
        assertTrue(transaction.contains("installCommittedSelfUpdateChannel("))
        assertTrue(transaction.indexOf("prepareSelfUpdateChannel(") < transaction.indexOf("install = it.install"))
        assertTrue(
            "MQTT must prepare exactly once and consume that capability",
            Regex("prepareSelfUpdateChannel\\(").findAll(transaction).count() == 1,
        )

        val readyBranch = transaction.substring(
            transaction.indexOf("is io.github.maxlyth.hapaneld.http.SelfUpdateChannelPreflight.Ready"),
            transaction.indexOf("is io.github.maxlyth.hapaneld.http.SelfUpdateChannelPreflight.UpToDate"),
        )
        assertFalse(readyBranch.contains("prepareSelfUpdateChannel("))
        assertFalse(transaction.contains("SelfUpdater.checkAndUpdate"))
        assertFalse(transaction.contains("prepareChannelUpdate("))
        assertFalse(transaction.contains("resolveTarget("))
    }

    @Test
    fun `direct config import and revision restore share precommit channel admission`() {
        val server = source("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt")
        val direct = server.substring(
            server.indexOf("private suspend fun handleConfigPost("),
            server.indexOf("private data class OnboardingConfigPost("),
        )
        assertTrue(direct.indexOf("prepareSelfUpdateChannel(request.requested, request.force)") <
            direct.indexOf("val committed = withContext"))
        assertTrue(direct.indexOf("p[\"update_channel\"]?.let { config.setUpdateChannel(it) }") >
            direct.indexOf("prepareSelfUpdateChannel(request.requested, request.force)"))

        val shared = server.substring(
            server.indexOf("private suspend fun applyAccepted("),
            server.indexOf("private data class AcceptedCommit("),
        )
        assertTrue(shared.indexOf("prepareSelfUpdateChannel(request.requested, request.force)") <
            shared.indexOf("rendererPreparation.transaction"))
        assertTrue(shared.contains("key == \"update_channel\" ->"))
        assertTrue(shared.indexOf("onSelfUpdateChannelCommitted(") >
            shared.indexOf("config.commit("))
        listOf("handleConfigImport", "Restore stored configuration revision", "applyRestoreConfig").forEach {
            assertTrue("shared apply caller missing: $it", server.contains(it))
        }
    }

    @Test
    fun `shared apply stages update channel and cannot divert it through live apply`() {
        val server = source("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt")
        val shared = server.substring(
            server.indexOf("private suspend fun applyAccepted("),
            server.indexOf("private data class AcceptedCommit("),
        )
        val updateChannelCase = shared.substring(
            shared.indexOf("key == \"update_channel\" ->"),
            shared.indexOf("key in HTTP_LIVE_KEYS ->"),
        )

        assertTrue(updateChannelCase.contains("SettingsRegistry.spec(key)"))
        assertTrue(updateChannelCase.contains("config.stage(editor, it, value)"))
        assertFalse(updateChannelCase.contains("live.add"))
    }

    @Test
    fun `direct and shared channel commits promote config ownership before release`() {
        val server = source("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt")
        val direct = server.substring(
            server.indexOf("private suspend fun handleConfigPost("),
            server.indexOf("private data class OnboardingConfigPost("),
        )
        val directPromotion = direct.lastIndexOf("InstallProgress.promoteConfigMutation")
        val directRelease = direct.lastIndexOf("InstallProgress.finishConfigMutation")
        val directConsume = direct.lastIndexOf("onSelfUpdateChannelCommitted(")
        assertTrue(directPromotion >= 0)
        assertTrue(directPromotion < directRelease)
        assertTrue(directRelease < directConsume)

        val shared = server.substring(
            server.indexOf("private suspend fun applyAccepted("),
            server.indexOf("private data class AcceptedCommit("),
        )
        val sharedClaim = shared.indexOf("InstallProgress.startConfigMutation()")
        val sharedPreflight = shared.indexOf("prepareSelfUpdateChannel(request.requested, request.force)")
        val sharedCommit = shared.indexOf("config.commit(")
        val sharedPromotion = shared.lastIndexOf("InstallProgress.promoteConfigMutation")
        val sharedRelease = shared.lastIndexOf("InstallProgress::finishConfigMutation")
        assertTrue(sharedClaim >= 0)
        assertTrue(shared.indexOf("restoreChangesUpdateChannel(config.updateChannel, accepted)") < sharedPreflight)
        assertTrue(sharedClaim < sharedPreflight)
        assertTrue(sharedPreflight < sharedCommit)
        assertTrue(sharedCommit < sharedPromotion)
        assertTrue(sharedPromotion < sharedRelease)
    }

    @Test
    fun `config coupled recovery is rejected before every channel config transaction`() {
        val updater = source("src/main/kotlin/io/github/maxlyth/hapaneld/util/SelfUpdater.kt")
        val admission = updater.substring(
            updater.indexOf("internal fun admitConfigCoupledChannel("),
            updater.indexOf("/** Consume a previously admitted exact channel candidate"),
        )
        assertTrue(admission.contains("SelfInstallDatabaseDisposition.RECOVER"))
        assertTrue(admission.indexOf("prepared.close()") < admission.indexOf("ChannelPreparation.Refused"))

        val service = source("src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt")
        val adapter = service.substring(
            service.indexOf("private suspend fun prepareSelfUpdateChannel("),
            service.indexOf("/** MQTT channel switches"),
        )
        assertTrue(adapter.contains("SelfUpdater.admitConfigCoupledChannel("))
        assertTrue(adapter.indexOf("SelfUpdater.prepareChannelUpdate") <
            adapter.indexOf("is SelfUpdater.ChannelPreparation.Ready"))
        assertTrue(adapter.contains("revalidateForConfigCommit = {"))
        assertTrue(adapter.contains("AppInstaller.revalidatePreparedDirectForConfigCommit("))
        assertTrue(adapter.indexOf("AppInstaller.revalidatePreparedDirectForConfigCommit(") <
            adapter.indexOf("SelfUpdater.installPreparedOutcome("))
    }

    @Test
    fun `every config coupled channel revalidates exact candidate and direct database immediately before commit`() {
        val server = source("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt")
        val direct = server.substring(
            server.indexOf("private suspend fun handleConfigPost("),
            server.indexOf("private data class OnboardingConfigPost("),
        )
        val directRevalidate = direct.indexOf("preparedChannel?.revalidateForConfigCommit()")
        val directCommit = direct.indexOf("config.applyBatch(", directRevalidate)
        assertTrue(directRevalidate >= 0)
        assertTrue(directCommit > directRevalidate)
        assertTrue(direct.substring(directRevalidate, directCommit).contains("return@synchronizedTransaction false"))

        val shared = server.substring(
            server.indexOf("private suspend fun applyAccepted("),
            server.indexOf("private data class AcceptedCommit("),
        )
        val sharedRevalidate = shared.indexOf("preparedChannel?.revalidateForConfigCommit()")
        val sharedCommit = shared.indexOf("config.commit(", sharedRevalidate)
        assertTrue(sharedRevalidate >= 0)
        assertTrue(sharedCommit > sharedRevalidate)
        assertTrue(shared.substring(sharedRevalidate, sharedCommit).contains("return@synchronizedTransaction"))

        val service = source("src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt")
        val mqtt = service.substring(
            service.indexOf("private fun launchSelfUpdateChannelChange("),
            service.indexOf("private fun completeSelfUpdateChannelChange("),
        )
        val mqttRevalidate = mqtt.indexOf("it.revalidateForConfigCommit()")
        val mqttCommit = mqtt.indexOf("config.applyBatch", mqttRevalidate)
        assertTrue(mqttRevalidate >= 0)
        assertTrue(mqttCommit > mqttRevalidate)
        assertTrue(mqtt.substring(mqttRevalidate, mqttCommit).contains("if (commitRefusal != null) false"))
    }

    @Test
    fun `precommit compatibility refusal preserves config and releases prepared capability ownership`() {
        val server = source("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt")
        val direct = server.substring(
            server.indexOf("private suspend fun handleConfigPost("),
            server.indexOf("private data class OnboardingConfigPost("),
        )
        val directRefusal = direct.indexOf("channelCompatibilityRefusal?.let")
        val directHandoff = direct.lastIndexOf("onSelfUpdateChannelCommitted(")
        assertTrue(directRefusal >= 0)
        assertTrue(directRefusal < directHandoff)
        assertTrue(direct.contains("InstallProgress.finishConfigMutation(configMutationTicket)"))
        assertTrue(direct.contains("preparedChannel?.close()"))

        val shared = server.substring(
            server.indexOf("private suspend fun applyAccepted("),
            server.indexOf("private data class AcceptedCommit("),
        )
        assertTrue(shared.contains("earlyResult = ApplyAcceptedResult.CompatibilityRefused(refusal)"))
        assertTrue(shared.indexOf("earlyResult = ApplyAcceptedResult.CompatibilityRefused(refusal)") <
            shared.indexOf("config.commit("))
        assertTrue(shared.contains("configMutationTicket?.let(InstallProgress::finishConfigMutation)"))
        assertTrue(shared.contains("preparedChannel?.close()"))

        val service = source("src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt")
        val mqtt = service.substring(
            service.indexOf("private fun launchSelfUpdateChannelChange("),
            service.indexOf("private fun completeSelfUpdateChannelChange("),
        )
        assertTrue(mqtt.contains("is io.github.maxlyth.hapaneld.http.SelfUpdateChannelPreflight.Ready -> preflight.use"))
        assertTrue(mqtt.indexOf("commitRefusal = it.revalidateForConfigCommit()") <
            mqtt.indexOf("config.applyBatch"))
    }

    @Test
    fun `stale and failed config admissions release ownership and discard prepared bytes`() {
        val server = source("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt")
        val direct = server.substring(
            server.indexOf("private suspend fun handleConfigPost("),
            server.indexOf("private data class OnboardingConfigPost("),
        )
        assertTrue(direct.contains("InstallProgress.finishConfigMutation(configMutationTicket)"))
        assertTrue(direct.contains("preparedChannel?.close()"))

        val shared = server.substring(
            server.indexOf("private suspend fun applyAccepted("),
            server.indexOf("private data class AcceptedCommit("),
        )
        assertTrue(shared.contains("configMutationTicket?.let(InstallProgress::finishConfigMutation)"))
        assertTrue(shared.contains("preparedChannel?.close()"))
        assertTrue(shared.indexOf("earlyResult = ApplyAcceptedResult.Stale") < shared.indexOf("} finally {"))
        assertTrue(shared.indexOf("earlyResult = ApplyAcceptedResult.CommitFailed") < shared.indexOf("} finally {"))
    }

    @Test
    fun `live setting replay cannot precommit update channel before candidate admission`() {
        val service = source("src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt")
        val apply = service.substring(
            service.indexOf("private fun applyLiveSettingObserved("),
            service.indexOf("private fun replayLiveSettingObserved("),
        )
        val exception = apply.indexOf("if (key == \"update_channel\")")
        val ordinaryPrecommit = apply.indexOf("config.commitRaw(spec, value)")
        assertTrue(exception >= 0)
        assertTrue(exception < ordinaryPrecommit)
        assertTrue(apply.substring(exception, ordinaryPrecommit).contains("bridge.applySettingObserved"))
    }

    @Test
    fun `postcommit channel callback consumes prepared capability without reresolution`() {
        val service = source("src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt")
        val completion = service.substring(
            service.indexOf("private fun completeSelfUpdateChannelChange("),
            service.indexOf("/** Install/update a managed component"),
        )
        assertTrue(completion.contains("install = ready.install"))
        assertTrue(completion.contains("installCommittedSelfUpdateChannel("))
        assertTrue(completion.contains("rollbackSelfUpdateChannel(committed, previous)"))
        assertFalse(completion.contains("SelfUpdater.checkAndUpdate"))
        assertFalse(completion.contains("prepareSelfUpdateChannel("))
        assertFalse(completion.contains("resolveTarget("))

        val helper = service.substring(
            service.indexOf("internal suspend fun installCommittedSelfUpdateChannel("),
            service.indexOf("/** A scope canceled before the promoted install body starts"),
        )
        assertTrue(helper.contains("return install().also"))
        assertTrue(helper.contains("if (!installed) rollback()"))

        val updater = source("src/main/kotlin/io/github/maxlyth/hapaneld/util/SelfUpdater.kt")
        val consume = updater.substring(
            updater.indexOf("internal suspend fun installPreparedOutcome("),
            updater.indexOf("/** Update ha-paneld to the newest build"),
        )
        assertTrue(consume.contains("AppInstaller.installPrepared(context, prepared)"))
        assertFalse(consume.contains("resolveTarget"))
        assertFalse(consume.contains("prepareChannelUpdate"))
    }
}
