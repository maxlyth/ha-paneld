package io.github.maxlyth.hapaneld.device.profile

import io.github.maxlyth.hapaneld.BuildConfig
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the actual YAML catalog packaged into the APK. The catalog bytes, not deleted Kotlin
 * singletons, are the authority for identity, matching, capabilities, and immutable revisions.
 */
class BundledProfileParityTest {
    private val bundled get() = BundledProfileFixtures.bundled
    private val bundledById get() = BundledProfileFixtures.bundledById

    /**
     * `has_native_navbar` gates whether `Native` may be chosen at all, and choosing it on a panel with
     * no system bar leaves no navigation. So absence is the conservative default and only hardware we
     * have actually verified may declare it — today that is the WF1589T alone.
     */
    @Test fun onlyVerifiedHardwareDeclaresANativeNavigationBar() {
        assertEquals(
            setOf("wf1589t"),
            bundled.filter { it.document.platform.hasNativeNavbar }.map { it.document.id }.toSet(),
        )
        // Generic must stay conservative: unknown hardware is not assumed to have a system bar.
        assertFalse(bundledById.getValue("generic").document.platform.hasNativeNavbar)
        assertFalse(bundledById.getValue("nspanel-pro").document.platform.hasNativeNavbar)
        // The declaration reaches the resolved profile, not just the parsed document.
        assertTrue(bundledById.getValue("wf1589t").profile().hasNativeNavbar)
        assertFalse(bundledById.getValue("nspanel-pro").profile().hasNativeNavbar)
    }

    @Test fun theNativeNavbarFieldIsDescribedToTheProfileEditorAndRejectsUnknownSiblings() {
        val descriptor = ProfileMetadata.schema.fields.single { it.path == "platform.has_native_navbar" }
        assertEquals("boolean", descriptor.type)
        assertFalse("an unverified profile must be able to omit it", descriptor.required)

        // The platform allowlist stays closed: adding one key must not open the block to any other.
        val raw = ProfileYaml.serialize(bundledById.getValue("wf1589t").document)
            .replace("  has_native_navbar: true", "  has_native_navbar: true\n  has_teleporter: true")
        val rejected = ProfileYaml.parse(raw)
        assertNull(rejected.document)
        assertTrue(rejected.issues.any { it.path == "platform.has_teleporter" && it.message == "Unknown field." })
    }

    /**
     * A microphone is only declared for hardware whose capture chain has actually produced audio.
     * The weaker signals lie: on 2026-08-28 every panel probed reported `android.hardware.microphone`
     * and offered `AUDIO_DEVICE_IN_BUILTIN_MIC` in its audio policy, yet a first-generation NSPanel Pro
     * captured 65,536 frames of digital silence with its codec's own capture path selected. Only the
     * WF1589T returned real signal, so only the WF1589T declares one.
     */
    @Test fun onlyHardwareWithProvenCaptureDeclaresAMicrophone() {
        assertEquals(
            setOf("wf1589t"),
            bundled.filter { it.document.hardware.hasMicrophone }.map { it.document.id }.toSet(),
        )
        // Unknown hardware stays conservative, and the panel that captured silence must never claim one.
        assertFalse(bundledById.getValue("generic").document.hardware.hasMicrophone)
        assertFalse(bundledById.getValue("nspanel-pro").document.hardware.hasMicrophone)
        // The declaration reaches the capability the voice settings gate on, not just the parsed document.
        assertTrue(bundledById.getValue("wf1589t").profile().hasMicrophone)
        assertFalse(bundledById.getValue("nspanel-pro").profile().hasMicrophone)
        // A camera is not a microphone. The WF1589T now declares both, so the witness that the two
        // keys are independent on real catalog content is the TPA10: it carries a camera, and its
        // capture chain has never produced audio, so it must declare the one and not the other.
        assertTrue(bundledById.getValue("tpa10").document.hardware.hasCamera)
        assertFalse(bundledById.getValue("tpa10").document.hardware.hasMicrophone)
        assertFalse(bundledById.getValue("tpa10").profile().hasMicrophone)
    }

    /**
     * A camera is declared for the boards that carry one, which is what makes the camera trial
     * reachable at all: `hardware.camera` is the gate in front of the camera settings, the foreground
     * service, the snapshot route, the RTSP transport and the two Home Assistant entities. Both of
     * these panels were probed on 2026-08-28 and each carries a GalaxyCore sensor with a working
     * hardware AVC encoder behind it. Every other bundled profile omits the key and stays closed.
     */
    @Test fun onlyHardwareWithACameraDeclaresOne() {
        assertEquals(
            setOf("tpa10", "wf1589t"),
            bundled.filter { it.document.hardware.hasCamera }.map { it.document.id }.toSet(),
        )
        // Unknown hardware stays conservative, and the NSPanel Pro line has no camera to declare.
        assertFalse(bundledById.getValue("generic").document.hardware.hasCamera)
        assertFalse(bundledById.getValue("nspanel-pro").document.hardware.hasCamera)
        // The declaration must reach the capability the camera surfaces gate on, not stop at the
        // document: a profile that parses a camera but resolves without one enables nothing.
        assertTrue(bundledById.getValue("tpa10").profile().hasCamera)
        assertTrue(bundledById.getValue("wf1589t").profile().hasCamera)
        assertFalse(bundledById.getValue("nspanel-pro").profile().hasCamera)
    }

    /**
     * `hardware.camera` and `hardware.microphone` declare what the board physically has. They are
     * deliberately independent: the TPA10 carries a camera whose capture chain has never been proven,
     * and the WF1589T carries both, so a single combined flag would misdescribe at least one of them.
     * Which profiles declare what is asserted above against real catalog content; these assertions
     * drive the parser from constructed YAML, so both keys are covered from both directions.
     */

    @Test fun cameraAndMicrophoneAreDeclaredIndependentlyAndSurviveARoundTrip() {
        listOf("hardware.camera", "hardware.microphone").forEach { path ->
            // singleOrNull, not single: a missing descriptor must fail this assertion rather than
            // throw out of the test, so the absence is reported as a defect and not as a broken test.
            val descriptor = ProfileMetadata.schema.fields.singleOrNull { it.path == path }
            assertNotNull("$path must be described to the profile editor", descriptor)
            assertEquals("boolean", descriptor!!.type)
            assertFalse("hardware without the part must be able to omit $path", descriptor.required)
        }

        // Absent means false, which is what every bundled profile without the part relies on. The
        // generic profile declares neither key, so it is the witness that omission reads as absence
        // both when parsed from the catalog and after a round trip.
        val none = bundledById.getValue("generic").document
        assertFalse(none.hardware.hasCamera)
        assertFalse(none.hardware.hasMicrophone)
        val noneReparsed = requireNotNull(ProfileYaml.parse(ProfileYaml.serialize(none)).document)
        assertFalse("an omitted camera key must not become true across a round trip", noneReparsed.hardware.hasCamera)
        assertFalse("an omitted microphone key must not become true across a round trip", noneReparsed.hardware.hasMicrophone)

        // A microphone without a camera is the case that matters, and it must survive serialization.
        val base = bundledById.getValue("nspanel-pro").document
        val micOnly = base.copy(hardware = base.hardware.copy(hasMicrophone = true))
        val reparsed = requireNotNull(ProfileYaml.parse(ProfileYaml.serialize(micOnly)).document)
        assertTrue("a microphone declaration must survive the round trip", reparsed.hardware.hasMicrophone)
        assertFalse("a microphone must not imply a camera", reparsed.hardware.hasCamera)
        assertEquals(micOnly, reparsed)

        // The declaration must reach the capability the settings gate on, not stop at the document.
        val micProfile = DataDeviceProfile(
            document = micOnly, productVersion = "", revision = "test", trustedBundledContent = true,
        )
        assertTrue(micProfile.hasMicrophone)
        assertFalse(micProfile.hasCamera)

        // Adding two keys must not open the hardware block to a third.
        val rejected = ProfileYaml.parse(
            ProfileYaml.serialize(micOnly).replace("  microphone: true", "  microphone: true\n  periscope: true")
        )
        assertNull(rejected.document)
        assertTrue(rejected.issues.any { it.path == "hardware.periscope" && it.message == "Unknown field." })
    }

    @Test fun theNativeNavbarDeclarationSurvivesASerializeParseRoundTrip() {
        val document = bundledById.getValue("wf1589t").document
        val reparsed = requireNotNull(ProfileYaml.parse(ProfileYaml.serialize(document)).document)
        assertTrue(reparsed.platform.hasNativeNavbar)
        assertEquals(document, reparsed)

        val plain = bundledById.getValue("nspanel-pro").document
        val plainReparsed = requireNotNull(ProfileYaml.parse(ProfileYaml.serialize(plain)).document)
        assertFalse(plainReparsed.platform.hasNativeNavbar)
    }

    @Test fun bundledCatalogHasUniqueIdsRawHashesAndExactlyOneGenericFallback() {
        assertTrue("no bundled profiles found", bundled.isNotEmpty())

        val ids = bundled.map { it.document.id }
        val hashes = bundled.map { it.rawSha256 }
        assertEquals("bundled profile catalog changed", EXPECTED_BUNDLED_IDS, ids.toSet())
        assertEquals(
            "bundled profile bytes changed without updating the migration golden",
            EXPECTED_BUNDLED_SHA256,
            bundled.associate { it.file.name to it.rawSha256 },
        )
        assertEquals("duplicate bundled profile ids", ids.size, ids.toSet().size)
        assertEquals("duplicate bundled YAML content", hashes.size, hashes.toSet().size)
        assertTrue(hashes.all { it.matches(Regex("[0-9a-f]{64}")) })
        bundled.forEach { source ->
            assertEquals("${source.file.path} filename/id mismatch", source.file.nameWithoutExtension, source.document.id)
        }

        val fallback = bundled.single { it.document.match.fallback }
        assertEquals("generic", fallback.document.id)
        assertTrue("fallback must not carry match branches", fallback.document.match.any.isEmpty())
        assertTrue(bundled.filterNot { it === fallback }.all { it.document.match.any.isNotEmpty() })
    }

    @Test fun bundledCatalogDeclaresShizukuOnlyForAnExactAlternateDriver() {
        val declared = bundled.filter {
            it.document.provisioning.access.shizuku != ShizukuRecommendation.NONE
        }
        assertEquals(setOf("zx-smt156"), declared.map { it.document.id }.toSet())
        declared.forEach { source ->
            assertTrue(
                "${source.document.id} declares Shizuku without an exact alternate driver",
                source.document.requires.drivers.any {
                    ProfileMetadata.helperAuthorityDemand[it] == ProfileHelperAuthorityDemand.SHIZUKU_ALTERNATE
                },
            )
        }
    }

    @Test fun bundledSocFactsDistinguishCoreClassesWithoutRuntimeGuessing() {
        fun soc(id: String) = requireNotNull(bundledById.getValue(id).document.soc)

        assertEquals("Rockchip RK3326 · 4× Arm Cortex-A35 · introduced 2018", soc("nspanel-pro").displayText())
        assertEquals("Rockchip RK3566 · 4× Arm Cortex-A55 · introduced 2020", soc("tpa10").displayText())
        assertEquals(
            "Rockchip RK3576 · 4× Arm Cortex-A72 + 4× Arm Cortex-A53 · introduced 2024",
            soc("wf1589t").displayText(),
        )
        assertTrue(bundledById.getValue("generic").document.soc == null)
        assertTrue(
            "aggregate legacy Shelly profile must not project the original model's SoC onto X2",
            bundledById.getValue("shelly-wall-display").document.soc == null,
        )
        assertTrue(
            "aggregate modern Shelly profile must not project one model's SoC onto other variants",
            bundledById.getValue("shelly-wall-display-v2").document.soc == null,
        )
        bundled.filter { it.document.soc != null }.forEach { source ->
            assertTrue("${source.document.id} did not declare a profile reference", source.document.metadata.source != null)
        }
    }

    @Test fun bundledProfilesUsingNewSchemaFieldsRequireTheRc1Core() {
        val featureBearing = bundled.filter {
            it.document.soc != null || it.document.metadata.links.isNotEmpty()
        }

        assertEquals(
            EXPECTED_BUNDLED_IDS - setOf("generic", "shelly-wall-display", "shelly-wall-display-v2"),
            featureBearing.map { it.document.id }.toSet(),
        )
        featureBearing.forEach { source ->
            assertEquals(
                "${source.document.id} uses schema fields introduced in rc1",
                "0.9.5-rc1",
                source.document.requires.minCoreVersion,
            )
        }
    }

    @Test fun aggregateShellyProfilesDoNotClaimUntestedModelSpecificFacts() {
        val legacy = bundledById.getValue("shelly-wall-display").document
        val modern = bundledById.getValue("shelly-wall-display-v2").document

        assertTrue(legacy.metadata.testedFirmware.isEmpty())
        assertTrue(modern.metadata.testedFirmware.isEmpty())
        assertNull(legacy.soc)
        assertNull(modern.soc)
        assertNull(modern.sensors.proximityTechnology)
        assertEquals("Ambient light", legacy.sensors.lightTechnology)
        assertEquals("Ambient light", modern.sensors.lightTechnology)
    }

    @Test fun resolverReturnsDataBackedProfilesWithTheExactRawYamlRevision() {
        resolutionMatrix().forEach { fixture ->
            val resolved = resolve(fixture.facts)
            val source = bundledById.getValue(fixture.expectedId)

            assertEquals("${fixture.label}: selected id", fixture.expectedId, resolved.profile.id)
            assertTrue("${fixture.label}: runtime profile was not YAML-backed", resolved.profile is DataDeviceProfile)
            assertEquals("${fixture.label}: runtime revision", source.rawSha256, resolved.profile.revision)
            assertEquals("${fixture.label}: summary revision", source.rawSha256, resolved.summary.ref.revision)
            assertEquals("${fixture.label}: parsed document", source.document, (resolved.profile as DataDeviceProfile).document)
        }
    }

    @Test fun everyBundledMatcherValueHasAResolverWitnessOwnedByItsProfileOrAnExplicitCollision() {
        val exercisedCollisions = mutableSetOf<BranchWitnessKey>()
        var negativeOperatorWitnesses = 0
        bundled.filterNot { it.document.match.fallback }.forEach { source ->
            source.document.match.any.forEachIndexed { index, group ->
                branchWitnesses(group).forEach { witness ->
                    val key = BranchWitnessKey(
                        profileId = source.document.id,
                        groupIndex = index,
                        predicateIndex = witness.predicateIndex,
                        value = witness.value,
                    )
                    val expectedId = BRANCH_WITNESS_COLLISIONS[key] ?: source.document.id
                    if (key in BRANCH_WITNESS_COLLISIONS) exercisedCollisions += key
                    assertTrue("$key did not satisfy its source profile for ${witness.facts}", source.document.matches(witness.facts))

                    val resolved = resolve(witness.facts)
                    assertEquals("$key for ${witness.facts}", expectedId, resolved.profile.id)
                    assertEquals(bundledById.getValue(expectedId).rawSha256, resolved.profile.revision)

                    val predicate = group.all[witness.predicateIndex]
                    operatorNearMiss(predicate, witness.value, witness.facts)?.let { nearMiss ->
                        negativeOperatorWitnesses++
                        val isolatedBranch = source.document.copy(
                            match = source.document.match.copy(any = listOf(group)),
                        )
                        assertFalse(
                            "$key ${predicate.op} accepted its operator near-miss $nearMiss",
                            isolatedBranch.matches(nearMiss),
                        )
                    }
                }
            }
        }
        assertEquals("stale or unexercised matcher collision exceptions", BRANCH_WITNESS_COLLISIONS.keys, exercisedCollisions)
        assertTrue("catalog no longer exercises equality/starts-with negative witnesses", negativeOperatorWitnesses > 0)
    }

    @Test fun publicAuthoringExamplesValidateAsImportedProfiles() {
        val examplesDir = listOf(
            File("docs/profiles/examples"),
            File("../docs/profiles/examples"),
        ).firstOrNull(File::isDirectory) ?: error("Public profile examples directory not found")
        val examples = examplesDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in setOf("yaml", "yml") }
        assertTrue("no public YAML examples found", examples.isNotEmpty())
        examples.forEach { example ->
            val parsed = ProfileYaml.parse(example.readText())
            assertNotNull("${example.name} did not parse: ${parsed.issues}", parsed.document)
            val issues = parsed.issues + ProfileValidator.validate(parsed.document!!, CORE_VERSION, bundled = false)
            assertTrue("${example.name} has schema/driver issues: $issues", issues.isEmpty())
        }
    }

    @Test fun unofficialCatalogIsValidDisjointAndAbsentFromApkAssets() {
        val directory = BundledProfileFixtures.unofficialDirectory
        assertNotNull("docs/profiles/unofficial is missing", directory)

        val unofficial = BundledProfileFixtures.unofficial
        assertTrue("unofficial profile catalog is empty", unofficial.isNotEmpty())
        assertTrue("unofficial profiles cannot be fallbacks", unofficial.none { it.document.match.fallback })

        val bundledIds = bundled.mapTo(mutableSetOf()) { it.document.id }
        val unofficialIds = unofficial.mapTo(mutableSetOf()) { it.document.id }
        assertEquals("unofficial profile catalog changed", EXPECTED_UNOFFICIAL_IDS, unofficialIds)
        assertEquals(
            "unofficial profile bytes changed without updating the reviewed catalog golden",
            EXPECTED_UNOFFICIAL_SHA256,
            unofficial.associate { it.file.name to it.rawSha256 },
        )
        assertEquals("duplicate unofficial profile ids", unofficial.size, unofficialIds.size)
        assertTrue("profile ids occur in both catalogs: ${bundledIds intersect unofficialIds}", bundledIds.intersect(unofficialIds).isEmpty())

        val assetNames = bundled.mapTo(mutableSetOf()) { it.file.name }
        val unofficialNames = unofficial.mapTo(mutableSetOf()) { it.file.name }
        assertEquals("unofficial profile filenames changed", EXPECTED_UNOFFICIAL_FILENAMES, unofficialNames)
        assertTrue("unofficial filenames occur in APK assets: ${assetNames intersect unofficialNames}", assetNames.intersect(unofficialNames).isEmpty())

        val assetHashes = bundled.mapTo(mutableSetOf()) { it.rawSha256 }
        val unofficialHashes = unofficial.mapTo(mutableSetOf()) { it.rawSha256 }
        assertEquals("duplicate unofficial YAML content", unofficial.size, unofficialHashes.size)
        assertTrue("unofficial YAML bytes occur in APK assets", assetHashes.intersect(unofficialHashes).isEmpty())
    }

    @Test fun unofficialEchoMatcherRequiresTheObservedCompoundIdentity() {
        val candidate = BundledProfileFixtures.unofficial.single {
            it.document.id == "community.cronos-lineageos18"
        }.document
        val exact = DeviceFacts(
            model = "amzn echo show 5 (2nd generation)",
            device = "cronos",
            productVersion = "",
        )

        assertTrue("observed compound identity no longer matches", candidate.matches(exact))
        assertFalse("device-only identity must not match", candidate.matches(exact.copy(model = "unrelated")))
        assertFalse("model-only identity must not match", candidate.matches(exact.copy(device = "unrelated")))

        val branch = candidate.match.any.single()
        assertEquals(
            setOf(
                ProfilePredicate(ProfileFact.DEVICE, ProfileMatchOp.EQUALS, listOf("cronos")),
                ProfilePredicate(
                    ProfileFact.MODEL,
                    ProfileMatchOp.EQUALS,
                    listOf("amzn echo show 5 (2nd generation)"),
                ),
            ),
            branch.all.toSet(),
        )
        assertEquals("compound matcher must not contain duplicate predicates", 2, branch.all.size)
    }

    /**
     * The Pi 4 matcher is deliberately looser than the Echo's exact-equals pair, because the board model
     * carries a hardware revision suffix that varies per unit. It still has to stay compound: `rpi4` alone
     * is shared with the Pi 400 and CM4, so device-only must never be enough. The model substring is
     * chosen to survive either reading of the vendor's manufacturer/model split, which is why it does not
     * begin with "pi".
     */
    @Test fun unofficialRpi4MatcherRequiresTheObservedCompoundIdentity() {
        val candidate = BundledProfileFixtures.unofficial.single {
            it.document.id == "community.rpi4-konstakang-lineageos"
        }.document
        val exact = DeviceFacts(model = "pi 4 model b rev 1.4", device = "rpi4", productVersion = "")

        assertTrue("observed compound identity no longer matches", candidate.matches(exact))
        assertTrue(
            "the profile must match whichever way the vendor splits manufacturer and model",
            candidate.matches(exact.copy(model = "raspberry pi 4 model b rev 1.4")) &&
                candidate.matches(exact.copy(model = "4 model b rev 1.4")),
        )
        assertTrue("hardware revisions must not need a new profile", candidate.matches(exact.copy(model = "pi 4 model b rev 1.2")))
        assertFalse("device-only identity must not match", candidate.matches(exact.copy(model = "unrelated")))
        assertFalse("model-only identity must not match", candidate.matches(exact.copy(device = "unrelated")))
        assertFalse("the pi 400 shares the board codename", candidate.matches(exact.copy(model = "pi 400 rev 1.1")))
        assertFalse("the compute module 4 shares the board codename", candidate.matches(exact.copy(model = "compute module 4 rev 1.1")))
        assertFalse("the pi 5 is out of scope", candidate.matches(DeviceFacts("pi 5 model b rev 1.0", "rpi5", "")))

        val branch = candidate.match.any.single()
        assertEquals(
            setOf(
                ProfilePredicate(ProfileFact.DEVICE, ProfileMatchOp.EQUALS, listOf("rpi4")),
                ProfilePredicate(ProfileFact.MODEL, ProfileMatchOp.CONTAINS, listOf("4 model b")),
            ),
            branch.all.toSet(),
        )
        assertEquals("compound matcher must not contain duplicate predicates", 2, branch.all.size)
    }

    /**
     * The one declared behaviour this profile exists for. A backlight-less board cannot reach a real
     * screen-off any other way, and the route is a declaration rather than a probe because local touch
     * wake is a property of the owner's touchscreen.
     */
    @Test fun unofficialRpi4SelectsTheKeyeventScreenRouteAndNoPrivilegedHardware() {
        val candidate = BundledProfileFixtures.unofficial.single {
            it.document.id == "community.rpi4-konstakang-lineageos"
        }.document

        assertEquals("keyevent", candidate.hardware.screenOff)
        assertTrue("the keyevent driver must be declared", "screen.keyevent" in candidate.requires.drivers)
        assertEquals(setOf("access.android-su", "screen.keyevent"), candidate.requires.drivers)
        assertEquals("none", candidate.hardware.led.mechanism)
        assertFalse("no button backlight was reported", candidate.hardware.hasButtonBacklight)
        assertNull("no relay hardware was reported", candidate.hardware.relayBase)
        assertNull("no zigbee radio was reported", candidate.hardware.zigbeeGatewayDir)
        assertFalse("no room-climate sensor was reported", candidate.sensors.cht8305)
        assertNull("no proximity sensor was reported", candidate.sensors.proximityTechnology)
        assertNull("no ambient-light sensor was reported", candidate.sensors.lightTechnology)
        assertTrue("the touchscreen must never be grabbed", candidate.input.evdevButtons.isEmpty())
        assertNull("the attached display is owner-supplied, so density must not be recommended", candidate.provisioning.display.density)
        assertNull("the attached display is owner-supplied, so its ppi is unknowable", candidate.display.physicalPpi)
        assertFalse("a native navbar was never verified on this board", candidate.platform.hasNativeNavbar)
    }

    @Test fun actualUnofficialYamlIsInertUntilMatchingExplicitActivationAndCanRollback() {
        BundledProfileFixtures.unofficial.forEach { candidate ->
            val matchingFacts = witness(candidate.document.match.any.maxBy { it.priority })

            withRegistry(matchingFacts) { registry ->
                val previous = registry.resolveForStartup().summary.ref
                val preview = registry.preview(candidate.rawYaml)
                assertTrue("${candidate.file.path}: ${preview.issues}", preview.compatible)
                val summary = requireNotNull(preview.summary)
                assertTrue(candidate.file.path, summary.matchesThisDevice)
                assertEquals(candidate.file.path, candidate.rawSha256, preview.contentSha256)
                val ref = summary.ref
                assertEquals(candidate.file.path, candidate.rawSha256, ref.revision)
                assertTrue(registry.importProfile(candidate.rawYaml, requireNotNull(preview.previewToken)) is ProfileMutation.Success)

                assertEquals(ProfileSelection.Auto, registry.status().selection)
                assertNotEquals("${candidate.file.path} auto-activated from its fingerprint", ref, registry.resolveForStartup().summary.ref)

                val selected = registry.select(ProfileSelection.Pinned(ref), registry.status().catalogRevision)
                assertTrue("${candidate.file.path}: $selected", selected is ProfileMutation.Success && selected.restartRequired)
                val applying = registry.resolveForStartup()
                assertEquals(candidate.file.path, ref, applying.summary.ref)
                assertTrue(candidate.file.path, applying.profile is DataDeviceProfile)
                assertEquals(candidate.file.path, candidate.rawSha256, applying.profile.revision)
                assertEquals(candidate.file.path, candidate.document, (applying.profile as DataDeviceProfile).document)
                assertTrue(candidate.file.path, registry.markActivationHealthy(requireNotNull(applying.activationGeneration)))
                assertEquals(candidate.file.path, ref, registry.status().active?.ref)

                val rollback = registry.rollbackToLastKnownGood(registry.status().catalogRevision)
                assertTrue("${candidate.file.path}: $rollback", rollback is ProfileMutation.Success && rollback.restartRequired)
                val restored = registry.resolveForStartup()
                assertEquals(candidate.file.path, previous, restored.summary.ref)
                assertTrue(candidate.file.path, registry.markActivationHealthy(requireNotNull(restored.activationGeneration)))
            }

            withRegistry(mismatchFacts(candidate.document)) { registry ->
                val preview = registry.preview(candidate.rawYaml)
                val summary = requireNotNull(preview.summary)
                assertFalse(candidate.file.path, summary.matchesThisDevice)
                val ref = summary.ref
                assertTrue(registry.importProfile(candidate.rawYaml, requireNotNull(preview.previewToken)) is ProfileMutation.Success)

                val selected = registry.select(ProfileSelection.Pinned(ref), registry.status().catalogRevision)

                assertTrue("${candidate.file.path}: $selected", selected is ProfileMutation.Rejected)
                assertTrue((selected as ProfileMutation.Rejected).issues.any { it.path == "selection.match" })
                assertEquals(ProfileSelection.Auto, registry.status().selection)
                assertFalse(registry.status().activation.phase == ProfileActivationPhase.PENDING)
            }
        }
    }

    @Test fun namedModelAndDensityStrategiesAreExecutedByTheYamlAdapter() {
        val strategies = bundled.filter { it.document.identity.modelLabelStrategy != "display-name" }
        assertTrue("catalog no longer exercises a named model/density strategy", strategies.isNotEmpty())
        strategies.forEach { source ->
            assertEquals("nspanel-product-version", source.document.identity.modelLabelStrategy)
            val compact = source.profile("s6_android_2.9.9")
            val wide = source.profile("NSPanel120P_3.5.0")
            assertEquals("NSPanel 86P · fw 2.9.9", compact.panelModelLabel("s6_android_2.9.9"))
            assertEquals(160, compact.recommendedDensity)
            assertEquals("NSPanel 120P · fw 3.5.0", wide.panelModelLabel("NSPanel120P_3.5.0"))
            assertEquals(250, wide.recommendedDensity)
            assertEquals(source.document.displayName, source.profile("unrelated").panelModelLabel("unrelated"))
        }
    }

    /**
     * Product-specific rows pin the externally observed fingerprints. The labelled collision rows are
     * especially important: they prove exact product/device and firmware branches outrank broad shared
     * SoC aliases without consulting a second compiled detector.
     */
    private fun resolutionMatrix(): List<ResolutionFixture> = listOf(
        ResolutionFixture("reference model", DeviceFacts("PX30_EVB", "px30", ""), "nspanel-pro"),
        ResolutionFixture("second-generation reference device", DeviceFacts("", "rk3326-s", ""), "nspanel-pro"),
        ResolutionFixture("firmware identity", DeviceFacts("unknown", "unknown", "NSPanel120P_3.7.1"), "nspanel-pro"),
        ResolutionFixture("exact model", DeviceFacts("TPA10", "unrelated", ""), "tpa10"),
        ResolutionFixture("exact device", DeviceFacts("unrelated", "tpa10", ""), "tpa10"),
        ResolutionFixture("exact rk3566 device", DeviceFacts("unrelated", "rk3566_t", ""), "zx-smt156"),
        ResolutionFixture("modern display codename", DeviceFacts("blake", "", ""), "shelly-wall-display-v2"),
        ResolutionFixture("modern display sku", DeviceFacts("rk3326", "SAWD-5A1XX10EU0", ""), "shelly-wall-display-v2"),
        ResolutionFixture("legacy display codename", DeviceFacts("stargate", "", ""), "shelly-wall-display"),
        ResolutionFixture("legacy display chipset", DeviceFacts("", "k400_mt6580", ""), "shelly-wall-display"),
        ResolutionFixture("sandboxed ioctl device", DeviceFacts("rk3576_u", "wf2489t", ""), "smt1019"),
        ResolutionFixture("ioctl device", DeviceFacts("rk3576_u", "wf1589t", ""), "wf1589t"),
        ResolutionFixture("vendor firmware", DeviceFacts("foo", "bar", "s9_android_1.1.0"), "s9e"),
        ResolutionFixture("unknown fallback", DeviceFacts("mysterypanel", "unknowndev", ""), "generic"),
        ResolutionFixture("collision: exact SKU over broad reference SoC", DeviceFacts("PX30_EVB", "Jenna", ""), "shelly-wall-display-v2"),
        ResolutionFixture("collision: exact device over vendor firmware", DeviceFacts("tpa10", "tpa10", "s9_android_1.1.0"), "tpa10"),
        ResolutionFixture("collision: exact device over broad rk3576 model", DeviceFacts("rk3576_u", "wf2489t", "s9_android_1.1.0"), "smt1019"),
        ResolutionFixture("collision: exact rk3566 device over broad rk3576 model", DeviceFacts("rk3576_u", "rk3566_t", ""), "zx-smt156"),
        ResolutionFixture("collision: firmware identity over broad rk3576 model", DeviceFacts("rk3576_u", "unrelated", "NSPanel120P_3.7.1"), "nspanel-pro"),
        ResolutionFixture("case-insensitive identity", DeviceFacts("RK3576_U", "WF2489T", ""), "smt1019"),
    )

    private fun witness(group: ProfileMatchGroup): DeviceFacts {
        return branchWitnesses(group).first().facts
    }

    /**
     * Give every literal matcher value its own resolver input. A compound branch may use one fact per
     * field; repeated predicates for the same fact require an explicit fixture because an arbitrary pair
     * of equals/contains/starts-with predicates cannot be combined safely by a generic witness builder.
     */
    private fun branchWitnesses(group: ProfileMatchGroup): List<BranchWitness> {
        check(group.all.isNotEmpty()) { "match branch has no predicates" }
        val duplicateFields = group.all.groupBy { it.field }.filterValues { it.size > 1 }.keys
        check(duplicateFields.isEmpty()) {
            "generic witness builder cannot combine repeated fields $duplicateFields in $group"
        }
        return group.all.flatMapIndexed { predicateIndex, selectedPredicate ->
            selectedPredicate.values.map { selectedValue ->
                val values = mutableMapOf(
                    ProfileFact.MODEL to "unmatched-model",
                    ProfileFact.DEVICE to "unmatched-device",
                    ProfileFact.PRODUCT_VERSION to "unmatched-version",
                )
                group.all.forEachIndexed { index, predicate ->
                    values[predicate.field] = if (index == predicateIndex) {
                        when (predicate.op) {
                            ProfileMatchOp.EQUALS -> selectedValue
                            ProfileMatchOp.STARTS_WITH -> "$selectedValue-witness-suffix"
                            ProfileMatchOp.CONTAINS -> "witness-prefix-$selectedValue-witness-suffix"
                        }
                    } else {
                        predicate.values.first()
                    }
                }
                BranchWitness(
                    predicateIndex = predicateIndex,
                    value = selectedValue,
                    facts = DeviceFacts(
                        model = values.getValue(ProfileFact.MODEL),
                        device = values.getValue(ProfileFact.DEVICE),
                        productVersion = values.getValue(ProfileFact.PRODUCT_VERSION),
                    ),
                )
            }
        }
    }

    private fun mismatchFacts(document: ProfileDocument): DeviceFacts = generateSequence(0) { it + 1 }
        .map { index -> DeviceFacts("unmatched-model-$index", "unmatched-device-$index", "unmatched-version-$index") }
        .first { !document.matches(it) }

    /** Negative probes are keyed by the declared operator, so they detect implementation broadening:
     * EQUALS must reject a superstring; STARTS_WITH must reject the same literal embedded mid-string. */
    private fun operatorNearMiss(
        predicate: ProfilePredicate,
        selectedValue: String,
        matchingFacts: DeviceFacts,
    ): DeviceFacts? {
        val nearValue = when (predicate.op) {
            ProfileMatchOp.EQUALS -> "$selectedValue-operator-near-miss"
            ProfileMatchOp.STARTS_WITH -> "operator-near-miss-$selectedValue"
            ProfileMatchOp.CONTAINS -> return null
        }
        return when (predicate.field) {
            ProfileFact.MODEL -> matchingFacts.copy(model = nearValue)
            ProfileFact.DEVICE -> matchingFacts.copy(device = nearValue)
            ProfileFact.PRODUCT_VERSION -> matchingFacts.copy(productVersion = nearValue)
        }
    }

    private fun resolve(facts: DeviceFacts): ResolvedProfile {
        val filesDir = Files.createTempDirectory("bundled-profile-contract").toFile()
        return try {
            RuntimeProfileRegistry(
                filesDir = filesDir,
                preferences = MemoryPreferences(),
                bundledLoader = BundledProfileFixtures::rawByName,
                facts = facts,
                coreVersion = CORE_VERSION,
                clock = { 1_000L },
            ).resolveForStartup()
        } finally {
            filesDir.deleteRecursively()
        }
    }

    private fun withRegistry(facts: DeviceFacts, block: (RuntimeProfileRegistry) -> Unit) {
        val filesDir = Files.createTempDirectory("unofficial-profile-contract").toFile()
        try {
            block(
                RuntimeProfileRegistry(
                    filesDir = filesDir,
                    preferences = MemoryPreferences(),
                    bundledLoader = BundledProfileFixtures::rawByName,
                    facts = facts,
                    coreVersion = CORE_VERSION,
                    clock = { 1_000L },
                ),
            )
        } finally {
            filesDir.deleteRecursively()
        }
    }

    private data class ResolutionFixture(
        val label: String,
        val facts: DeviceFacts,
        val expectedId: String,
    )

    private data class BranchWitness(
        val predicateIndex: Int,
        val value: String,
        val facts: DeviceFacts,
    )

    private data class BranchWitnessKey(
        val profileId: String,
        val groupIndex: Int,
        val predicateIndex: Int,
        val value: String,
    )

    private class MemoryPreferences : ProfilePreferences {
        private val values = mutableMapOf<String, Any?>()
        override fun getString(key: String, default: String): String = values[key] as? String ?: default
        override fun getLong(key: String, default: Long): Long = values[key] as? Long ?: default
        override fun put(vararg values: Pair<String, Any?>): Boolean {
            values.forEach { (key, value) ->
                if (value == null) this.values.remove(key) else this.values[key] = value
            }
            return true
        }
    }

    private companion object {
        val CORE_VERSION: String = BuildConfig.VERSION_NAME
        val EXPECTED_BUNDLED_IDS = setOf(
            "generic",
            "nspanel-pro",
            "s9e",
            "shelly-wall-display-v2",
            "shelly-wall-display",
            "smt1019",
            "tpa10",
            "wf1589t",
            "zx-smt156",
        )
        val EXPECTED_BUNDLED_SHA256 = mapOf(
            "generic.yaml" to "c95dd07e605c826b092c141c111f5f1181e98f5f4833d426f9f6bebab4ab5eb9",
            "nspanel-pro.yaml" to "86f6b9071e205a073353c57e14346fc4fceba06a16ab021a1b1adcfc863456b1",
            "s9e.yaml" to "b01253348e986d91516788ee3e56f43825b058fecc02d367f5bb0ea73b83abd8",
            "shelly-wall-display-v2.yaml" to "16415916b2cc0841fccee75709f3b10d3b6a431e3532593c53cb0d34a89fcd24",
            "shelly-wall-display.yaml" to "11a58c3ab0535ff522d97c25870f2a640ed733062a4cee19a3367505ea6a82cb",
            "smt1019.yaml" to "3004666dd80585a9f57f846f8db5bbde9b781bb8d669921d9406ab88a5a84289",
            "tpa10.yaml" to "78a1c3559f52847306d71af701accd9854b6470531d5661b64800e0b7177b870",
            "wf1589t.yaml" to "1562d11445d6a520db3cd56845b1ae47368851bc4de517757473301bd9392977",
            "zx-smt156.yaml" to "80de45864b9fef6f813dcd8092c5afff34a588663f556f699c8dfb608ac47573",
        )
        val EXPECTED_UNOFFICIAL_IDS = setOf(
            "community.cronos-lineageos18",
            "community.rpi4-konstakang-lineageos",
        )
        val EXPECTED_UNOFFICIAL_FILENAMES = setOf(
            "community-cronos-lineageos18.yaml",
            "community-rpi4-konstakang-lineageos.yaml",
        )
        val EXPECTED_UNOFFICIAL_SHA256 = mapOf(
            "community-cronos-lineageos18.yaml" to
                "c0207b2b43f46d84641d2d33683cb7fb0e8a4013544827bd3041337a40d02ea2",
            "community-rpi4-konstakang-lineageos.yaml" to
                "e49e0db3e29d8bb77c581c32a2f70d55bc629178d4bb2077a7b55d1885bd2e29",
        )

        /** Branch-level collisions belong here; matrix-level cross-profile collisions are pinned above. */
        val BRANCH_WITNESS_COLLISIONS: Map<BranchWitnessKey, String> = emptyMap()
    }
}
