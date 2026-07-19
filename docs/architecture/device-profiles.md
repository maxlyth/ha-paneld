# Device-profile architecture

Each supported panel has one canonical profile that declares everything ha-paneld does specially for that hardware. Generic functional modules consume the resolved profile instead of hard-coding paths and quirks, so “what does ha-paneld do for this panel?” has one answer.

Profiles are versioned YAML documents loaded at runtime. ha-paneld ships immutable bundled profiles for the hardware maintained by the project, while administrators can inspect, fork, import and activate local revisions without rebuilding the Android app. Both sources pass through the same parser, validator, matcher and compiled driver catalog.

For the author workflow, see [Runtime panel profiles](../profiles/README.md). For the document model, see [Profile format and compatibility](../profiles/format.md).

## Profile versus driver

A profile describes hardware facts and selects mechanisms. A driver implements a mechanism.

- **Profile** — panel identity, fingerprint rules, named firmware/model strategies, capability candidates, typed driver parameters, display/sensor/SoC metadata, inert reference links, update recommendations and access guidance.
- **Driver** — compiled code that probes or operates an Android API, device node, sysfs class, helper command or other bounded mechanism.

The split is a security and maintenance boundary. An imported profile can select only drivers known to the running core; it cannot contain executable code, shell commands, helper verbs, native libraries, ioctl payloads or a new protocol. HTTPS product and reference links are display-only and are never fetched or interpreted as provisioning input. Adding a genuinely new mechanism still requires a normal ha-paneld implementation and release. Once that mechanism exists in the catalog, later panels can select it in YAML.

## One resolved profile per service lifetime

The foreground service resolves a profile once during startup, then supplies the same immutable result to sensors, hardware controllers, the HTTP UI and MQTT discovery. Profile activation is therefore restart-bound: hot-swapping selected fields would leave long-lived controllers, evdev listeners and Home Assistant entities disagreeing about the panel.

The activation transaction is:

1. Parse and validate untrusted YAML without changing runtime state.
2. Show the exact candidate hash, compatibility result, match result, risks and diff from the active revision.
3. Save an immutable inactive revision.
4. On explicit activation, record the desired exact revision and generation, return the HTTP response, then restart the service.
5. Resolve and construct every controller from that revision.
6. Mark the activation healthy only after startup reaches the required health point.
7. If resolution or startup fails, select the last-known-good revision and restart conservatively.

The local UI remains available after profile failure. When no installed panel-specific profile can run, ha-paneld normally resolves the bundled `generic.yaml` fallback. If that bundled fallback is itself missing or corrupt, a capability-empty emergency contract keeps the local recovery surface available without inventing hardware support.

## Sources and immutability

Bundled revisions live in the APK and cannot be edited or deleted. Editing one creates a complete local fork, so a local file never depends on hidden mutable state in the bundled copy.

Imported revisions are stored by stable profile ID and the lowercase SHA-256 of their exact YAML. Preview tokens bind import to the bytes that were inspected. Catalog generations prevent a stale browser from overwriting or selecting a different revision after another administrator changes the catalog.

Profile selection is either automatic matching or an explicit pin to one immutable revision. Saving or importing never changes selection by itself.

## Matching

Automatic matching is pure and limited to immutable build facts: Android build model, build device and the vendor product-version property. It does not probe sysfs or run privileged commands.

Exact bundled product identities are evaluated before bundled reference-platform fallbacks such as `px30`, `rk3326`, `rk3566` and `rk3576`, because unrelated vendors can ship the same SoC. The resolver compares the highest matched group priority, then the profile priority; an exact tie fails closed to the bundled Generic profile (or the capability-empty emergency contract if that asset is unavailable). This two-level order lets one profile carry both an exact identity and a lower-priority compatibility fallback without its broad branch defeating another product's exact rule. Local/community match rules are preview evidence only and never enter automatic selection. A local revision can override bundled detection only through explicit activation; once pinned, the panel keeps that exact revision rather than being reinterpreted by a later import.

Named core-owned strategies can derive a small set of bounded values from those same facts, for example a product variant that changes the local model label or recommended density. The schema has no general scripting, arbitrary expressions or conditional patch section. Proximity polarity, ranges and firmware classifiers are deliberately not profile data; the runtime learner normalizes live sensor behavior across panels.

## The rule that keeps profiles from being brittle

A profile declares **candidates and quirks**; the functional driver still runtime-probes whenever the platform exposes a reliable probe. The profile says where or how to look, while the probe says whether the interface is actually present and reachable.

Some facts cannot be discovered generically, so they remain explicit profile knowledge: the SoC model and CPU core class, a distinct button-backlight node, an evdev button mapping, firmware-specific sensor behavior, a physical display PPI, or a known-good core-owned update artifact ID. The artifact's URL, version and signer hash stay compiled into the core rather than being supplied by YAML. Those facts carry evidence and compatibility responsibility when a profile is shared.

Consequences:

- **Generic is conservative.** Standard Android sensors, available CPU governors and other safe routes can be observed; relays, evdev buttons, vendor radios, climate chips, update artifacts and privileged paths remain absent until a reviewed profile declares them.
- **Declaration is not authority.** A root driver is unavailable when live `su`/helper checks fail. A Shizuku recommendation is author guidance and never installs the Manager, records consent or grants a permission.
- **Capabilities degrade rather than inventing success.** The UI and diagnostics distinguish a declaration from live availability and a missing authority route.
- **A profile never expands the helper protocol.** Root/helper commands remain compiled, authenticated, allowlisted and bounded independently of YAML.

## Generic passive draft

An unrecognised panel can export a draft built only from passive evidence. Draft generation reads the immutable fingerprint and bounded Android observations; it must not write hardware, change settings, run `su`, invoke the helper or perform exploratory active probes.

The draft deliberately contains unknown/TODO values. Its purpose is to give a community author a safe, reproducible starting point, not to turn filenames and retail claims into asserted capabilities. See [Starting from Generic](../profiles/README.md#starting-from-generic).

## Schema and compatibility

The profile schema descriptor and driver catalog are runtime-owned, typed contracts used by the parser, Profiles UI and public reference. Normal unknown fields fail validation, required driver and recipe IDs must exist in the running core, and schema 2 is the sole accepted document format. Stored schema-1 proof-of-concept revisions can still be identified, exported and deleted, but cannot activate or serve as rollback targets.

The profile's own content version is independent of the document schema. Installed profiles are revalidated after an app update, and an incompatible local revision cannot replace a healthy active profile.

## Risks highlighted before import

Validation identifies unusually consequential behavior separately from ordinary metadata. The preview calls out root-controlled paths, relay or GPIO writes, evdev reads and grabs, recommended package-disable intents, WebView installation recommendations, and a local profile that overrides a bundled match. These are review prompts, not permissions; activation still requires an exact confirmed revision and every driver still enforces its own parameter validation and live checks.

## Background

Before the profile silo existed, device-specific behavior was spread across functional modules. Answering what ha-paneld did specially for one platform required reading LED, screen, root, relay and radio controllers independently. The original Kotlin `DeviceProfile` interface brought those customisations together but still required an app rebuild for every profile change.

Runtime YAML preserves the one-silo design while separating hardware knowledge from the release cycle. Built-in and community profiles now use the same resolved model, while compiled drivers remain the per-feature implementation axis.

> [!NOTE]
> For the runtime trust boundaries around profile import, the root helper, `su`, Shizuku and the LAN HTTP surface, see the [security model](security.md).
