# Device-profile architecture

> Status: **implemented in 0.7.0** — an architecture-focused release, no new user features. The LED,
> Zigbee and relay controllers read the active profile; `Su` and `ScreenController` deliberately stay on
> their runtime autodetect/tiering (already device-agnostic), with their profile fields declarative.

## Problem

ha-paneld supports four platforms (Sonoff NSPanel Pro / PX30, Tuya TPA10 / rk3566, Electron WF1589T /
rk3576, Smatek S9E). Capability support is detected by **bottom-up runtime probing** inside each
functional module, which is robust — but the **device-specific customisations for a given platform are
not in one canonical place**. To answer "what does ha-paneld do specially for the TPA10?" you must read
five modules:

- **NSPanel Pro** specifics live in `ZigbeeController` (`/vendor/bin/siliconlabs_host`), `ScreenController`
  (`su bl_power` tier), `Su` (toolbox `su -c`).
- **TPA10**: `SocketLedController`/helper daemon (avsux), `Su` (`su 0 sh -c`), the app-can't-`su` wall.
- **WF1589T / rk3576**: `Rk3576LedController` / `NativeLed` (`/dev/ledjni`), `LedFactory`.
- **S9E**: `RelayController` (`/sys/class/st_relay`, gpio147–150).

There is no `Tpa10` file. A contributor with a new "XXX panel" has no single silo to populate and no
documented atomic path to progressively light up their hardware.

## Decision

Introduce a **`DeviceProfile`** — one canonical silo per platform that declares everything
device-specific. Generic functional modules read the active profile instead of hard-coding paths/quirks.

```kotlin
interface DeviceProfile {
  val id: String                 // nspanel-pro | tpa10 | wf1589t | s9e | generic
  val socClass: String           // PX30 | rk3566 | rk3576
  val suForm: SuForm             // TOOLBOX | ANDROID | NONE (app-sandbox-walled → daemon)
  val appCanSu: Boolean
  val ledMechanism: LedMechanism // RK3576_IOCTL | SYSFS_DAEMON | NONE
  val screenOff: ScreenOff       // SU_BLPOWER | BRIGHTNESS_ZERO
  val zigbeeGatewayDir: String?  // /vendor/bin/siliconlabs_host | null
  val relayBase: String?         // /sys/class/st_relay | null
  val buttonLedGpioBase: Int?    // 147 | null
  // + display-density quirk, WebView note, etc.
  companion object { fun detect(ctx): DeviceProfile }   // fingerprint Build.* + sysfs, else Generic
}
```

Silo files: `device/NSPanelPro.kt`, `device/Tpa10.kt`, `device/Wf1589t.kt`, `device/S9e.kt`,
`device/Generic.kt`.

## The rule that keeps it from being brittle

A static product matrix is fragile (observed: `.45`/bmp had an orphaned zgateway with an empty
`siliconlabs_host` dir; the S9E is rk3566 like the TPA10). So:

- **The profile declares *candidates + quirks*; the functional module still runtime-probes to confirm.**
  Profile says *where to look*; the probe says *whether it's actually there*.
- **The `Generic` profile probes everything generically**, so an unknown panel still advertises whatever
  it physically has with no profile written. A `device/Xxx.kt` is added only when a panel needs a quirk
  the generic path can't infer.

This preserves today's graceful degradation while giving the per-device canonical silo.

## Relationship to a capability registry (complementary, not required for 0.7.0)

- **`DeviceProfile`** = *what a device has + where + quirks* (this document; the per-device axis).
- A **capability registry** = *how to drive a feature + publish/diag it* (the per-feature axis) — would
  also fix the current drift where detection, MQTT discovery gating, and the `/diag` capability matrix
  are three separate implementations. Optional follow-on; out of scope for the 0.7.0 silo work.

## Migration (staged, one concern per commit; no behaviour change)

1. Define `DeviceProfile` + enums + `Generic` + `DeviceProfile.detect()`.
2. Add one real profile (e.g. `NSPanelPro`) as the reference; wire `Su`/`ScreenController`/`ZigbeeController`
   to read it. Verify on-device — identical behaviour.
3. Migrate the remaining device-specific modules (LED, relay/button-LED) one per commit.
4. Document the "Adding a panel" onboarding path; cross-link the per-panel hardware docs.

## Onboarding outcome

"Fingerprint your panel → if it works generically, done; else drop a `device/Xxx.kt` declaring its
quirks." That is the atomic, progressive path a newcomer currently lacks.
