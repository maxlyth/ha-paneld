package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TameReconcileWiringContractTest {
    private val server = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
    ).first(File::isFile).readText()
    private val controller = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/control/TameController.kt"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/control/TameController.kt"),
    ).first(File::isFile).readText()

    @Test fun `direct config card and restore commits share the commit-order wakeup seam`() {
        val direct = server.substring(
            server.indexOf("private suspend fun handleConfigPost"),
            server.indexOf("private fun updateTameSelection"),
        )
        val card = server.substring(
            server.indexOf("private fun updateTameSelection"),
            server.indexOf("private suspend fun respondRemoteAdmission"),
        )
        val restore = server.substring(
            server.indexOf("private suspend fun applyAccepted"),
            server.indexOf("private fun applyRendererEffects"),
        )

        assertTrue(direct.contains("if (\"tame_vendor_packages\" in p) requestTameReconcileAfterCommit()"))
        assertTrue(card.contains("config.commit(editor, afterCommit = ::requestTameReconcileAfterCommit)"))
        assertTrue(restore.contains("if (\"tame_vendor_packages\" in accepted) requestTameReconcileAfterCommit()"))
        assertFalse(server.contains("TameDelta"))
        assertFalse(server.contains("private enum class TameMutation"))
    }

    @Test fun `startup enters the same owner behind the predecessor fence`() {
        val service = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt"),
        ).first(File::isFile).readText()
        val startup = service.substring(
            service.indexOf("restartLease.awaitPredecessor()"),
            service.indexOf("activeRuntime.mqtt.start()"),
        )

        assertTrue(startup.indexOf("server.start()") < startup.indexOf("server.requestTameReconcile()"))
        assertFalse(startup.contains("tame.applyBlocklist"))
    }

    @Test fun `overlay restoration marker is the sole write ahead ownership record`() {
        val reassert = controller.substring(
            controller.indexOf("private fun reassertTame"),
            controller.indexOf("private fun applyTameActions"),
        )

        assertFalse(controller.contains("tame_applied_packages"))
        assertTrue(reassert.contains("TameStatePolicy.markerKey(pkg)"))
        assertTrue(reassert.contains("TameStatePolicy.reassertOwnership("))
        assertTrue(reassert.contains("mutate = { applyTameActions(pkg) }"))
        assertTrue(reassert.contains("commitWithDurableVisibility"))
        assertFalse(controller.contains("HelperClient.available()"))
        assertTrue(
            controller.contains(
                "?: return packages.associateWith",
            ),
        )
    }
}
