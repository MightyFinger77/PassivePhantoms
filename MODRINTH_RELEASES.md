# Modrinth version release notes

## 1.3.0-Dev1b (2026-07-15)

- Fully Folia-compatible on the correct Dev1b base (config v3 header preserved).
- EntityScheduler + per-chunk RegionScheduler for all entity/world work.
- Fixed `cant getEntities asynchronously` and remaining cross-region query hazards.
- Paper/Spigot behavior preserved on non-Folia servers.

## 1.3.0-Dev1a (2026-03-19)

- Added Folia support baseline for passivephantoms.
- This release was branched from the latest version to avoid breaking existing installs.
- Internal migration/update compatibility checks were reviewed for this version line.
