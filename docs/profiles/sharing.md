# Sharing and contributing profiles

Runtime profiles make it possible to exchange panel support without asking every user to build ha-paneld. That does not make every exchanged file an officially supported profile. Keep community experimentation easy while making provenance, evidence and maturity visible.

## Community and curated profiles

A **community profile** is authored and exchanged independently, then installed deliberately by a panel administrator. It can use the complete public profile schema and compiled driver catalog, but its presence in an issue, forum post or third-party repository is not a project support or security endorsement.

A **bundled/curated profile** ships with ha-paneld. It is immutable in the installed app and follows the project's release, review, testing and documentation standards. Users can export it or create a local fork, but local changes do not mutate the bundled source.

Promotion from community to bundled is a separate contribution decision. Popularity or a valid schema is not enough; the match, non-probeable hardware facts, privileged paths, firmware coverage and safe degradation all need reviewable evidence.

## Metadata and provenance

Complete the profile's metadata before sharing it:

- stable profile ID and profile content version;
- human-readable panel family/model;
- author or maintaining organisation;
- source/homepage where updates and evidence live;
- a license that permits redistribution;
- maturity such as draft, experimental or verified;
- exact models, Android builds and firmware versions tested;
- important untested variants;
- limitations, reduced capabilities and recovery notes;
- source citations for hardware facts that cannot be runtime-probed.

Use **draft** when the file is primarily generated from Generic or firmware/retail evidence, **experimental** when it has been activated on hardware but important controls or firmware variants remain unverified, and **verified** only when the claimed behavior has been exercised on the stated hardware with rollback/recovery evidence. These labels describe evidence, not code-signing trust.

Record speculation as a limitation, not as a positive capability. For example, a kernel configuration containing a sensor driver does not prove that the sensor is fitted, wired, Android-exposed or accessible to ha-paneld.

## What to share

A useful community profile package is normally just:

1. the exported YAML revision;
2. a short README or post naming the tested hardware and firmware;
3. redacted passive diagnostics;
4. staged test results from [Testing and troubleshooting](testing.md);
5. source links for non-obvious facts;
6. known recovery steps and limitations.

Do not embed APKs, native libraries, shell scripts, firmware blobs, credentials or private diagnostic archives in a profile. If a new driver is required, contribute it to ha-paneld separately; the profile can require it after the implementation is available in a released core.

## Safe exchange

Review a profile as configuration with hardware consequences:

- read the match rules and ensure they identify your product rather than only its SoC;
- inspect every driver and authority requirement;
- review filesystem/device paths, package recommendations and core-owned update artifact IDs;
- verify author/source/license and the file hash obtained from the publisher;
- import and validate on the target panel before saving;
- compare with the active revision and activate only the exact inspected hash;
- keep a known-good rollback revision.

A signature published separately by the author can help establish origin, but ha-paneld does not interpret it as authority and it does not bypass validation, live probes, local Shizuku consent or activation confirmation. Unsigned does not necessarily mean malicious, and signed does not mean suitable for your exact hardware.

## Privacy and redaction

Before attaching a profile or diagnostic report publicly, remove:

- panel names, panel IDs and room names;
- IP, MAC and globally routable IPv6 addresses;
- Wi-Fi SSIDs or credentials;
- MQTT hostnames, usernames and passwords;
- Home Assistant URLs, tokens, entity state and dashboard content;
- private package/application data;
- serial numbers or vendor identifiers that are unique to one device unless strictly required and knowingly disclosed.

Build model, build device, Android version, ABI, vendor product-version and bounded hardware paths are usually the useful matching evidence. Review even a sanitised generated report before posting it.

## Requirements for a bundled contribution

A proposal to bundle a profile should include all community-profile material plus:

- an exact-before-broad matching corpus with positive and near-miss fixtures;
- schema and cross-profile capability-invariant coverage;
- evidence for every privileged path, selected core-owned update artifact and non-probeable hardware fact;
- validation on each claimed firmware family, or explicit variants/limitations where coverage is incomplete;
- attended testing of user-visible hardware plus rollback and never-blank behavior;
- a public hardware page covering SoC/ABI, display, sensors, LEDs/relays/buttons/radios, authority and deployment method;
- updates to the supported-hardware index and both panel-hardware issue-template lists;
- public firmware acquisition/archival information where legally redistributable, without adding firmware blobs to the repository;
- release notes that state setup impact and whether existing installations need action.

The project may keep a useful profile at community or experimental maturity until physical evidence exists. Conservative Generic behavior is preferable to bundling confident-looking guesses.

## Maintaining a shared profile

Keep releases immutable. Publish a new profile content version for changes and preserve old files/hashes so users can reproduce a working installation. Describe matching, driver, authority and default changes prominently; metadata-only corrections should remain distinguishable from behavioral revisions.

Test against the oldest claimed compatible ha-paneld version and revalidate after core schema or driver changes. If maintenance stops, say so in the source metadata and narrow the tested firmware range rather than implying ongoing compatibility.
