# Profile format and compatibility

Runtime panel profiles are YAML documents with a closed, versioned schema. A machine-readable authoring descriptor and driver catalog are available from the Profile page on a profile-capable panel; this page is the complete human-readable field reference. It explains the document model and compatibility rules but does not replace validation by the target ha-paneld version.

## Document model

Every profile contains these conceptual sections:

| Section | Purpose |
| --- | --- |
| Schema | Selects the profile document language understood by ha-paneld. |
| Identity | Stable profile ID and independently versioned profile content. |
| Metadata | Human name, author, source, display-only links, license, maturity, tested firmware and known limitations. |
| Requirements | Minimum compatible ha-paneld core and the compiled drivers/features the document requires. |
| Match | Bounded rules over the panel fingerprint. |
| Capabilities and drivers | Hardware facts plus compiled driver selections and typed parameters. |
| Named strategies | Optional core-owned rules for the small set of supported firmware/model-dependent values. |
| Provisioning intent | Access guidance, software/display recommendations, package desired state and selected core-owned recipes; never a permission grant. |

Schema 2 has the closed root fields `schema`, `id`, `version`, `display_name`, `soc_class`, `soc`, `metadata`, `requires`, `match`, `platform`, `hardware`, `sensors`, `identity`, `input`, `cpu`, `display` and `provisioning`. Field names are lowercase `snake_case`; values are case-sensitive unless their field description says otherwise. See the [minimal community example](examples/minimal-community.yaml).

The exact allowed enum values and driver/artifact vocabulary are defined by the schema and driver catalog served by the running version. Validate against the same release that will activate the profile.

## Closed schema

Unknown fields are errors. This catches misspellings that would otherwise silently remove a safety property or leave a capability unconfigured. Schema 2 is a preview and has no free-form extension area. Put author, HTTPS source, SPDX-style license, `draft`/`experimental`/`verified` maturity, tested firmware and limitations in `metadata`; those values cannot define matching, drivers, access or runtime behavior.

Duplicate YAML keys, aliases and recursive keys are errors. Exactly one YAML document is allowed. YAML tags cannot construct application or Java objects, and profiles cannot contain regular-expression predicates. The loader accepts at most 20 nesting levels, 20,000 parser events, 512 entries in any one map or list, and 16,384 characters in any one string. Keep documents simple and explicit even when a YAML editor supports more elaborate syntax.

Each YAML revision is limited to 128 KiB. The local imported catalog is also bounded to 128 revisions total, 16 revisions for one profile ID and 4 MiB of YAML. Export and delete obsolete inactive revisions before saving more; active, pending and last-known-good revisions remain protected. These limits keep an exchanged-profile workflow from exhausting panel storage or making startup parse an unbounded catalog.

Required fields cannot be null. For optional fields, the current reader generally treats null as absent, but authors should omit unknown values to keep intent clear. Empty strings, empty lists/maps, zeroes and false are real values and can differ from omission or a documented default.

## Identity and versions

Use a stable, distinguishable profile ID of 1–128 lowercase ASCII letters or digits with dots and hyphens only in the interior. Consecutive dots are not allowed. Prefer an author or organisation prefix when a generic model name might collide, for example `org.example.wallpanel-x1`. Do not put a firmware number in the ID when one profile and its named strategies can cover the hardware family.

The profile content version belongs to the author and changes when the profile's knowledge or behavior changes. Use semantic versioning if the profile will be shared:

- patch: clarifications or metadata that do not change matching or runtime behavior;
- minor: additive capability knowledge, a new compatible firmware variant or a safer recommendation;
- major: changed matching identity, driver selection, privilege needs or another change that deserves deliberate migration.

The schema version belongs to ha-paneld. Authors must not increment it to version their own profile.

## Complete schema-2 field reference

“Required” means the key must be present with the stated type. “Optional; default …” means omission—or null for an optional field—produces that value. Unknown keys at every level are errors. All integers must fit a signed 32-bit value before the narrower field range is checked, and all numbers must remain finite when converted to a 32-bit float where the field uses a number.

### Root fields

| Field | Presence | Type and validation |
| --- | --- | --- |
| `schema` | Required | Integer; exactly `2`. Schema 1 was a proof of concept and is not accepted. |
| `id` | Required | String, 1–128 characters; lowercase ASCII letters/digits, with `.` or `-` only in the interior; `..` is forbidden. |
| `version` | Required | Profile content version: exactly three dot-separated components, each `0` or 1–9 digits without a leading zero; optional dot-separated prerelease identifiers made from ASCII letters, digits and `-`; no build-metadata suffix. |
| `display_name` | Required | Non-blank string, 1–100 characters. |
| `soc_class` | Required | Non-blank string, 1–100 characters. |
| `soc` | Optional; default absent | Structured, profile-evidenced SoC facts; fields below. |
| `metadata` | Required | Mapping; fields below. |
| `requires` | Required | Mapping; fields below. |
| `match` | Required | Mapping; fields below. |
| `platform` | Required | Mapping; fields below. |
| `hardware` | Required | Mapping; fields below. |
| `sensors` | Optional; default `{}` | Mapping; fields below. |
| `identity` | Optional; default `{}` | Mapping; fields below. |
| `input` | Optional; default `{}` | Mapping; fields below. |
| `cpu` | Optional; default `{}` | Mapping; fields below. |
| `display` | Optional; default `{}` | Physical display facts; fields below. |
| `provisioning` | Required | Provisioning intent mapping; fields below. |

### `metadata` and `requires`

| Field | Presence | Type and validation |
| --- | --- | --- |
| `metadata.author` | Required | Non-blank string, 1–100 characters. |
| `metadata.source` | Optional; default absent | Absolute HTTPS URL, at most 500 characters, with a non-empty host and no user-information component. |
| `metadata.links` | Optional; default `[]` | List of at most eight display-only external links. ha-paneld never fetches, preloads or uses them for provisioning. URLs and labels must be unique and a link cannot repeat `metadata.source`. |
| `metadata.links[].label` | Required per item | Non-blank display label, 1–48 characters, without control or bidirectional-formatting characters. The Profiles UI also shows the destination hostname in an isolated text run. |
| `metadata.links[].url` | Required per item | Absolute HTTPS URL using the same 500-character, host and no-user-information rules as `metadata.source`. |
| `metadata.license` | Required | SPDX-style string consisting of one or more 1–64 character identifiers matching `[A-Za-z0-9][A-Za-z0-9.+-]*`, separated only by ` AND ` or ` OR `. Parentheses, `WITH` and arbitrary prose are not accepted. |
| `metadata.maturity` | Required | `draft`, `experimental` or `verified`. |
| `metadata.tested_firmware` | Optional; default `[]` | List of at most 32 non-blank strings, each at most 120 characters. |
| `metadata.limitations` | Optional; default `[]` | List of at most 32 non-blank strings, each at most 500 characters. |
| `requires.min_core_version` | Optional; default absent | Dotted release version, at most 64 characters: 1–4 numeric components of 1–6 digits each, with an optional dot-separated ASCII alphanumeric/`-` prerelease suffix. The running core must compare equal or newer, including prerelease order. |
| `requires.drivers` | Required | List of core driver IDs. Unknown IDs are errors; a driver required by a populated capability must be listed. An otherwise unused driver produces a warning. |

### `soc`

`soc_class` remains the required broad family label used by older schema-2 profiles. The optional structured block records facts that Android generally cannot identify reliably, such as whether a nominally quad-core panel uses Cortex-A35 or Cortex-A55 cores. These values appear on the Dashboard and Profiles page but are omitted from the terse public `/diag` report. Add only facts supported by a public source or hardware evidence; omit unknown fields rather than guessing.

| Field | Presence | Type and validation |
| --- | --- | --- |
| `soc.model` | Required when `soc` is present | Non-blank model name, 1–100 characters, without C0 or DEL controls. |
| `soc.introduced_year` | Optional; default absent | Integer 1970–2100. Use the SoC's public introduction year, not the panel release year. |
| `soc.cpu_cores` | Optional; default `[]` | At most eight architecture clusters and 256 cores in total. An empty list means the model is known but the CPU topology is not sufficiently evidenced. |
| `soc.cpu_cores[].architecture` | Required per item | Non-blank core architecture, 1–64 characters, without controls; architectures must be unique ignoring case. |
| `soc.cpu_cores[].count` | Required per item | Integer 1–128. |

### `match`

`match.any` is OR across groups; one group’s `all` list is AND across predicates; one predicate’s `values` list is OR. Comparisons are case-sensitive against build facts that ha-paneld has already lowercased.

| Field | Presence | Type and validation |
| --- | --- | --- |
| `match.priority` | Required | Integer 0–1000; tie-breaks profiles after matched group priority. |
| `match.fallback` | Required | Boolean. `true` is valid only for the bundled profile whose ID is `generic`; imported/community profiles cannot be fallbacks. |
| `match.any` | Required | List of at most 64 group objects. A non-fallback profile requires at least one group; bundled Generic may use an empty list. |
| `match.any[].priority` | Required | Integer 0–1000; branch specificity used before profile priority. |
| `match.any[].all` | Required | List of 1–8 predicate objects. |
| `match.any[].all[].field` | Required | `model`, `device` or `product_version`. |
| `match.any[].all[].op` | Required | `equals`, `starts_with` or `contains`. |
| `match.any[].all[].values` | Required | List of 1–32 non-blank lowercase strings; each is at most 100 characters and cannot contain C0 or DEL control characters. |

### `platform` and `hardware`

| Field | Presence | Type and validation |
| --- | --- | --- |
| `platform.su_form` | Required | `none`, `android` or `toolbox`. |
| `platform.app_can_su` | Required | Boolean. Must be `false` when `su_form` is `none`; an `android` or `toolbox` form may still be declared with `false` for a sandbox-walled app. |
| `platform.has_recents` | Optional; default `true` | Boolean. |
| `platform.has_native_navbar` | Optional; default `false` | Boolean. Declare `true` only when the firmware draws Android's own navigation bar, which makes the `Native` navbar mode selectable. Leave it `false` unless you have verified it on the hardware: Android's `config_showNavigationBar` resource is unreliable in both directions, and selecting `Native` on a panel that has no system bar would leave no navigation at all. |
| `hardware.led` | Required | Mapping containing the LED fields below. |
| `hardware.led.mechanism` | Required | `none`, `autodetect`, `rk3576-ioctl`, `rk3576-ioctl-daemon` or `sysfs-daemon`. Daemon-only mechanisms are rejected when `platform.app_can_su` is `true`. |
| `hardware.led.transfer` | Optional; default `identity` | `identity` or `rk3576-four-bit`. |
| `hardware.screen_off` | Required | `brightness-zero`, `su-blpower`, `daemon-blpower` or `keyevent`. `su-blpower` requires `app_can_su: true`; `daemon-blpower` requires `app_can_su: false`. `keyevent` is for panels that expose no `/sys/class/backlight` device at all: it injects `KEYCODE_SLEEP` and `KEYCODE_WAKEUP`, which puts **Android itself** to sleep rather than blanking a backlight, and it works through root or the helper daemon, whichever the panel has. Declare it only after checking two things on the hardware, because neither can be probed: whether a touch on the sleeping panel wakes it (Android delivers touches while asleep only where the touchscreen is a kernel wake source, so where it is not, Home Assistant is the only way back), and what the panel shows after `KEYCODE_WAKEUP` — a panel with a PIN, pattern or password configured is refused outright and dims instead, since nobody types a credential on a wall panel. |
| `hardware.has_button_backlight` | Optional; default `false` | Boolean; selects the bounded helper-backed button-backlight capability when true. |
| `hardware.zigbee_gateway_dir` | Optional; default absent | Exact allowlisted path `/vendor/bin/siliconlabs_host`; no other path is accepted. |
| `hardware.relay_base` | Optional; default absent | One exact allowlisted path: `/sys/class/relay`, `/sys/class/st_relay` or `/sys/class/strelay`. |
| `hardware.relay_base_fallbacks` | Optional; default `[]` | List of at most three paths from the same relay allowlist. Entries must be unique and must not repeat `relay_base`. |
| `hardware.button_led_gpio_base` | Optional; default absent | Integer 0–4092, representing the first GPIO in a four-GPIO block. |

### `sensors` and `identity`

| Field | Presence | Type and validation |
| --- | --- | --- |
| `sensors.proximity_technology` | Optional; default absent | Non-blank string, 1–100 characters, without C0 or DEL controls. When present it declares Android proximity sensor use. |
| `sensors.proximity_gpio` | Optional; default absent | Integer 0–4095 for the supported root-backed binary GPIO route. |
| `sensors.light_technology` | Optional; default absent | Non-blank string, 1–100 characters, without C0 or DEL controls. When present it declares Android ambient-light sensor use. |
| `sensors.cht8305` | Optional; default `false` | Boolean; enables an exact core-supported CHT8305-compatible room-climate input through the authenticated helper/Shizuku routes. |
| `sensors.room_temp_offset_c` | Optional; default `0.0` | Finite number from -30 through 30 °C. |
| `identity.manufacturer` | Optional; default absent | Non-blank string, 1–100 characters, without C0 or DEL controls; absence means infer at runtime. |
| `identity.model` | Optional; default absent | Non-blank string, 1–100 characters, without C0 or DEL controls; absence means infer at runtime. |
| `identity.model_label_strategy` | Optional; default `display-name` | `display-name` or `nspanel-product-version`. |

Older schema-2 revisions may contain `proximity_near_below`, `proximity_near_raw`, `proximity_far_raw` or `proximity_graded_strategy`. Current cores accept these retired keys only as compatibility tombstones: they are ignored when loading and omitted when a current profile document is serialized. Exporting an immutable older revision still preserves its original bytes. Proximity classification and fleet-normalized reporting are learned from live sensor behavior rather than encoded as device-specific profile calibration.

### `input`, `cpu` and `display`

| Field | Presence | Type and validation |
| --- | --- | --- |
| `input.evdev_buttons` | Optional; default `[]` | List of at most 32 evdev mapping objects. Duplicate `(sw, code)` pairs are rejected even if their nodes differ. |
| `input.evdev_buttons[].node` | Required per item | String matching `/dev/input/eventN`, where `N` is 1–3 decimal digits. No other device path is accepted. |
| `input.evdev_buttons[].code` | Required per item | Integer Linux input/switch code 1–767. |
| `input.evdev_buttons[].grab` | Required per item | Boolean; true requests exclusive `EVIOCGRAB`. |
| `input.evdev_buttons[].event_type` | Required per item | String of at most 64 characters matching `KEYCODE_[A-Z0-9_]+`. |
| `input.evdev_buttons[].sw` | Optional; default `false` | Boolean; false selects `EV_KEY`, true selects `EV_SW`. |
| `cpu.governors` | Optional; default absent | Mapping whose only allowed keys are `Performance`, `Efficiency` and `Auto`. Each value is a 1–32 character governor name matching `[a-z][a-z0-9_-]*`. |
| `display.physical_ppi` | Optional; default absent | Integer 50–1000. |

### `provisioning`

| Field | Presence | Type and validation |
| --- | --- | --- |
| `provisioning.access` | Optional; default `{}` | Access recommendation mapping. |
| `provisioning.access.shizuku` | Optional; default `none` | `none`, `optional` or `recommended`; omit unless a selected compiled driver explicitly supports this alternate authority route. Never represents live readiness or consent. |
| `provisioning.software` | Optional; default `{}` | Core-owned software policy mapping. |
| `provisioning.software.webview.artifact` | Optional; default absent | Core-owned ID `lineageos-138-arm`, `lineageos-150-arm` or `lineageos-150-arm64`. A profile cannot provide the URL, version or signer hash. |
| `provisioning.software.companion.max_version` | Optional; default absent | Dotted release version using the same syntax and 64-character bound as `requires.min_core_version`. |
| `provisioning.display.density` | Optional; default absent | Integer 80–640 dpi, or the named strategy `nspanel-variant`. |
| `provisioning.display.font_scale` | Optional; default absent | Finite number from 0.5 through 1.5. |
| `provisioning.packages` | Optional; default `[]` | List of 0–128 package desired-state objects. |
| `provisioning.packages[].package` | Required per item | Unique Android package name matching `[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+`. |
| `provisioning.packages[].desired_state` | Required per item | `disabled`. This describes the target state and is not execution consent. |
| `provisioning.packages[].importance` | Required per item | `recommended` or `optional`. Importance controls presentation only and never means apply by default. |
| `provisioning.packages[].tags` | Optional; default `[]` | List of at most eight tags. Each is 1–24 characters, starts with a lowercase ASCII letter and then contains only lowercase letters, digits or `-`. |
| `provisioning.packages[].note` | Optional; default empty string | String at most 500 characters. Notes are supplementary explanations, not procedures. |
| `provisioning.recipes` | Optional; default `[]` | List of at most 32 core-owned recipe selections. |
| `provisioning.recipes[].id` | Required per item | Unique registered ID. Schema 2 initially knows `nspanel-pro.watchdog-e2big-repair` and `tpa10.vendor-stack-minimize`. Recipe entries cannot contain arguments, commands, URLs or workflow definitions. |

The three WebView IDs currently resolve inside the core as follows:

| Artifact ID | Core-owned build |
| --- | --- |
| `lineageos-138-arm` | LineageOS System WebView `138.0.7204.63`, 32-bit ARM. |
| `lineageos-150-arm` | LineageOS System WebView `150.0.7871.63`, 32-bit ARM. |
| `lineageos-150-arm64` | LineageOS System WebView `150.0.7871.63`, 64-bit ARM. |

### Conditional driver requirements

Every driver selected by populated fields must appear in `requires.drivers`. All listed drivers except `screen.brightness-zero` and `sensor.android` are classified as privileged in schema 2.

| Profile condition | Required driver |
| --- | --- |
| `platform.su_form: android` and `platform.app_can_su: true` | `access.android-su` |
| `platform.su_form: toolbox` and `platform.app_can_su: true` | `access.toolbox-su` |
| `hardware.led.mechanism: autodetect` | `led.autodetect` |
| `hardware.led.mechanism: rk3576-ioctl` | `led.rk3576-ioctl` |
| `hardware.led.mechanism: rk3576-ioctl-daemon` | `led.rk3576-ioctl-daemon` |
| `hardware.led.mechanism: sysfs-daemon` | `led.sysfs-daemon` |
| `hardware.screen_off: brightness-zero` | `screen.brightness-zero` |
| `hardware.screen_off: su-blpower` | `screen.su-blpower` |
| `hardware.screen_off: daemon-blpower` | `screen.daemon-blpower` |
| `hardware.screen_off: keyevent` | `screen.keyevent` |
| `hardware.zigbee_gateway_dir` is present | `radio.siliconlabs-host` |
| `hardware.relay_base` is present, or `relay_base_fallbacks` is non-empty | `relay.sysfs` |
| `hardware.button_led_gpio_base` is present | `relay.gpio-button-led` |
| `hardware.has_button_backlight: true` | `input.button-backlight` |
| `sensors.proximity_technology` or `sensors.light_technology` is present | `sensor.android` |
| `sensors.proximity_gpio` is present | `sensor.gpio-proximity` |
| `sensors.cht8305: true` | `sensor.cht8305-daemon` |
| `input.evdev_buttons` is non-empty | `input.evdev` |
| `provisioning.software.webview.artifact` is present | `update.webview` |

### Syntax and storage quotas

| Scope | Limit |
| --- | --- |
| One raw YAML revision | 128 KiB of UTF-8 bytes. |
| YAML document count | Exactly one; empty input is invalid. |
| YAML aliases | Zero. |
| Nesting | At most 20 collection levels. |
| Parser work | At most 20,000 parser events. |
| Any one map or list | At most 512 entries before narrower field limits. |
| Any one string | At most 16,384 characters before narrower field limits. |
| Imported catalog | At most 128 revisions admitted. |
| One imported profile ID | At most 16 revisions admitted. |
| Imported YAML storage | At most 4 MiB total. |

## Requirements

A profile declares the minimum core behavior and compiled drivers it needs. Validation fails closed when a requirement is unavailable. An unavailable requirement is different from a failed live probe: the former means this ha-paneld build does not know the requested mechanism; the latter means the mechanism exists in core but was not reachable on this panel.

Do not broaden requirements pre-emptively. Requiring an unused privileged driver makes the profile harder to run and review without adding capability.

## Match rules

Match rules operate only on the immutable `model`, `device` and `product_version` facts exposed by ha-paneld. A `match.any` list is OR; predicates in one group's `all` list are AND. Predicates support `equals`, `starts_with` and `contains` over lowercase expected values—never regular expressions.

Every group has a branch `priority` from 0–1000. The matching bundled profile with the highest matched group priority wins; profile-level `match.priority` breaks a remaining tie, and an exact tie fails closed to Generic. Use higher group priorities for exact product identities and lower priorities for broad compatibility fallbacks. A rule matching only `px30`, `rk3326`, `rk3566`, `rk3576` or a similarly reused reference name is not enough evidence for hardware-specific controls.

Matching chooses a candidate profile; it does not prove a capability. Drivers still perform their live availability checks, and the conservative result wins when evidence and declaration disagree.

## Named model strategies

The schema has no generic expression language or arbitrary conditional patch section. Its named strategies are deliberately small and core-owned:

- `identity.model_label_strategy: nspanel-product-version` decodes the NSPanel variant and firmware label;
- `provisioning.display.density: nspanel-variant` selects the documented 86P/120P density;
- the default `display-name` and fixed integer values avoid those transforms.

`hardware.relay_base_fallbacks` is ordinary bounded data for known supported relay-class renames, not an expression or arbitrary path search.

Strategies are compiled, bounded transformations named in the schema, not scripts from the profile. They cannot invoke I/O or drivers. The format reference describes the exact result and inputs of every supported strategy.

Create separate profiles when no named strategy covers the difference, or when the products need different compiled drivers, authority tiers, recovery expectations or independently maintained evidence.

## Capabilities and compiled drivers

A capability declaration contains hardware facts and, where needed, a driver ID with typed parameters. Driver IDs name implementations compiled into ha-paneld. The driver owns validation of paths, ranges, event codes, package names and other security-sensitive input. Privileged paths are selected from core allowlists. System WebView updates use `provisioning.software.webview.artifact`, whose enum names a URL, version, artifact checksum and signer hash compiled into and audited with the core; a profile cannot redefine that trust root.

Profiles cannot supply:

- shell commands or argument arrays;
- helper-daemon command names;
- arbitrary filesystem read/write operations;
- native libraries, ioctl numbers or byte payloads;
- APK URLs, signer hashes or new update artifacts;
- executable expressions with network, filesystem, process, reflection or clock access;
- credentials, Home Assistant tokens or MQTT configuration.

A declared capability is a candidate until the driver confirms it. The UI and diagnostics should distinguish **declared**, **available**, **unavailable** and **authority missing** rather than treating the YAML as proof.

## Access guidance

Access metadata may identify the exceptional shell fallback only when it enables a concrete declared capability. Generic update, screenshot, input or display-sizing convenience is not enough. The declaration may travel with a profile, but it does not change permission state; installation, service readiness, local consent and approval remain live local state excluded from backup, restore, MQTT and fleet activation.

See the [Shizuku fallback section of the provisioning guide](../provisioning.md#shizuku-fallback-for-unrooted-panels) for the fixed shell-identity subset and [Runtime panel profiles](README.md#authority-standard-android-and-privileged-routes) for author guidance.

## Compatibility behavior

ha-paneld treats compatibility failures as inactive profile problems, not panel-startup failures:

- Schema `2` is the only accepted document language. Stored schema-1 proof-of-concept revisions remain listable, exportable and deletable but cannot activate; a selected incompatible revision falls back to a compatible schema-2 last-known-good, matching bundled profile or bundled `generic.yaml`.
- An unknown required driver or transform prevents activation.
- Installed local revisions are revalidated after an app update.
- The original YAML and its content hash remain identifiable after validation and normalization.
- An activation failure returns to the last-known-good revision.
- If no panel-specific profile can run, ha-paneld starts with the conservative bundled `generic.yaml` behavior. If that bundled fallback is missing or corrupt, a capability-empty emergency contract keeps the local management UI available without asserting panel hardware.

Content hashes and any separately published signatures answer different questions. ha-paneld's revision hash ties save and activation to the bytes that were inspected. An external signature may help identify a publisher, but it is not an authority signal interpreted by the profile loader. Neither grants root, Shizuku consent or permission to bypass validation and confirmation.

## Matching precedence

The resolution policy protects known products from community files becoming active implicitly:

1. an explicit, valid administrator selection pinned to an exact bundled or local revision;
2. automatic exact bundled matching;
3. automatic bundled compatibility fallbacks;
4. Generic.

Local/community match rules are evaluated for preview and comparison only. They never enter automatic selection, so importing or saving a file cannot override bundled detection. Activating one always requires an explicit pinned selection.

## Compatibility checklist for authors

Before sharing a new revision, confirm:

- schema `2` and every field validate on the oldest core version claimed by the file;
- every required driver is present on that version;
- exact fingerprint fixtures cover each claimed model and firmware family;
- a near-miss fixture does not match a different product using the same SoC;
- every named strategy boundary has been tested immediately below, at and above the transition where applicable;
- every exact match group has a higher branch priority than its broad fallback, with cross-profile collision fixtures;
- the profile works with unavailable optional hardware and missing authority without blocking startup;
- the previous active revision remains a usable rollback target.
