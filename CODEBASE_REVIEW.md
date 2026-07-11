# PassivePhantoms Codebase Review (1.3.0-Dev1b)

## Threading model

- **Paper / Spigot**: Timers and world work run on the main thread (inline).
- **Folia**: GlobalRegionScheduler only dispatches. Entity/world access uses:
  - `EntityScheduler` (`runAtEntity` / `runAtEntityLater`)
  - `RegionScheduler` per chunk (`runAtChunk`)
  - `Bukkit.isOwnedByCurrentRegion` before block/entity touches in the current region
- Tracking maps are `ConcurrentHashMap` / concurrent sets for multi-region access.
- Scheduled tasks are tracked and cancelled in `onDisable`.

## What works well

- Passive/aggressive UUID tracking with death + monitor cleanup.
- End spawn control with radius-based caps (Folia: async multi-chunk count).
- Movement stuck/tree logic scoped to End players / nearby chunks.
- Config migration and Modrinth update checker (async HTTP → global sync for flags).

## Folia rules (do not regress)

- Never call `World.getEntities()` / `getNearbyEntities()` / `getPlayers()` from the global region or across foreign regions.
- Prefer `Chunk.getEntities()` only after `runAtChunk` or when `isOwnedByCurrentRegion` is true.
- Do not fall back to `Bukkit.getScheduler()` on Folia if reflection fails — log and abort.
