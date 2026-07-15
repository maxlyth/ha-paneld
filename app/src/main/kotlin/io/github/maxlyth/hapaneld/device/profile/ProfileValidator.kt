package io.github.maxlyth.hapaneld.device.profile

import java.net.URI

internal object ProfileValidator {
    private val idPattern = Regex("^[a-z0-9](?:[a-z0-9.-]{0,126}[a-z0-9])?$")
    private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$")
    private val versionPattern = Regex("^[0-9]{1,6}(?:\\.[0-9]{1,6}){0,3}(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
    private val contentVersionPattern = Regex("^(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
    private val licensePattern = Regex("^[A-Za-z0-9][A-Za-z0-9.+-]{0,63}(?: (?:AND|OR) [A-Za-z0-9][A-Za-z0-9.+-]{0,63})*$")
    private val eventTypePattern = Regex("^KEYCODE_[A-Z0-9_]+$")

    fun validate(document: ProfileDocument, coreVersion: String, bundled: Boolean): List<ProfileIssue> {
        val issues = mutableListOf<ProfileIssue>()
        fun reject(path: String, message: String) { issues += ProfileIssue(ProfileIssueSeverity.ERROR, path, message) }
        fun warn(path: String, message: String) { issues += ProfileIssue(ProfileIssueSeverity.WARNING, path, message) }
        fun boundedText(value: String?, path: String, max: Int = 100) {
            if (value != null && (value.isBlank() || value.length > max || value.any { it.code < 0x20 || it.code == 0x7f })) {
                reject(path, "Must contain 1-$max characters without control characters.")
            }
        }
        if (document.schema != ProfileMetadata.SCHEMA) reject("schema", "Unsupported schema ${document.schema}; expected ${ProfileMetadata.SCHEMA}.")
        if (!idPattern.matches(document.id) || ".." in document.id) reject("id", "Use 1-128 lowercase letters or digits with interior dots and hyphens; consecutive dots are not allowed.")
        if (!contentVersionPattern.matches(document.version)) reject("version", "Expected semantic version MAJOR.MINOR.PATCH.")
        if (document.displayName.isBlank() || document.displayName.length > 100) reject("display_name", "Must contain 1-100 characters.")
        if (document.socClass.isBlank() || document.socClass.length > 100) reject("soc_class", "Must contain 1-100 characters.")
        if (document.metadata.author.isBlank() || document.metadata.author.length > 100) reject("metadata.author", "Must contain 1-100 characters.")
        document.metadata.source?.let { source ->
            val uri = runCatching { URI(source) }.getOrNull()
            if (source.length > 500 || uri?.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) reject("metadata.source", "Must be an absolute HTTPS URL without user information, at most 500 characters.")
        }
        if (!licensePattern.matches(document.metadata.license)) reject("metadata.license", "Expected a bounded SPDX-style license expression.")
        if (document.metadata.testedFirmware.size > 32 || document.metadata.testedFirmware.any { it.isBlank() || it.length > 120 }) reject("metadata.tested_firmware", "Use at most 32 non-empty entries of at most 120 characters.")
        if (document.metadata.limitations.size > 32 || document.metadata.limitations.any { it.isBlank() || it.length > 500 }) reject("metadata.limitations", "Use at most 32 non-empty entries of at most 500 characters.")
        if (document.match.priority !in 0..1000) reject("match.priority", "Must be between 0 and 1000.")
        if (document.match.fallback && (!bundled || document.id != "generic")) reject("match.fallback", "Only the bundled generic profile may be a fallback.")
        if (!document.match.fallback && document.match.any.isEmpty()) reject("match.any", "At least one match group is required.")
        if (document.match.any.size > 64) reject("match.any", "At most 64 match groups are allowed.")
        document.match.any.forEachIndexed { groupIndex, group ->
            if (group.priority !in 0..1000) reject("match.any[$groupIndex].priority", "Must be between 0 and 1000.")
            if (group.all.isEmpty()) reject("match.any[$groupIndex].all", "A group must contain at least one predicate.")
            if (group.all.size > 8) reject("match.any[$groupIndex].all", "At most 8 predicates are allowed per group.")
            group.all.forEachIndexed { predicateIndex, predicate ->
                val path = "match.any[$groupIndex].all[$predicateIndex].values"
                if (predicate.values.isEmpty() || predicate.values.size > 32) reject(path, "Provide 1-32 values.")
                predicate.values.forEach {
                    if (it.isBlank() || it.length > 100 || it != it.lowercase() || it.any { char -> char.code < 0x20 || char.code == 0x7f }) reject(path, "Values must be non-empty lowercase strings without controls, at most 100 characters.")
                }
            }
        }
        document.requires.minCoreVersion?.let {
            if (it.length > 64 || !versionPattern.matches(it)) reject("requires.min_core_version", "Expected a bounded dotted release version.")
            else if (compareVersions(coreVersion, it) < 0) reject("requires.min_core_version", "Requires ha-paneld $it or newer; this core is $coreVersion.")
        }
        val knownDrivers = ProfileMetadata.drivers.mapTo(mutableSetOf()) { it.id }
        document.requires.drivers.forEach {
            if (it !in knownDrivers) reject("requires.drivers", "Unknown core driver '$it'.")
        }
        if (document.platform.suForm !in setOf("none", "android", "toolbox")) reject("platform.su_form", "Unknown su form '${document.platform.suForm}'.")
        if (document.platform.suForm == "none" && document.platform.appCanSu) reject("platform.app_can_su", "Cannot be true when su_form is none.")
        if (document.hardware.led.mechanism !in setOf("none", "autodetect", "rk3576-ioctl", "rk3576-ioctl-daemon", "sysfs-daemon")) {
            reject("hardware.led.mechanism", "Unknown LED mechanism '${document.hardware.led.mechanism}'.")
        }
        if (document.hardware.led.transfer !in setOf("identity", "rk3576-four-bit")) reject("hardware.led.transfer", "Unknown core transfer '${document.hardware.led.transfer}'.")
        if (document.hardware.screenOff !in setOf("brightness-zero", "su-blpower", "daemon-blpower")) reject("hardware.screen_off", "Unknown screen-off route '${document.hardware.screenOff}'.")
        if (document.hardware.screenOff == "su-blpower" && !document.platform.appCanSu) reject("hardware.screen_off", "su-blpower requires app_can_su: true.")
        if (document.hardware.screenOff == "daemon-blpower" && document.platform.appCanSu) reject("hardware.screen_off", "daemon-blpower is reserved for sandbox-walled profiles.")
        if (document.hardware.led.mechanism in setOf("rk3576-ioctl-daemon", "sysfs-daemon") && document.platform.appCanSu) reject("hardware.led.mechanism", "Daemon-only LED routes are reserved for sandbox-walled profiles.")
        validateAllowedPath(
            document.hardware.zigbeeGatewayDir,
            "hardware.zigbee_gateway_dir",
            setOf("/vendor/bin/siliconlabs_host"),
            issues,
        )
        val relayClasses = setOf("/sys/class/relay", "/sys/class/st_relay", "/sys/class/strelay")
        validateAllowedPath(document.hardware.relayBase, "hardware.relay_base", relayClasses, issues)
        document.hardware.relayBaseFallbacks.forEachIndexed { index, path ->
            validateAllowedPath(path, "hardware.relay_base_fallbacks[$index]", relayClasses, issues)
        }
        if (document.hardware.relayBaseFallbacks.size > 3) reject("hardware.relay_base_fallbacks", "At most 3 fallback classes are allowed.")
        if (document.hardware.relayBaseFallbacks.size != document.hardware.relayBaseFallbacks.toSet().size || document.hardware.relayBase in document.hardware.relayBaseFallbacks) {
            reject("hardware.relay_base_fallbacks", "Relay class paths must be unique.")
        }
        document.hardware.buttonLedGpioBase?.let { if (it !in 0..4092) reject("hardware.button_led_gpio_base", "GPIO block base must be between 0 and 4092.") }
        document.sensors.proximityGpio?.let {
            if (it !in 0..4095) reject("sensors.proximity_gpio", "GPIO must be between 0 and 4095.")
            if (!document.platform.appCanSu) reject("sensors.proximity_gpio", "GPIO proximity polling requires app_can_su: true.")
        }
        if ((document.sensors.proximityNearRaw == null) != (document.sensors.proximityFarRaw == null)) reject("sensors", "Near and far reference readings must be supplied together.")
        if (document.sensors.roomTempOffsetC !in -30f..30f) reject("sensors.room_temp_offset_c", "Offset must be between -30 and 30 °C.")
        if (document.sensors.proximityGradedStrategy !in setOf("observed", "nspanel-firmware-cutover")) reject("sensors.proximity_graded_strategy", "Unknown core strategy.")
        boundedText(document.sensors.proximityTechnology, "sensors.proximity_technology")
        boundedText(document.sensors.lightTechnology, "sensors.light_technology")
        if (document.identity.modelLabelStrategy !in setOf("display-name", "nspanel-product-version")) reject("identity.model_label_strategy", "Unknown core strategy.")
        boundedText(document.identity.manufacturer, "identity.manufacturer")
        boundedText(document.identity.model, "identity.model")
        when (val density = document.display.recommendedDensity) {
            is ProfileDensity.Fixed -> if (density.value !in 80..640) reject("display.recommended_density", "Density must be between 80 and 640 dpi.")
            is ProfileDensity.Strategy -> if (density.id != "nspanel-variant") reject("display.recommended_density", "Unknown core strategy '${density.id}'.")
            null -> Unit
        }
        document.display.recommendedFontScale?.let { if (it !in 0.5f..1.5f) reject("display.recommended_font_scale", "Font scale must be between 0.5 and 1.5.") }
        document.display.physicalPpi?.let { if (it !in 50..1000) reject("display.physical_ppi", "Physical PPI must be between 50 and 1000.") }
        if (document.input.evdevButtons.size > 32) reject("input.evdev_buttons", "At most 32 evdev mappings are allowed.")
        val evdevCodes = mutableSetOf<Pair<Boolean, Int>>()
        document.input.evdevButtons.forEachIndexed { index, button ->
            val path = "input.evdev_buttons[$index]"
            if (!Regex("^/dev/input/event[0-9]{1,3}$").matches(button.node)) {
                reject("$path.node", "Only /dev/input/eventN device nodes are supported.")
            }
            if (button.code !in 1..767) reject("$path.code", "Linux input code must be between 1 and 767.")
            if (button.eventType.length > 64 || !eventTypePattern.matches(button.eventType)) reject("$path.event_type", "Use KEYCODE_ plus uppercase letters, digits, or underscores, at most 64 characters.")
            if (!evdevCodes.add(button.sw to button.code)) reject(path, "Duplicate (sw, code) mapping.")
        }
        document.cpu.governors?.forEach { (tier, governor) ->
            if (tier !in setOf("Performance", "Efficiency", "Auto")) reject("cpu.governors.$tier", "Unknown HA CPU tier.")
            if (!Regex("^[a-z][a-z0-9_-]{0,31}$").matches(governor)) reject("cpu.governors.$tier", "Invalid Linux governor name.")
        }
        document.updates.webViewArtifact?.let { artifact ->
            if (artifact !in ProfileArtifacts.webViews) reject("updates.webview_artifact", "Unknown core-owned WebView artifact '$artifact'.")
        }
        document.updates.companionMaxVersion?.let { version ->
            if (version.length > 64 || !versionPattern.matches(version)) reject("updates.companion_max_version", "Expected a bounded dotted release version.")
        }
        if (document.taming.size > 128) reject("taming", "At most 128 package suggestions are allowed.")
        val seenPackages = mutableSetOf<String>()
        document.taming.forEachIndexed { index, candidate ->
            val path = "taming[$index]"
            if (!packagePattern.matches(candidate.packageName)) reject("$path.package", "Invalid Android package name.")
            if (!seenPackages.add(candidate.packageName)) reject("$path.package", "Duplicate package suggestion.")
            if (candidate.tags.size > 8 || candidate.tags.any { !Regex("^[a-z][a-z0-9-]{0,23}$").matches(it) }) reject("$path.tags", "Use at most 8 lowercase tags of at most 24 characters.")
            if (candidate.note.length > 500) reject("$path.note", "Note must not exceed 500 characters.")
        }
        expectedDrivers(document).forEach { driver ->
            if (driver !in document.requires.drivers) reject("requires.drivers", "Capability requires core driver '$driver'.")
        }
        document.requires.drivers.filterNot { it in expectedDrivers(document) }.forEach {
            warn("requires.drivers", "Driver '$it' is declared but no field currently uses it.")
        }
        return issues
    }

    fun risks(document: ProfileDocument, overridesBundled: Boolean): Set<ProfileRisk> = buildSet {
        if (document.platform.appCanSu || document.hardware.screenOff != "brightness-zero" || document.requires.drivers.any { ProfileMetadata.drivers.find { d -> d.id == it }?.privileged == true }) add(ProfileRisk.ROOT_PATHS)
        if (document.hardware.relayBase != null || document.hardware.relayBaseFallbacks.isNotEmpty() || document.hardware.buttonLedGpioBase != null || document.sensors.proximityGpio != null) add(ProfileRisk.RELAY_OR_GPIO_WRITES)
        if (document.input.evdevButtons.isNotEmpty()) add(ProfileRisk.EVDEV_READ)
        if (document.input.evdevButtons.any { it.grab }) add(ProfileRisk.EVDEV_GRAB)
        if (document.taming.any { it.defaultTame }) add(ProfileRisk.DEFAULT_TAMING)
        if (document.updates.webViewArtifact != null) add(ProfileRisk.WEBVIEW_INSTALL)
        if (overridesBundled) add(ProfileRisk.OVERRIDES_BUNDLED)
    }

    private fun expectedDrivers(document: ProfileDocument): Set<String> = buildSet {
        when (document.platform.suForm) {
            "android" -> if (document.platform.appCanSu) add("access.android-su")
            "toolbox" -> if (document.platform.appCanSu) add("access.toolbox-su")
        }
        if (document.hardware.led.mechanism != "none") add("led.${document.hardware.led.mechanism}")
        add("screen.${document.hardware.screenOff}")
        if (document.hardware.zigbeeGatewayDir != null) add("radio.siliconlabs-host")
        if (document.hardware.relayBase != null || document.hardware.relayBaseFallbacks.isNotEmpty()) add("relay.sysfs")
        if (document.hardware.buttonLedGpioBase != null) add("relay.gpio-button-led")
        if (document.hardware.hasButtonBacklight) add("input.button-backlight")
        if (document.sensors.proximityTechnology != null || document.sensors.lightTechnology != null) add("sensor.android")
        if (document.sensors.proximityGpio != null) add("sensor.gpio-proximity")
        if (document.sensors.cht8305) add("sensor.cht8305-daemon")
        if (document.input.evdevButtons.isNotEmpty()) add("input.evdev")
        if (document.updates.webViewArtifact != null) add("update.webview")
    }

    private fun validateAllowedPath(
        value: String?,
        path: String,
        allowed: Set<String>,
        issues: MutableList<ProfileIssue>,
    ) {
        if (value == null) return
        if (value !in allowed) {
            issues += ProfileIssue(
                ProfileIssueSeverity.ERROR,
                path,
                "Unsupported privileged path; allowed values: ${allowed.sorted().joinToString()}.",
            )
        }
    }

    internal fun compareVersions(left: String, right: String): Int {
        val a = ParsedVersion.parse(left)
        val b = ParsedVersion.parse(right)
        if (a == null || b == null) return if (left == right) 0 else left.compareTo(right)
        for (index in 0 until maxOf(a.release.size, b.release.size)) {
            val compared = (a.release.getOrNull(index) ?: 0).compareTo(b.release.getOrNull(index) ?: 0)
            if (compared != 0) return compared
        }
        if (a.preRelease == null && b.preRelease == null) return 0
        if (a.preRelease == null) return 1
        if (b.preRelease == null) return -1
        for (index in 0 until maxOf(a.preRelease.size, b.preRelease.size)) {
            val x = a.preRelease.getOrNull(index) ?: return -1
            val y = b.preRelease.getOrNull(index) ?: return 1
            val xNumber = x.toIntOrNull()
            val yNumber = y.toIntOrNull()
            val compared = when {
                xNumber != null && yNumber != null -> xNumber.compareTo(yNumber)
                xNumber != null -> -1
                yNumber != null -> 1
                else -> comparePreReleaseIdentifier(x, y)
            }
            if (compared != 0) return compared
        }
        return 0
    }

    private fun comparePreReleaseIdentifier(left: String, right: String): Int {
        val token = Regex("[A-Za-z-]+|[0-9]+")
        val a = token.findAll(left).map { it.value }.toList()
        val b = token.findAll(right).map { it.value }.toList()
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(index) ?: return -1
            val y = b.getOrNull(index) ?: return 1
            val xNumeric = x.all(Char::isDigit)
            val yNumeric = y.all(Char::isDigit)
            val compared = when {
                xNumeric && yNumeric -> compareNumericText(x, y)
                xNumeric -> -1
                yNumeric -> 1
                else -> x.compareTo(y)
            }
            if (compared != 0) return compared
        }
        return left.compareTo(right)
    }

    private fun compareNumericText(left: String, right: String): Int {
        val a = left.trimStart('0').ifEmpty { "0" }
        val b = right.trimStart('0').ifEmpty { "0" }
        return a.length.compareTo(b.length).takeIf { it != 0 } ?: a.compareTo(b)
    }

    private data class ParsedVersion(val release: List<Int>, val preRelease: List<String>?) {
        companion object {
            fun parse(value: String): ParsedVersion? {
                if (!versionPattern.matches(value)) return null
                val release = value.substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }
                val suffix = value.substringAfter('-', "").takeIf { '-' in value }?.split('.')
                return ParsedVersion(release, suffix)
            }
        }
    }
}
