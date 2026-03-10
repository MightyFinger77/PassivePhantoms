# PassivePhantoms 1.2.6 Codebase Review

## What works well

- **Passive/aggressive tracking**: UUID set and event handling correctly make phantoms aggressive on player/projectile damage and re-target when they lose target.
- **Spawn control**: Overworld spawns cancelled; End spawning per-player with per-chunk mobcap.
- **Movement monitoring**: Stuck detection and tree avoidance only run in The End; cooldowns and thresholds are configurable.
- **Config migration**: Existing migration adds missing keys and removes deprecated options; `addBehaviorComments` appends behavior info once.
- **Commands**: reload, debug, status, list with permission checks and tab completion.

## Issues and tweaks

### Memory leaks

1. **Tracking maps never pruned for despawned phantoms**  
   When a phantom is removed without `EntityDeathEvent` (chunk unload, `entity.remove()`, etc.), its UUID stays in `aggressivePhantoms` and in the four movement maps (`lastPhantomLocations`, `stuckCounter`, `lastMovementTime`, `stuckAttempts`, `lastTreeAvoidanceTime`). Over time this can grow.

   **Fix**: Use `cleanupPhantomData(phantomId)` in `onPhantomDeath` (and ensure it clears all five maps). Optionally run a periodic prune: remove UUIDs from `aggressivePhantoms` (and movement maps) when `Bukkit.getEntity(uuid) == null`.

2. **onPhantomDeath cleanup incomplete**  
   Death handler removes from `aggressivePhantoms` and four maps but not `lastTreeAvoidanceTime`. `cleanupPhantomData` clears all five; death should call `cleanupPhantomData(phantomId)` for consistency.

3. **onDisable**  
   Clear all tracking sets/maps in `onDisable()` so references are released when the plugin is disabled.

### Main-thread usage

- **Entity and world access**  
  All entity/world usage is correctly on the main thread: `countPhantomsInChunk`, `world.getEntities()`, `monitorPhantomMovement`, block lookups. Moving these off the main thread would require scheduling back for entity/world changes and is not recommended.

- **Heavy work on main**  
  `monitorPhantomMovement` runs every `stuckDetectionTicks` (default 100) and iterates all entities in End world(s). With many entities this is a spike; already scoped to End only. Optional improvement: cap iterations per tick or split across ticks (not done in this pass).

- **Config file write in addBehaviorComments**  
  Uses `Files.readAllBytes` / `Files.write` on the config file. Only called from `migrateConfig()` before `loadConfig()`, so no concurrent use with Bukkit’s config API in normal flow.

### Async opportunities

- **Update check**  
  Only clear win: run the Modrinth (or any HTTP) update check asynchronously, then apply results on the main thread (e.g. set `latestVersion` / `updateAvailable` and notify on join). No other logic is a good candidate for async without larger refactors.

### Other tweaks

- **findNearestChorusFruit**  
  Uses `radius * radius` in the loop but `radius` is `(int) treeAvoidanceRadius`; for consistency with `isNearChorusFruit` use `radiusSquared` (int) so the condition matches.

- **Reload command**  
  After `reloadConfig()` + `migrateConfig()` + `loadConfig()`, config is up to date; no further change needed for Locktight-style migration.

---

## Summary of code changes (this pass)

1. **Locktight-style config migration**: Add `config_version`, merge default config (from jar) with user values line-by-line, preserve comments, remove deprecated keys.
2. **Modrinth update checker**: Async check on enable; console log if update available; notify players with `passivephantoms.notify` on join (with delay); config option `update_checker`.
3. **Memory / cleanup**: Use `cleanupPhantomData` in `onPhantomDeath`; clear all tracking data in `onDisable`; optional periodic prune of stale UUIDs from tracking maps.
