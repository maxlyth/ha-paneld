package io.github.maxlyth.hapaneld.device.profile

/** Core-owned vocabulary available to untrusted profile files. */
object ProfileMetadata {
    const val SCHEMA = 2
    const val MAX_BYTES = 128 * 1024
    const val MAX_DEPTH = 20
    const val MAX_COLLECTION_SIZE = 512
    const val MAX_STRING_LENGTH = 16 * 1024
    const val MAX_IMPORTED_REVISIONS = 128
    const val MAX_IMPORTED_REVISIONS_PER_ID = 16
    const val MAX_IMPORTED_BYTES = 4 * 1024 * 1024L

    val drivers: List<ProfileDriverDescriptor> = listOf(
        ProfileDriverDescriptor("access.android-su", ProfileDriverKind.ACCESS, "Android su invocation and runtime fallback", true),
        ProfileDriverDescriptor("access.toolbox-su", ProfileDriverKind.ACCESS, "Toolbox su invocation and runtime fallback", true),
        ProfileDriverDescriptor("input.button-backlight", ProfileDriverKind.INPUT, "Helper-backed monochrome button backlight", true),
        ProfileDriverDescriptor("input.evdev", ProfileDriverKind.INPUT, "Helper-backed Linux input event watcher", true),
        ProfileDriverDescriptor("led.autodetect", ProfileDriverKind.LED, "Probe built-in LED transports including helper fallback", true),
        ProfileDriverDescriptor("led.rk3576-ioctl", ProfileDriverKind.LED, "Built-in /dev/ledjni ioctl with helper fallback", true),
        ProfileDriverDescriptor("led.rk3576-ioctl-daemon", ProfileDriverKind.LED, "Helper-backed /dev/ledjni ioctl protocol", true),
        ProfileDriverDescriptor("led.sysfs-daemon", ProfileDriverKind.LED, "Helper-backed supported RGB sysfs protocol", true),
        ProfileDriverDescriptor("radio.siliconlabs-host", ProfileDriverKind.RADIO, "Sonoff Silicon Labs gateway directory", true),
        ProfileDriverDescriptor("relay.sysfs", ProfileDriverKind.RELAY, "Supported relay sysfs class", true),
        ProfileDriverDescriptor("relay.gpio-button-led", ProfileDriverKind.RELAY, "Root-backed button LED GPIO block", true),
        ProfileDriverDescriptor("screen.brightness-zero", ProfileDriverKind.SCREEN, "Application-level brightness-zero screen off", false),
        ProfileDriverDescriptor("screen.daemon-blpower", ProfileDriverKind.SCREEN, "Helper-backed kernel bl_power screen off", true),
        ProfileDriverDescriptor("screen.su-blpower", ProfileDriverKind.SCREEN, "su-backed kernel bl_power screen off", true),
        ProfileDriverDescriptor("sensor.android", ProfileDriverKind.SENSOR, "Android SensorManager light/proximity inputs", false),
        ProfileDriverDescriptor("sensor.cht8305-daemon", ProfileDriverKind.SENSOR, "Helper-backed CHT8305 temperature/humidity input", true),
        ProfileDriverDescriptor("sensor.gpio-proximity", ProfileDriverKind.SENSOR, "Root-backed binary proximity GPIO", true),
        ProfileDriverDescriptor("update.webview", ProfileDriverKind.UPDATE, "Core-owned System WebView artifacts: ${ProfileArtifacts.webViews.keys.sorted().joinToString()}", true),
    )

    /**
     * Root-helper authority demand for each core driver. This is deliberately separate from
     * [ProfileDriverDescriptor.privileged]: a privileged driver may use app `su`, a trusted-host
     * operation, or the root helper, and only the last one belongs in `access.helper`.
     */
    internal val helperAuthorityDemand: Map<String, ProfileHelperAuthorityDemand> = mapOf(
        "access.android-su" to ProfileHelperAuthorityDemand.NONE,
        "access.toolbox-su" to ProfileHelperAuthorityDemand.NONE,
        "input.button-backlight" to ProfileHelperAuthorityDemand.REQUIRED,
        "input.evdev" to ProfileHelperAuthorityDemand.REQUIRED,
        "led.autodetect" to ProfileHelperAuthorityDemand.SANDBOX_FALLBACK,
        "led.rk3576-ioctl" to ProfileHelperAuthorityDemand.SANDBOX_FALLBACK,
        "led.rk3576-ioctl-daemon" to ProfileHelperAuthorityDemand.REQUIRED,
        "led.sysfs-daemon" to ProfileHelperAuthorityDemand.REQUIRED,
        "radio.siliconlabs-host" to ProfileHelperAuthorityDemand.NONE,
        "relay.sysfs" to ProfileHelperAuthorityDemand.NONE,
        "relay.gpio-button-led" to ProfileHelperAuthorityDemand.NONE,
        "screen.brightness-zero" to ProfileHelperAuthorityDemand.NONE,
        "screen.daemon-blpower" to ProfileHelperAuthorityDemand.REQUIRED,
        "screen.su-blpower" to ProfileHelperAuthorityDemand.SANDBOX_FALLBACK,
        "sensor.android" to ProfileHelperAuthorityDemand.NONE,
        "sensor.cht8305-daemon" to ProfileHelperAuthorityDemand.REQUIRED,
        "sensor.gpio-proximity" to ProfileHelperAuthorityDemand.NONE,
        "update.webview" to ProfileHelperAuthorityDemand.NONE,
    )

    /** Core-owned workflow identifiers selectable by schema 2. Profiles cannot define recipe behavior. */
    val recipes: Set<String> = setOf(
        "nspanel-pro.watchdog-e2big-repair",
        "tpa10.vendor-stack-minimize",
    )

    val schema: ProfileSchemaDescriptor = ProfileSchemaDescriptor(
        schema = SCHEMA,
        maxBytes = MAX_BYTES,
        fields = listOf(
            field("schema", "integer", true, "Profile schema version; schema 2 is the current preview."),
            field("id", "slug", true, "Stable lowercase letters/digits with interior dots or hyphens."),
            field("version", "version", true, "Semantic version of this profile's content."),
            field("display_name", "string", true, "Human-readable panel name."),
            field("soc_class", "string", true, "Human-readable SoC family."),
            field("metadata.author", "string", true, "Profile author or organization."),
            field("metadata.source", "https-url", false, "Canonical source page for this profile."),
            field("metadata.license", "spdx-expression", true, "SPDX-style license expression."),
            field("metadata.maturity", "enum", true, "Evidence maturity.", listOf("draft", "experimental", "verified")),
            field("metadata.tested_firmware", "string[]", false, "Firmware builds against which this revision was tested."),
            field("metadata.limitations", "string[]", false, "Known profile limitations shown during review."),
            field("requires.min_core_version", "version", false, "Oldest compatible ha-paneld core."),
            field("requires.drivers", "string[]", true, "Core-owned driver ids used by the profile."),
            field("match.priority", "integer", true, "Automatic-match priority from 0 to 1000."),
            field("match.fallback", "boolean", true, "True only for the bundled generic fallback."),
            field("match.any[].priority", "integer", true, "Branch specificity from 0 to 1000; exact identities outrank broad SoC matches."),
            field("match.any[].all[].field", "enum", true, "Immutable build fact.", ProfileFact.entries.map { it.yamlName }),
            field("match.any[].all[].op", "enum", true, "Bounded string comparison.", ProfileMatchOp.entries.map { it.yamlName }),
            field("match.any[].all[].values", "string[]", true, "Lowercase comparison values."),
            field("platform.su_form", "enum", true, "Supported su calling convention.", listOf("none", "android", "toolbox")),
            field("platform.app_can_su", "boolean", true, "Whether an ordinary app can attempt su."),
            field("platform.has_recents", "boolean", false, "Whether Android Recents is functional."),
            field("hardware.led.mechanism", "enum", true, "Built-in LED route.", listOf("none", "autodetect", "rk3576-ioctl", "rk3576-ioctl-daemon", "sysfs-daemon")),
            field("hardware.led.transfer", "enum", true, "Core-owned LED transfer function.", listOf("identity", "rk3576-four-bit")),
            field("hardware.screen_off", "enum", true, "Preferred screen-off route.", listOf("brightness-zero", "su-blpower", "daemon-blpower")),
            field("hardware.has_button_backlight", "boolean", false, "Helper-backed button backlight capability."),
            field("hardware.zigbee_gateway_dir", "path", false, "Supported Sonoff gateway directory."),
            field("hardware.relay_base", "path", false, "Preferred supported relay sysfs class."),
            field("hardware.relay_base_fallbacks", "path[]", false, "Alternative relay sysfs classes."),
            field("hardware.button_led_gpio_base", "integer", false, "First GPIO in a button LED block."),
            field("sensors.proximity_technology", "string", false, "Author-declared proximity technology."),
            field("sensors.proximity_near_below", "boolean", false, "Whether lower raw values mean nearer."),
            field("sensors.proximity_near_raw", "number", false, "Profiled near reference reading."),
            field("sensors.proximity_far_raw", "number", false, "Profiled far reference reading."),
            field("sensors.proximity_gpio", "integer", false, "Supported raw binary proximity GPIO."),
            field("sensors.proximity_graded_strategy", "enum", false, "Core-owned firmware rule.", listOf("observed", "nspanel-firmware-cutover")),
            field("sensors.light_technology", "string", false, "Author-declared ambient light technology."),
            field("sensors.cht8305", "boolean", false, "Expose the supported helper-backed CHT8305 input."),
            field("sensors.room_temp_offset_c", "number", false, "Profile baseline temperature correction."),
            field("identity.manufacturer", "string", false, "Default HA manufacturer; null means infer."),
            field("identity.model", "string", false, "Default HA model; null means infer."),
            field("identity.model_label_strategy", "enum", false, "Core-owned local label strategy.", listOf("display-name", "nspanel-product-version")),
            field("input.evdev_buttons[]", "object[]", false, "Supported helper-backed evdev mappings."),
            field("cpu.governors", "map<string,string>", false, "HA CPU tier to Linux governor mapping."),
            field("display.physical_ppi", "integer", false, "Author-evidenced physical pixel density."),
            field("provisioning.access.shizuku", "enum", false, "Author recommendation only, never live readiness or consent.", listOf("none", "optional", "recommended")),
            field("provisioning.software.webview.artifact", "enum", false, "Core-owned APK artifact and signer trust root.", ProfileArtifacts.webViews.keys.sorted()),
            field("provisioning.software.companion.max_version", "version", false, "Newest known-good Companion version."),
            field("provisioning.display.density", "integer|strategy", false, "Recommended fixed dpi or nspanel-variant strategy."),
            field("provisioning.display.font_scale", "number", false, "Recommended WebView text scale."),
            field("provisioning.packages[].package", "package-name", true, "Android package whose desired state is declared."),
            field("provisioning.packages[].desired_state", "enum", true, "Desired package state; never implicit consent.", listOf("disabled")),
            field("provisioning.packages[].importance", "enum", true, "Presentation priority only.", listOf("recommended", "optional")),
            field("provisioning.packages[].tags", "string[]", false, "Bounded classifier labels."),
            field("provisioning.packages[].note", "string", false, "Bounded explanatory note; never procedure text."),
            field("provisioning.recipes[].id", "enum", true, "Core-owned provisioning recipe id; no arguments or commands.", recipes.sorted()),
        ),
    )

    private fun field(
        path: String,
        type: String,
        required: Boolean,
        description: String,
        values: List<String> = emptyList(),
    ) = ProfileFieldDescriptor(path, type, required, values, description)
}

/**
 * Whether selecting a driver makes the root helper part of the core provisioning contract.
 *
 * [SANDBOX_FALLBACK] mirrors controllers that prefer an app-side privileged route and then fall back
 * to the helper. The helper becomes required when the active profile marks the app sandbox as walled;
 * it remains a resilience fallback rather than a required install on app-su profiles.
 */
internal enum class ProfileHelperAuthorityDemand {
    NONE,
    SANDBOX_FALLBACK,
    REQUIRED,
}
