package io.github.maxlyth.hapaneld

import android.os.Build
import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import io.github.maxlyth.hapaneld.control.BrightnessController
import io.github.maxlyth.hapaneld.control.NavigateController
import io.github.maxlyth.hapaneld.control.ScreenController
import io.github.maxlyth.hapaneld.control.SystemController
import io.github.maxlyth.hapaneld.control.VolumeController
import io.github.maxlyth.hapaneld.hardware.LedController
import io.github.maxlyth.hapaneld.input.ButtonBus
import io.github.maxlyth.hapaneld.input.PanelAccessibilityService
import io.github.maxlyth.hapaneld.util.HelperClient
import org.json.JSONObject
import java.net.URI

/**
 * MQTT bridge — the single uniform control API across the fleet. Publishes Home Assistant
 * MQTT-discovery configs so every panel exposes identical entities (the per-hardware HAL is hidden
 * behind them), subscribes to the command topics, and dispatches to the controllers. Best-effort:
 * disabled silently when no broker is configured (the HTTP /play surface works standalone).
 *
 * Entities published (per panel):
 * - `light.<panel>_screen`  — brightness + on/off (on=wake, off=sleep). HA-driven; no on-device loop.
 * - `light.<panel>_led`     — RGB (only if [led].available()).
 * - `text.<panel>_navigate` — URL navigate.
 * - `event.<panel>_button`  — hardware button events (only if [buttonsEnabled]).
 * - `media_player.<panel>_paneld` — TTS/announce (HTTP /play does the work).
 */
class MqttBridge(
    private val config: Config,
    private val brightness: BrightnessController,
    private val screen: ScreenController,
    private val led: LedController,
    private val navigate: NavigateController,
    private val volume: VolumeController,
    private val system: SystemController,
    private val buttonsEnabled: Boolean,
    private val hasLight: Boolean,
    private val hasProximity: Boolean,
    private val hasTemperature: Boolean,
    private val hasHumidity: Boolean,
    private val hasButtonBacklight: Boolean,
    private val configUrl: String? = null,
    // Resolves HA's LAN IP via mDNS to default the broker when none is configured (injected by the
    // service, wired to MdnsAdvertiser). Returns null if HA isn't found / mDNS unavailable.
    private val discoverHaIp: () -> String? = { null },
) {
    private var client: Mqtt5AsyncClient? = null

    /** Broker actually in use — configured, or auto-discovered as `tcp://<ha-ip>:1883`; "" if none. */
    var activeBroker: String = ""
        private set

    /** Live connection state for the UI, so an auth failure reads differently from "unreachable":
     *  connected | auth-failed | unreachable | connecting | disabled. */
    @Volatile var state: String = "disabled"
        private set

    fun isConnected(): Boolean = state == "connected"

    private val panel = config.panelId
    private val availabilityTopic = "ha-paneld/$panel/availability"
    private val cmdScreen = "ha-paneld/$panel/screen/set"
    private val cmdLed = "ha-paneld/$panel/led/set"
    private val cmdNavigate = "ha-paneld/$panel/navigate/set"
    private val cmdVolume = "ha-paneld/$panel/volume/set"
    private val cmdReload = "ha-paneld/$panel/reload/set"
    private val cmdReboot = "ha-paneld/$panel/reboot/set"
    private val cmdLauncher = "ha-paneld/$panel/launcher/set"
    private val cmdHome = "ha-paneld/$panel/home/set"
    private val cmdButtons = "ha-paneld/$panel/buttons/set"
    private val stateButtons = "ha-paneld/$panel/buttons/state"
    private val cmdBack = "ha-paneld/$panel/back/set"
    private val cmdRecents = "ha-paneld/$panel/recents/set"
    private val stateScreen = "ha-paneld/$panel/screen/state"
    private val stateLed = "ha-paneld/$panel/led/state"
    private val stateNavigate = "ha-paneld/$panel/navigate/state"
    private val stateVolume = "ha-paneld/$panel/volume/state"
    private val eventButton = "ha-paneld/$panel/button/event"
    private val stateIlluminance = "ha-paneld/$panel/illuminance/state"
    private val stateProximity = "ha-paneld/$panel/proximity/state"
    private val stateTemperature = "ha-paneld/$panel/temperature/state"
    private val stateHumidity = "ha-paneld/$panel/humidity/state"

    fun start() {
        var broker = config.mqttBroker.trim()
        if (broker.isEmpty()) {
            // No explicit broker — try to find HA on the LAN (mDNS) and default to its :1883.
            discoverHaIp()?.let {
                broker = "tcp://$it:1883"
                Log.i(TAG, "MQTT broker auto-discovered via mDNS (HA at $it): $broker")
            }
        }
        if (broker.isEmpty()) {
            Log.i(TAG, "no broker configured and none discovered — MQTT disabled")
            activeBroker = ""
            state = "disabled"
            return
        }
        activeBroker = broker
        state = "connecting"
        try {
            val uri = URI(if (broker.contains("://")) broker else "tcp://$broker")
            val host = uri.host ?: return
            val port = if (uri.port > 0) uri.port else 1883

            val c = MqttClient.builder()
                .useMqttVersion5()
                .identifier("ha-paneld-$panel")
                .serverHost(host)
                .serverPort(port)
                // Auto-reconnect so a network blip / broker restart never permanently orphans the
                // panel. Re-subscribe + re-publish discovery happen in onConnected on every connect.
                .automaticReconnectWithDefaultConfig()
                .addConnectedListener { onConnected() }
                .addDisconnectedListener {
                    // Classify so the UI can say "auth rejected" vs "unreachable" rather than just "down".
                    val m = (it.cause?.message ?: it.cause?.toString() ?: "").uppercase()
                    state = when {
                        Regex("NOT_AUTHORIZED|BAD_USER_NAME|PASSWORD|AUTHENTICAT|BANNED").containsMatchIn(m) -> "auth-failed"
                        Regex("REFUSED|TIMEOUT|UNREACHABLE|UNRESOLVED|RESET|NO ROUTE|CONNECTION|FAILED").containsMatchIn(m) -> "unreachable"
                        else -> "disconnected"
                    }
                    Log.w(TAG, "MQTT disconnected ($state) — auto-reconnecting: ${it.cause?.message}")
                }
                .buildAsync()
            client = c
            ButtonBus.listener = { event -> publishButton(event) }

            val connect = c.connectWith().willPublish()
                .topic(availabilityTopic)
                .payload("offline".toByteArray())
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(true)
                .applyWillPublish()
            if (config.mqttUser.isNotEmpty()) {
                connect.simpleAuth()
                    .username(config.mqttUser)
                    .password(config.mqttPassword.toByteArray())
                    .applySimpleAuth()
            }
            connect.send() // async; onConnected() does subscribe + discovery on success
            Log.i(TAG, "MQTT connecting to $host:$port for $panel")
        } catch (e: Exception) {
            Log.w(TAG, "MQTT connect failed", e)
        }
    }

    /** Runs on every (re)connect: (re)subscribe to commands and (re)publish discovery + online. */
    private fun onConnected() {
        val c = client ?: return
        state = "connected"
        c.subscribeWith()
            .topicFilter("ha-paneld/$panel/+/set")
            .qos(MqttQos.AT_LEAST_ONCE)
            .callback { publish -> onCommand(publish) }
            .send()
        publishDiscovery(c)
        publish(c, availabilityTopic, "online", retain = true)
        restoreAndPublishStates(c)
        Log.i(TAG, "MQTT connected — (re)subscribed + discovery for $panel")
    }

    /**
     * Sync HA to the panel's actual state on (re)connect, so the UI isn't stale after a reboot
     * (hardware resets, but HA holds the last retained state). Re-applies the last LED colour and
     * publishes the current screen/volume/navigate states.
     */
    private fun restoreAndPublishStates(c: Mqtt5AsyncClient) {
        publish(c, stateVolume, volume.getPercent().toString(), retain = true)
        // Screen: panels come up on; report ON + the current brightness.
        publish(c, stateScreen, """{"state":"ON","brightness":${brightness.getBrightness().coerceAtLeast(1)}}""", retain = true)
        // Navigate: last pushed URL (skip when empty — empty retained payload just clears the topic).
        if (config.lastNavigate.isNotEmpty()) publish(c, stateNavigate, config.lastNavigate, retain = true)
        // LED: re-apply the last colour to the hardware (reset on reboot) and publish it.
        val led = config.lastLed.split(",").mapNotNull { it.toIntOrNull() }
        if (led.size == 5 && led[0] == 1) {
            val (_, br, r, g, b) = led
            this.led.setRgb(r * br / 255, g * br / 255, b * br / 255)
            publish(c, stateLed, ledStateJson(br, r, g, b), retain = true)
        } else {
            // Force the hardware off too — the LED can power up to a default on reboot, so publishing
            // OFF without driving it leaves HA and the physical LED disagreeing (office-dash rk3576).
            this.led.off()
            publish(c, stateLed, """{"state":"OFF"}""", retain = true)
        }
    }

    // ---- command dispatch ----

    private fun onCommand(publish: Mqtt5Publish) {
        val topic = publish.topic.toString()
        val payload = String(publish.payloadAsBytes)
        try {
            when (topic) {
                cmdScreen -> handleScreen(payload)
                cmdLed -> handleLed(payload)
                cmdNavigate -> handleNavigate(payload)
                cmdVolume -> handleVolume(payload)
                cmdReload -> system.reloadDashboard(config.dashboardPackage)
                cmdReboot -> system.reboot()
                cmdLauncher -> system.launchLauncher(config.launcherPackage)
                cmdHome -> system.launchHome(config.dashboardPackage)
                cmdButtons -> handleButtons(payload)
                cmdBack -> PanelAccessibilityService.navBack()
                cmdRecents -> PanelAccessibilityService.navRecents()
                else -> Log.d(TAG, "unhandled command topic $topic")
            }
        } catch (e: Exception) {
            Log.w(TAG, "command failed on $topic: $payload", e)
        }
    }

    private fun handleScreen(payload: String) {
        val json = JSONObject(payload)
        val on = json.optString("state", "ON").equals("ON", ignoreCase = true)
        if (!on) {
            screen.sleep()
            publish(client!!, stateScreen, """{"state":"OFF"}""", retain = true)
            return
        }
        screen.wake() // power the backlight on (daemon bl_power) or restore brightness (fallback)
        val level = if (json.has("brightness")) {
            json.getInt("brightness").also { brightness.setBrightness(it) }
        } else {
            brightness.getBrightness().coerceAtLeast(1)
        }
        screen.noteLevel(level)
        publish(client!!, stateScreen, """{"state":"ON","brightness":$level}""", retain = true)
    }

    private fun handleLed(payload: String) {
        val json = JSONObject(payload)
        val on = json.optString("state", "ON").equals("ON", ignoreCase = true)
        if (!on) {
            led.off()
            config.lastLed = "0,0,0,0,0"
            publish(client!!, stateLed, """{"state":"OFF"}""", retain = true)
            return
        }
        val br = if (json.has("brightness")) json.getInt("brightness") else 255
        val color = json.optJSONObject("color")
        var r = color?.optInt("r", 255) ?: 255
        var g = color?.optInt("g", 255) ?: 255
        var b = color?.optInt("b", 255) ?: 255
        val cr = color?.optInt("r", 255) ?: 255
        val cg = color?.optInt("g", 255) ?: 255
        val cb = color?.optInt("b", 255) ?: 255
        // Apply HA brightness as a scalar over the colour (json light sends them separately).
        led.setRgb(cr * br / 255, cg * br / 255, cb * br / 255)
        config.lastLed = "1,$br,$cr,$cg,$cb" // remember for restore on reboot
        publish(client!!, stateLed, ledStateJson(br, cr, cg, cb), retain = true)
    }

    private fun ledStateJson(br: Int, r: Int, g: Int, b: Int) =
        """{"state":"ON","color_mode":"rgb","brightness":$br,"color":{"r":$r,"g":$g,"b":$b}}"""

    // Button backlight (e.g. TPA10): a brightness-only light, driven via the root daemon's BTN command
    // (same daemon that owns the sysfs LED). Daemon calls are short blocking I/O — fine on this thread.
    private fun handleButtons(payload: String) {
        val json = JSONObject(payload)
        val on = json.optString("state", "ON").equals("ON", ignoreCase = true)
        val level = if (!on) 0 else if (json.has("brightness")) json.getInt("brightness") else 255
        HelperClient.send("BTN $level")
        publish(
            client!!, stateButtons,
            if (on) """{"state":"ON","brightness":$level}""" else """{"state":"OFF"}""",
            retain = true,
        )
    }

    private fun handleNavigate(payload: String) {
        // text entity sends the raw URL string.
        val url = payload.trim().trim('"')
        if (url.isNotEmpty()) {
            navigate.navigate(url)
            config.lastNavigate = url
            publish(client!!, stateNavigate, url, retain = true)
        }
    }

    private fun handleVolume(payload: String) {
        // number entity sends a plain numeric string (0..100).
        val pct = payload.trim().trim('"').toDoubleOrNull()?.toInt() ?: return
        volume.setPercent(pct)
        publish(client!!, stateVolume, volume.getPercent().toString(), retain = true)
    }

    private fun publishButton(event: String) {
        client?.let { publish(it, eventButton, """{"event_type":"$event"}""") }
    }

    fun publishLight(lux: Int) {
        client?.let { publish(it, stateIlluminance, lux.toString(), retain = true) }
    }

    fun publishProximity(near: Boolean) {
        client?.let { publish(it, stateProximity, if (near) "ON" else "OFF", retain = true) }
    }

    // Rounded at publish (1dp temp, integer humidity) so precision wobble can't create recorder rows.
    fun publishTemperature(celsius: Float) {
        client?.let { publish(it, stateTemperature, String.format(java.util.Locale.US, "%.1f", celsius), retain = true) }
    }

    fun publishHumidity(percent: Float) {
        client?.let { publish(it, stateHumidity, Math.round(percent).toString(), retain = true) }
    }

    // ---- discovery ----

    private fun publishDiscovery(c: Mqtt5AsyncClient) {
        // device.name = the configurable friendly name; entity names are the capability ONLY, so HA
        // composes a clean `<domain>.<panel>_<cap>` entity_id without doubling the panel id.
        // configuration_url -> HA renders a "Visit" link on the device page (the panel's info UI).
        val cu = if (!configUrl.isNullOrBlank()) ""","configuration_url":"$configUrl"""" else ""
        val name = jsonEsc(config.friendlyName)
        val mfr = jsonEsc(config.manufacturer)
        val mdl = jsonEsc(config.model)
        // hw_version = panel firmware/build; surfaces in HA's device-info section (sw_version is
        // ha-paneld's own version). serial_number = stable Android id.
        val hw = jsonEsc("Android ${Build.VERSION.RELEASE} · ${Build.DISPLAY}")
        val device = """"device":{"identifiers":["ha-paneld-$panel"],"name":"$name","manufacturer":"$mfr","model":"$mdl","sw_version":"${Config.VERSION}","hw_version":"$hw","serial_number":"${config.androidId}"$cu}"""
        val avail = """"availability_topic":"$availabilityTopic","payload_available":"online","payload_not_available":"offline""""

        publishConfig(
            c, "light", "${panel}_screen",
            """{"name":"Screen","unique_id":"${panel}_screen","schema":"json","brightness":true,"supported_color_modes":["brightness"],"command_topic":"$cmdScreen","state_topic":"$stateScreen",$avail,$device}""",
        )

        if (led.available()) {
            val modes = if (led.colorCapable()) """["rgb"]""" else """["brightness"]"""
            publishConfig(
                c, "light", "${panel}_led",
                """{"name":"LED","unique_id":"${panel}_led","schema":"json","brightness":true,"supported_color_modes":$modes,"command_topic":"$cmdLed","state_topic":"$stateLed",$avail,$device}""",
            )
        }

        publishConfig(
            c, "text", "${panel}_navigate",
            """{"name":"Navigate","unique_id":"${panel}_navigate","command_topic":"$cmdNavigate","state_topic":"$stateNavigate","mode":"text",$avail,$device}""",
        )

        if (buttonsEnabled) {
            publishConfig(
                c, "event", "${panel}_button",
                """{"name":"Button","unique_id":"${panel}_button","state_topic":"$eventButton","event_types":["KEYCODE_MUTE","KEYCODE_F","KEYCODE_BACK","KEYCODE_HOME","KEYCODE_DPAD_CENTER","KEYCODE_VOLUME_UP","KEYCODE_VOLUME_DOWN"],$avail,$device}""",
            )
            // Nav actions via the a11y service (performGlobalAction) — uniform on every panel, no root.
            publishConfig(
                c, "button", "${panel}_back",
                """{"name":"Back","unique_id":"${panel}_back","command_topic":"$cmdBack","icon":"mdi:arrow-left",$avail,$device}""",
            )
            publishConfig(
                c, "button", "${panel}_recents",
                """{"name":"Recents","unique_id":"${panel}_recents","command_topic":"$cmdRecents","icon":"mdi:view-agenda",$avail,$device}""",
            )
        }

        // TTS/announce playback volume (STREAM_MUSIC). HA has no MQTT media_player platform, so
        // volume is a number entity rather than a media_player slider.
        publishConfig(
            c, "number", "${panel}_volume",
            """{"name":"Volume","unique_id":"${panel}_volume","command_topic":"$cmdVolume","state_topic":"$stateVolume","min":0,"max":100,"step":1,"mode":"slider","unit_of_measurement":"%","icon":"mdi:volume-high",$avail,$device}""",
        )

        // Panel sensors — exposed as data only; room sensors stay the occupancy/lux authority.
        if (hasLight) {
            publishConfig(
                c, "sensor", "${panel}_illuminance",
                """{"name":"Illuminance","unique_id":"${panel}_illuminance","state_topic":"$stateIlluminance","device_class":"illuminance","unit_of_measurement":"lx","state_class":"measurement",$avail,$device}""",
            )
        }
        if (hasProximity) {
            publishConfig(
                c, "binary_sensor", "${panel}_proximity",
                """{"name":"Proximity","unique_id":"${panel}_proximity","state_topic":"$stateProximity","device_class":"occupancy","payload_on":"ON","payload_off":"OFF",$avail,$device}""",
            )
        }
        if (hasTemperature) {
            publishConfig(
                c, "sensor", "${panel}_temperature",
                """{"name":"Temperature","unique_id":"${panel}_temperature","state_topic":"$stateTemperature","device_class":"temperature","unit_of_measurement":"°C","state_class":"measurement",$avail,$device}""",
            )
        }
        if (hasHumidity) {
            publishConfig(
                c, "sensor", "${panel}_humidity",
                """{"name":"Humidity","unique_id":"${panel}_humidity","state_topic":"$stateHumidity","device_class":"humidity","unit_of_measurement":"%","state_class":"measurement",$avail,$device}""",
            )
        }
        if (hasButtonBacklight) {
            publishConfig(
                c, "light", "${panel}_buttons",
                """{"name":"Button backlight","unique_id":"${panel}_buttons","schema":"json","brightness":true,"supported_color_modes":["brightness"],"command_topic":"$cmdButtons","state_topic":"$stateButtons","icon":"mdi:gesture-tap-button",$avail,$device}""",
            )
        }

        // Panel actions (root via su; graceful no-op without it).
        publishConfig(
            c, "button", "${panel}_reload",
            """{"name":"Reload dashboard","unique_id":"${panel}_reload","command_topic":"$cmdReload","icon":"mdi:web-refresh",$avail,$device}""",
        )
        publishConfig(
            c, "button", "${panel}_reboot",
            """{"name":"Reboot","unique_id":"${panel}_reboot","command_topic":"$cmdReboot","device_class":"restart","icon":"mdi:restart",$avail,$device}""",
        )
        publishConfig(
            c, "button", "${panel}_launcher",
            """{"name":"Launcher","unique_id":"${panel}_launcher","command_topic":"$cmdLauncher","icon":"mdi:apps",$avail,$device}""",
        )
        publishConfig(
            c, "button", "${panel}_home",
            """{"name":"Home Assistant","unique_id":"${panel}_home","command_topic":"$cmdHome","icon":"mdi:home-assistant",$avail,$device}""",
        )
    }

    private fun jsonEsc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun publishConfig(c: Mqtt5AsyncClient, component: String, objectId: String, payload: String) {
        publish(c, "homeassistant/$component/$objectId/config", payload, retain = true)
    }

    /**
     * Clear this panel's retained discovery (empty payload per topic) so a panel_id change doesn't
     * leave an orphan device in HA. Covers every entity we may have published for the current id.
     */
    fun clearDiscovery() {
        val c = client ?: return
        val entities = listOf(
            "light" to "${panel}_screen", "light" to "${panel}_led",
            "text" to "${panel}_navigate", "event" to "${panel}_button",
            "button" to "${panel}_back", "button" to "${panel}_recents",
            "number" to "${panel}_volume", "sensor" to "${panel}_illuminance",
            "binary_sensor" to "${panel}_proximity",
            "sensor" to "${panel}_temperature", "sensor" to "${panel}_humidity",
            "light" to "${panel}_buttons",
            "button" to "${panel}_reload", "button" to "${panel}_reboot",
            "button" to "${panel}_launcher", "button" to "${panel}_home",
        )
        entities.forEach { (comp, obj) -> publish(c, "homeassistant/$comp/$obj/config", "", retain = true) }
    }

    private fun publish(c: Mqtt5AsyncClient, topic: String, payload: String, retain: Boolean = false) {
        c.publishWith()
            .topic(topic)
            .payload(payload.toByteArray())
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(retain)
            .send()
    }

    fun stop() {
        ButtonBus.listener = null
        runCatching {
            client?.let {
                publish(it, availabilityTopic, "offline", retain = true)
                it.disconnect()
            }
        }
        client = null
    }

    companion object {
        private const val TAG = "ha-paneld/mqtt"
    }
}
