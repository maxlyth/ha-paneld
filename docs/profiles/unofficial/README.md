# Unofficial panel profiles

This directory is a catalog of community-supplied profiles that are not bundled with ha-paneld. Their presence in the repository is not a claim that ha-paneld runs on the named retail hardware as sold. Each profile may describe a particular reflash, firmware modification or single reported installation rather than the manufacturer's stock software.

Unofficial profiles are never selected automatically. Download the individual YAML file, inspect its source, tested firmware and limitations, and use the panel's **Profiles** page to import it. Importing and validating are preview-only; they neither save nor activate the profile.

Before saving, require the preview to report that every predicate in one complete match group agrees with the panel's immutable device facts. A matching name alone is insufficient. Activation hard-checks that identity and rejects unsafe touchscreen grabs, but it does not preflight every live privilege route. After the controlled restart, the running capability-specific drivers probe their interfaces and authority when initialized or used; unavailable capabilities stay unavailable, and startup health determines whether the candidate remains active or rolls back. Never treat a successful import or activation as proof that every declared capability works.

To try an unofficial profile safely:

1. Open `http://<panel-ip>:8888/profiles`, export the active revision and make a current full panel backup.
2. Download and inspect the YAML from this directory. Confirm that its exact firmware and modification assumptions describe the panel in front of you.
3. Paste or upload the YAML on **Profiles**, then validate it. Do not continue if the identity match is incomplete, a required compiled driver is missing or an unsafe input mapping is reported. Validation does not prove live hardware authority.
4. Save the validated content as an inactive local revision. Saving still does not alter the running profile.
5. Test and activate it only while somebody can see and touch the panel, following the [profile testing checklist](../testing.md). Activation is tied to the exact profile ID, revision and content hash.
6. After restart, inspect the live capability results and confirm the active revision, expected controls and Home Assistant entities. An unavailable route must remain unavailable rather than being treated as successful. Exercise **Roll back** before relying on the profile unattended; failed startup health should return to the last-known-good revision automatically.

Returning to **Use automatic** selects only a bundled profile or the conservative Generic fallback. It will not automatically reactivate an unofficial revision.

Development builds previously carried an earlier Echo revision as a bundled profile. That old core-owned ID does not confer support and does not migrate to the community ID in this catalog. An owner who deliberately wants the profile must import, validate and activate the new `community.*` revision explicitly.

## Catalog

- [`community-cronos-lineageos18.yaml`](community-cronos-lineageos18.yaml) — a draft for one contributor's Amazon Echo Show 5 Gen 2 reflashed with LineageOS 18.1. Stock Fire OS cannot run ha-paneld; unlocking, reflashing and recovery are not supported by this project, and the profile has not been maintainer- or fleet-qualified. See the [profile-specific evidence notes](echo-show-5-gen2.md).
- [`community-rpi4-konstakang-lineageos.yaml`](community-rpi4-konstakang-lineageos.yaml) — a draft for one contributor's Raspberry Pi 4 Model B running KonstaKANG's LineageOS Android 16 build with a separately sourced HDMI touchscreen. This is an assembly rather than a retail panel, so the attached display's density and wake behaviour are the owner's to establish; the profile's one declared behaviour is the `keyevent` screen route, for a board that exposes no Linux backlight device. Whether a touch wakes the sleeping panel depends on that touchscreen and must be proved attended. See the [profile-specific evidence notes](raspberry-pi-4-konstakang.md).
- [`community-sunworld-yc-sm55p-p76s01.yaml`](community-sunworld-yc-sm55p-p76s01.yaml): a draft for one contributor's 5.5-inch Sunworld YC-SM55P on the Portworld P76S01 board. The profile records the firmware-backed RK3576S identity and conservative Android control routes, but leaves optional sensors and camera hardware disabled until physical testing. Its generic Rockchip build identity is not safe for automatic selection. See the [profile-specific evidence notes](sunworld-yc-sm55p-p76s01.md).

For the profile format, trust boundary and contribution requirements, see the [runtime profile guide](../README.md), [format reference](../format.md), [testing checklist](../testing.md) and [sharing guide](../sharing.md).
