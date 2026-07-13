# Device-profile architecture

Each supported panel has **one canonical silo** — a `DeviceProfile` — that declares everything ha-paneld does specially for that hardware. Generic functional modules read the active profile instead of hard-coding paths and quirks, so "what does ha-paneld do for the TPA10?" has a single answer and onboarding a new panel has one profile entry point.

## The profile

`DeviceProfile` is one canonical silo per platform. It declares the device-specific paths, mechanisms and quirks; generic modules consume it rather than hard-coding them.

```kotlin
interface DeviceProfile {
  val id: String                 // stable profile key
  val socClass: String           // human-readable SoC/family
  val suForm: SuForm             // TOOLBOX | ANDROID | NONE
  val appCanSu: Boolean
  val usesDaemon: Boolean         // full profile behaviour requires the helper
  val ledMechanism: LedMechanism // RK3576_IOCTL | RK3576_IOCTL_DAEMON | SYSFS_DAEMON | AUTODETECT | NONE
  val hasButtonBacklight: Boolean // distinct helper-driven BTN node; not inferred from the RGB backend
  val screenOff: ScreenOff       // expected hardware path; live control still falls through safe routes
  val zigbeeGatewayDir: String?  // /vendor/bin/siliconlabs_host | null
  val relayBase: String?         // /sys/class/st_relay | null
  val buttonLedGpioBase: Int?    // 147 | null
  // + display-density quirk, WebView note, etc.
  companion object { fun detect(): DeviceProfile }   // memoized Build.* + product-version match, else Generic
}
```

One silo file per supported platform:

- `device/NSPanelPro.kt`
- `device/Tpa10.kt`
- `device/Wf1589t.kt`
- `device/Smt1019.kt`
- `device/S9e.kt`
- `device/EchoShow5Gen2.kt`
- `device/ZxSmt156.kt`
- `device/ShellyWallDisplay.kt`
- `device/ShellyWallDisplayV2.kt`
- `device/Generic.kt`

`DeviceProfile.detect()` memoizes a pure match over `Build.MODEL`, `Build.DEVICE`, and `ro.product.version`, falling back to `Generic` when nothing matches. Exact product identities are evaluated before broad reference-platform aliases such as `px30`, `rk3326`, and `rk3576_u`, because unrelated vendors can ship the same SoC. Sysfs and daemon probes confirm capabilities after selection; they do not choose the profile. See the per-panel [hardware docs](../hardware/README.md) for the physical detail behind each profile.

## The rule that keeps it from being brittle

The profile declares **candidates and quirks**; the functional module still runtime-probes whenever the platform exposes a reliable probe. The profile says *where to look*; the probe says *whether it is actually reachable*. Some facts cannot be discovered generically, so they remain explicit profile facts: a distinct button-backlight node, evdev button mappings, firmware-specific sensor behavior, and known-good update pins.

Two consequences:

- **The `Generic` profile is conservative.** Standard Android sensors, generic LED routes, and available CPU governors can be probed; relays, evdev buttons, vendor radios, climate chips, and update pins remain absent until their paths or protocols are known.
- Capability support is still detected by **bottom-up runtime probing** inside each functional module — robust, and now backed by a per-device canonical place for the customisations.
- `appCanSu` and other route fields are preferences or expectations, not permission claims. Privileged controllers try safe alternatives after live failure, while `usesDaemon` records whether complete profile behavior needs the helper even when ordinary `su` also works.

## Onboarding a new panel

The atomic, progressive path a newcomer previously lacked:

> [!TIP]
> Fingerprint your panel → if the conservative generic routes are sufficient, you are done; otherwise add a `device/Xxx.kt`, an exact-before-broad match rule, and the cross-profile contract coverage in the same change.

---

## Background

<details>
<summary>The problem this solved</summary>

When this architecture was introduced, ha-paneld supported four platforms (Sonoff NSPanel Pro / PX30, Tuya TPA10 / rk3566, Electron WF1589T / rk3576, Smatek S9E). Capability support was detected by **bottom-up runtime probing** inside each functional module, which was robust, but the **device-specific customisations for a given platform were not in one canonical place**. Before 0.7.0, answering "what does ha-paneld do specially for the TPA10?" required reading five modules:

- **NSPanel Pro** specifics live in `ZigbeeController` (`/vendor/bin/siliconlabs_host`), `ScreenController` (`su bl_power` tier), `Su` (toolbox `su -c`).
- **TPA10**: `SocketLedController`/helper daemon (avsux), `Su` (`su 0 sh -c`), the app-can't-`su` wall.
- **WF1589T / rk3576**: `Rk3576LedController` / `NativeLed` (`/dev/ledjni`), `LedFactory`.
- **S9E**: `RelayController` (`/sys/class/st_relay`, gpio147–150).

There was no `Tpa10` file. A contributor with a new "XXX panel" had no single silo to populate and no documented atomic path to progressively light up their hardware.

</details>

<details>
<summary>Relationship to a capability registry (complementary)</summary>

- **`DeviceProfile`** = *what a device has + where + quirks* (this document; the per-device axis).
- A **capability registry** = *how to drive a feature + publish/diag it* (the per-feature axis) — would also fix the current drift where detection, MQTT discovery gating, and the `/diag` capability matrix are three separate implementations. An optional follow-on.

</details>

> [!NOTE]
> For the runtime trust boundaries these modules operate within (the root helper daemon, su call-sites, MQTT control plane), see the [security model](security.md).
