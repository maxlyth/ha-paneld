package io.github.maxlyth.hapaneld.device.profile

import io.github.maxlyth.hapaneld.device.Generic
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RuntimeProfileRegistryTest {
    private lateinit var directory: File
    private lateinit var preferences: MemoryProfilePreferences
    private val facts = DeviceFacts("test-panel", "test-device", "fw-1")

    @Before fun setUp() {
        directory = Files.createTempDirectory("profile-registry-test").toFile()
        preferences = MemoryProfilePreferences()
    }

    @After fun tearDown() {
        directory.deleteRecursively()
    }

    @Test fun `community import remains inert until immutable revision is pinned`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val candidate = ProfileYaml.serialize(testProfileDocument(facts = facts))

        val imported = import(registry, candidate)

        assertEquals("generic", registry.status().active!!.ref.id)
        assertFalse(registry.status().active!!.ref == imported)
        assertEquals(ProfileSelection.Auto, registry.status().selection)
    }

    @Test fun `preview token is one shot and bound to exact bytes`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val first = ProfileYaml.serialize(testProfileDocument(facts = facts))
        val second = ProfileYaml.serialize(testProfileDocument(version = "1.0.1", facts = facts))
        val preview = registry.preview(first)

        assertTrue(registry.importProfile(second, preview.previewToken!!) is ProfileMutation.Rejected)
        assertTrue(registry.importProfile(first, preview.previewToken) is ProfileMutation.Rejected)
    }

    @Test fun `same id can hold multiple immutable content revisions`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val first = import(registry, ProfileYaml.serialize(testProfileDocument(version = "1.0.0", facts = facts)))
        val second = import(registry, ProfileYaml.serialize(testProfileDocument(version = "1.0.1", facts = facts)))

        assertFalse(first == second)
        assertEquals(2, registry.list().count { it.origin == ProfileOrigin.IMPORTED })
        assertNotNull(registry.exportProfile(first))
        assertNotNull(registry.exportProfile(second))
    }

    @Test fun `activation preserves one step last known good and supports explicit rollback`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val ref = import(registry, ProfileYaml.serialize(testProfileDocument(facts = facts)))
        val beforeSelectRevision = registry.status().catalogRevision

        val selected = registry.select(ProfileSelection.Pinned(ref), beforeSelectRevision)
        assertTrue(selected is ProfileMutation.Success && selected.restartRequired)
        assertEquals(ProfileActivationPhase.PENDING, registry.status().activation.phase)
        assertEquals("generic", registry.status().active!!.ref.id)

        val applying = registry.resolveForStartup()
        assertEquals(ref, applying.summary.ref)
        assertNotNull(applying.activationGeneration)
        assertTrue(registry.markActivationHealthy(applying.activationGeneration!!))
        assertEquals(ProfileSelection.Auto, registry.status().lastKnownGood)
        assertEquals(ref, registry.status().active!!.ref)

        val rollback = registry.rollbackToLastKnownGood(registry.status().catalogRevision)
        assertTrue(rollback is ProfileMutation.Success && rollback.restartRequired)
        val rolledBack = registry.resolveForStartup()
        assertEquals("generic", rolledBack.summary.ref.id)
        assertTrue(registry.markActivationHealthy(rolledBack.activationGeneration!!))
        assertEquals(ProfileSelection.Pinned(ref), registry.status().lastKnownGood)
    }

    @Test fun `unhealthy applying process rolls back without promoting candidate`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val ref = import(registry, ProfileYaml.serialize(testProfileDocument(facts = facts)))
        registry.select(ProfileSelection.Pinned(ref), registry.status().catalogRevision)

        val applying = registry.resolveForStartup()
        assertEquals(ref, applying.summary.ref)
        val recovered = registry.resolveForStartup()

        assertNull(recovered.activationGeneration)
        assertEquals("generic", recovered.summary.ref.id)
        assertEquals(ProfileActivationPhase.ROLLED_BACK, registry.status().activation.phase)
        assertEquals(ProfileSelection.Auto, registry.status().selection)
    }

    @Test fun `automatic bundled revision change uses health gate and retained rollback snapshot`() {
        val oldRaw = ProfileYaml.serialize(testProfileDocument(id = "vendor.test-panel", version = "1.0.0", facts = facts))
        val newRaw = ProfileYaml.serialize(testProfileDocument(id = "vendor.test-panel", version = "1.0.1", facts = facts))
        val oldRef = ProfileRef("vendor.test-panel", ProfileYaml.sha256(oldRaw))
        val newRef = ProfileRef("vendor.test-panel", ProfileYaml.sha256(newRaw))
        val first = registry(mapOf("generic.yaml" to genericYaml(), "panel.yaml" to oldRaw))

        val initiallyActive = first.resolveForStartup()
        assertEquals(oldRef, initiallyActive.summary.ref)
        assertTrue(first.markStartupHealthy(oldRef))

        val upgraded = registry(mapOf("generic.yaml" to genericYaml(), "panel.yaml" to newRaw))
        val applying = upgraded.resolveForStartup()
        assertEquals(newRef, applying.summary.ref)
        assertNotNull(applying.activationGeneration)
        assertEquals(ProfileActivationPhase.APPLYING, upgraded.status().activation.phase)

        val afterFailedStartup = registry(mapOf("generic.yaml" to genericYaml(), "panel.yaml" to newRaw)).resolveForStartup()
        assertEquals(oldRef, afterFailedStartup.summary.ref)
        assertEquals(ProfileActivationPhase.ROLLED_BACK, registry(mapOf("generic.yaml" to genericYaml(), "panel.yaml" to newRaw)).status().activation.phase)
    }

    @Test fun `pending status does not mark previous active revision selected`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val ref = import(registry, ProfileYaml.serialize(testProfileDocument(facts = facts)))

        registry.select(ProfileSelection.Pinned(ref), registry.status().catalogRevision)

        assertFalse(registry.status().active!!.selected)
        assertTrue(registry.list().single { it.ref == ref }.selected)
    }

    @Test fun `healthy imported revision is not copied into trusted bundled rollback storage`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val ref = import(registry, ProfileYaml.serialize(testProfileDocument(facts = facts)))
        registry.select(ProfileSelection.Pinned(ref), registry.status().catalogRevision)
        val applying = registry.resolveForStartup()

        assertTrue(registry.markActivationHealthy(applying.activationGeneration!!))
        assertFalse(File(directory, "device-profiles/rollback/${ref.id}/${ref.revision}.yaml").exists())
        assertEquals(ProfileOrigin.IMPORTED, registry(mapOf("generic.yaml" to genericYaml())).list().single { it.ref == ref }.origin)
    }

    @Test fun `failed teardown can abort only a pending activation`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val ref = import(registry, ProfileYaml.serialize(testProfileDocument(facts = facts)))
        registry.select(ProfileSelection.Pinned(ref), registry.status().catalogRevision)

        assertTrue(registry.abortPendingActivation("Critical owner did not stop."))
        assertEquals(ProfileSelection.Auto, registry.status().selection)
        assertEquals(ProfileActivationPhase.ROLLED_BACK, registry.status().activation.phase)
        assertTrue(registry.status().activation.message!!.contains("did not stop"))
        assertFalse(registry.abortPendingActivation("too late"))
    }

    @Test fun `missing pinned revision in pending state rolls back and cannot be marked healthy`() {
        var registry = registry(mapOf("generic.yaml" to genericYaml()))
        val ref = import(registry, ProfileYaml.serialize(testProfileDocument(facts = facts)))
        registry.select(ProfileSelection.Pinned(ref), registry.status().catalogRevision)
        importedFile(ref).delete()
        registry = registry(mapOf("generic.yaml" to genericYaml()))

        val recovered = registry.resolveForStartup()

        assertNull(recovered.activationGeneration)
        assertEquals("generic", recovered.summary.ref.id)
        assertEquals(ProfileActivationPhase.ROLLED_BACK, registry.status().activation.phase)
    }

    @Test fun `selection transitions advance CAS revision across two administrators`() {
        val firstAdmin = registry(mapOf("generic.yaml" to genericYaml()))
        val ref = import(firstAdmin, ProfileYaml.serialize(testProfileDocument(facts = facts)))
        val secondAdmin = registry(mapOf("generic.yaml" to genericYaml()))
        val sharedRevision = secondAdmin.status().catalogRevision

        assertTrue(firstAdmin.select(ProfileSelection.Pinned(ref), sharedRevision) is ProfileMutation.Success)
        assertTrue(secondAdmin.select(ProfileSelection.Pinned(ref), sharedRevision) is ProfileMutation.Rejected)
    }

    @Test fun `last known good imported revision cannot be deleted`() {
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val first = import(registry, ProfileYaml.serialize(testProfileDocument(version = "1.0.0", facts = facts)))
        registry.select(ProfileSelection.Pinned(first), registry.status().catalogRevision)
        val firstApplying = registry.resolveForStartup()
        registry.markActivationHealthy(firstApplying.activationGeneration!!)
        val second = import(registry, ProfileYaml.serialize(testProfileDocument(version = "1.0.1", facts = facts)))
        registry.select(ProfileSelection.Pinned(second), registry.status().catalogRevision)
        val secondApplying = registry.resolveForStartup()
        registry.markActivationHealthy(secondApplying.activationGeneration!!)

        assertEquals(ProfileSelection.Pinned(first), registry.status().lastKnownGood)
        assertTrue(registry.deleteProfile(first, registry.status().catalogRevision) is ProfileMutation.Rejected)
    }

    @Test fun `oversized restored file and interrupted temp are ignored`() {
        val idDir = File(directory, "device-profiles/imported/community.example.oversized").apply { mkdirs() }
        File(idDir, "${"0".repeat(64)}.yaml").writeBytes(ByteArray(ProfileMetadata.MAX_BYTES + 1))
        File(idDir, ".interrupted.tmp").writeText(ProfileYaml.serialize(testProfileDocument()))

        val registry = registry(mapOf("generic.yaml" to genericYaml()))

        assertTrue(registry.status().issues.any { "size limit" in it.message })
        assertTrue(registry.list().none { it.origin == ProfileOrigin.IMPORTED })
    }

    @Test fun `restored id mismatch is retained only as an incompatible deletable record`() {
        val raw = ProfileYaml.serialize(testProfileDocument(id = "community.example.actual", facts = facts))
        val wrongDir = File(directory, "device-profiles/imported/community.example.other").apply { mkdirs() }
        File(wrongDir, "${ProfileYaml.sha256(raw)}.yaml").writeText(raw)

        val registry = registry(mapOf("generic.yaml" to genericYaml()))

        val summary = registry.list().single { it.origin == ProfileOrigin.IMPORTED }
        assertFalse(summary.compatible)
        assertEquals("community.example.other", summary.ref.id)
        assertEquals(raw, registry.exportProfile(summary.ref))
        assertTrue(registry.status().issues.any { "does not match storage id" in it.message })
        assertTrue(registry.deleteProfile(summary.ref, registry.status().catalogRevision) is ProfileMutation.Success)
    }

    @Test fun `per id quota rejects another import before writing`() {
        repeat(ProfileMetadata.MAX_IMPORTED_REVISIONS_PER_ID) { index ->
            restore(ProfileYaml.serialize(testProfileDocument(version = "1.0.$index", facts = facts)))
        }
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val candidate = ProfileYaml.serialize(testProfileDocument(version = "1.1.0", facts = facts))
        val preview = registry.preview(candidate)

        val result = registry.importProfile(candidate, preview.previewToken!!)

        assertTrue(result is ProfileMutation.Rejected)
        assertTrue((result as ProfileMutation.Rejected).issues.any { it.path == "catalog.quota" })
        assertEquals(ProfileMetadata.MAX_IMPORTED_REVISIONS_PER_ID, registry.list().count { it.origin == ProfileOrigin.IMPORTED })
    }

    @Test fun `restored catalog deterministically skips files beyond aggregate quota`() {
        repeat(33) { index ->
            val base = ProfileYaml.serialize(testProfileDocument(id = "community.example.aggregate-$index", facts = facts))
            val padding = ProfileMetadata.MAX_BYTES - base.toByteArray().size - 3
            restore(base + "# " + "x".repeat(padding) + "\n")
        }

        val registry = registry(mapOf("generic.yaml" to genericYaml()))

        assertEquals(32, registry.list().count { it.origin == ProfileOrigin.IMPORTED })
        assertTrue(registry.status().issues.any { "aggregate byte quota" in it.message })
    }

    @Test fun `restored count quota prioritizes the selected immutable revision`() {
        repeat(ProfileMetadata.MAX_IMPORTED_REVISIONS) { index ->
            restore(ProfileYaml.serialize(testProfileDocument(id = "community.example.count-$index", facts = facts)))
        }
        val protected = restore(ProfileYaml.serialize(testProfileDocument(id = "community.zzz.protected", facts = facts)))
        preferences.put("selection" to "${protected.id}@${protected.revision}")

        val registry = registry(mapOf("generic.yaml" to genericYaml()))

        assertEquals(ProfileMetadata.MAX_IMPORTED_REVISIONS, registry.list().count { it.origin == ProfileOrigin.IMPORTED })
        assertTrue(registry.list().any { it.ref == protected })
        assertEquals(protected, registry.status().active!!.ref)
        assertTrue(registry.status().issues.any { "catalog count quota" in it.message })
    }

    @Test fun `missing or corrupt bundled generic uses compiled emergency fallback`() {
        val missing = registry(emptyMap())
        val corrupt = registry(mapOf("generic.yaml" to "schema: ["))

        assertSame(Generic, missing.resolveForStartup().profile)
        assertSame(Generic, corrupt.resolveForStartup().profile)
        assertTrue(missing.status().issues.any { "compiled emergency" in it.message })
        assertTrue(corrupt.status().issues.any { "compiled emergency" in it.message })
    }

    @Test fun `incompatible imported revision remains listable exportable and deletable but cannot activate`() {
        val raw = incompatibleImportedYaml()
        val ref = restore(raw)
        val registry = registry(mapOf("generic.yaml" to genericYaml()))

        val summary = registry.list().single { it.ref == ref }
        assertFalse(summary.compatible)
        assertTrue(summary.issues.any { it.severity == ProfileIssueSeverity.ERROR })
        assertEquals(raw, registry.exportProfile(ref))
        assertTrue(registry.select(ProfileSelection.Pinned(ref), registry.status().catalogRevision) is ProfileMutation.Rejected)

        val deleted = registry.deleteProfile(ref, registry.status().catalogRevision)
        assertTrue(deleted is ProfileMutation.Success)
        assertNull(registry.exportProfile(ref))
        assertFalse(importedFile(ref).exists())
    }

    @Test fun `active pin invalidated by core upgrade restores durable last known good`() {
        // `led.removed-driver` represents a driver accepted by an earlier core but removed by this one.
        val ref = restore(incompatibleImportedYaml())
        val lkg = restore(ProfileYaml.serialize(testProfileDocument(id = "community.example.known-good", facts = facts)))
        preferences.put(
            "selection" to "${ref.id}@${ref.revision}",
            "last_known_good" to "${lkg.id}@${lkg.revision}",
            "activation_phase" to ProfileActivationPhase.ACTIVE.name,
        )
        val registry = registry(mapOf("generic.yaml" to genericYaml()))
        val revisionBeforeRecovery = registry.status().catalogRevision

        val recovered = registry.resolveForStartup()

        assertEquals(lkg.id, recovered.profile.id)
        assertNull(recovered.activationGeneration)
        assertEquals(ProfileSelection.Pinned(lkg), registry.status().selection)
        assertEquals(ProfileSelection.Pinned(lkg), registry.status().lastKnownGood)
        assertEquals(ProfileActivationPhase.ROLLED_BACK, registry.status().activation.phase)
        assertTrue(registry.status().catalogRevision > revisionBeforeRecovery)
        assertTrue(recovered.issues.any { "incompatible" in it.message })
        assertFalse(registry.list().single { it.ref == ref }.compatible)
        assertTrue(registry.deleteProfile(ref, registry.status().catalogRevision) is ProfileMutation.Success)
    }

    @Test fun `stored schema 1 pin falls back but remains exportable and deletable`() {
        val raw = legacySchemaOneYaml()
        val ref = ProfileRef("community.example.legacy-v1", ProfileYaml.sha256(raw))
        importedFile(ref).apply { parentFile!!.mkdirs() }.writeText(raw)
        preferences.put(
            "selection" to "${ref.id}@${ref.revision}",
            "activation_phase" to ProfileActivationPhase.ACTIVE.name,
        )
        val registry = registry(mapOf("generic.yaml" to genericYaml()))

        val stored = registry.list().single { it.ref == ref }
        assertFalse(stored.compatible)
        assertEquals(raw, registry.exportProfile(ref))

        val recovered = registry.resolveForStartup()
        assertEquals("generic", recovered.profile.id)
        assertEquals(ProfileSelection.Auto, registry.status().selection)
        assertEquals(ProfileActivationPhase.ROLLED_BACK, registry.status().activation.phase)

        assertTrue(registry.deleteProfile(ref, registry.status().catalogRevision) is ProfileMutation.Success)
        assertNull(registry.exportProfile(ref))
    }

    private fun registry(bundled: Map<String, String>) = RuntimeProfileRegistry(
        filesDir = directory,
        preferences = preferences,
        bundledLoader = { bundled },
        facts = facts,
        coreVersion = "1.0.0",
        clock = { 1000L },
    )

    private fun genericYaml() = ProfileYaml.serialize(testProfileDocument(id = "generic", fallback = true))

    private fun import(registry: RuntimeProfileRegistry, raw: String): ProfileRef {
        val preview = registry.preview(raw)
        assertTrue(preview.issues.toString(), preview.compatible)
        assertTrue(registry.importProfile(raw, preview.previewToken!!) is ProfileMutation.Success)
        return preview.summary!!.ref
    }

    private fun importedFile(ref: ProfileRef) = File(directory, "device-profiles/imported/${ref.id}/${ref.revision}.yaml")

    private fun incompatibleImportedYaml(): String = ProfileYaml.serialize(
        testProfileDocument(id = "community.example.removed-driver", facts = facts).copy(
            requires = ProfileRequirements(drivers = setOf("screen.brightness-zero", "led.removed-driver")),
        ),
    )

    private fun legacySchemaOneYaml(): String = """
        schema: 1
        id: community.example.legacy-v1
        version: 1.0.0
        display_name: Legacy proof of concept
        soc_class: Unknown
        metadata:
          author: Test author
          license: MIT
          maturity: draft
        requires:
          drivers:
            - screen.brightness-zero
        match:
          priority: 500
          fallback: false
          any:
            - priority: 900
              all:
                - field: model
                  op: equals
                  values:
                    - test-panel
        platform:
          su_form: none
          app_can_su: false
          has_recents: true
          shizuku: optional
        hardware:
          led:
            mechanism: none
          screen_off: brightness-zero
        updates: {}
        taming: []
    """.trimIndent() + "\n"

    private fun restore(raw: String): ProfileRef {
        val parsed = ProfileYaml.parse(raw).document!!
        val ref = ProfileRef(parsed.id, ProfileYaml.sha256(raw))
        importedFile(ref).apply { parentFile!!.mkdirs() }.writeText(raw)
        return ref
    }
}

private class MemoryProfilePreferences : ProfilePreferences {
    private val values = linkedMapOf<String, Any>()
    override fun getString(key: String, default: String) = values[key] as? String ?: default
    override fun getLong(key: String, default: Long) = values[key] as? Long ?: default
    override fun put(vararg values: Pair<String, Any?>): Boolean {
        val next = LinkedHashMap(this.values)
        values.forEach { (key, value) -> if (value == null) next.remove(key) else next[key] = value }
        this.values.clear()
        this.values.putAll(next)
        return true
    }
}
