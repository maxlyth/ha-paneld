# Profile examples

These files use the public schema accepted by the Profiles page. They are teaching material, not claims that the fictional hardware exists.

- [`minimal-community.yaml`](minimal-community.yaml) is a conservative, standard-Android starting point. It enables no vendor protocol, root/helper path, relay, evdev input, update artifact, package desired state or provisioning recipe.

Import an example through **Profiles**, review the validation preview and save it only as an inactive local revision. Change the ID, provenance, exact fingerprint, tested firmware and limitations before using it as the basis for a real panel. An imported match is preview evidence only; local/community profiles are never selected automatically.

For complete production examples, export one of the immutable bundled profiles from a running panel. Those profiles exercise the same schema but may select privileged compiled drivers and core-owned artifacts that should not be copied without hardware evidence.
