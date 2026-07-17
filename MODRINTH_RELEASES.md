# Modrinth version release notes

## 1.3.0-Dev2a (2026-07-15)

- Added `allow_overworld_spawning` and `allow_end_spawning` (config v4) for optional Overworld/End spawn control.
- Defaults match prior behavior: Overworld off, End custom spawns on.
- Status/debug output shows both flags; migration merges new keys into existing configs.
- Locktight-style update checker: `/passivephantoms update`, join notify for ops, notify online admins when outdated.

## 1.3.0-Dev1b (2026-07-15)

- Fully Folia-compatible on the correct Dev1b base (config v3 header preserved).
- EntityScheduler + per-chunk RegionScheduler for all entity/world work.
- Fixed `cant getEntities asynchronously` and remaining cross-region query hazards.
- Paper/Spigot behavior preserved on non-Folia servers.

## 1.3.0-Dev1a (2026-03-19)

- Added Folia support baseline for passivephantoms.
- This release was branched from the latest version to avoid breaking existing installs.
- Internal migration/update compatibility checks were reviewed for this version line.
