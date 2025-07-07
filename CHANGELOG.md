# Changelog

## [1.2.5] - 2025-07-07

**Note: Movement improvements are experimental and phantoms may still get hung up on chorus trees.**

### Added
- **Movement improvements**: Automatic stuck detection and escape assistance for phantoms
- **Natural tree avoidance**: Guides phantoms to fly around chorus fruit trees using gentle velocity adjustments
- **Enhanced status command**: Shows plugin status and movement improvement status

### Configuration
- `phantom_settings.movement_improvements_enabled`: Enable/disable movement improvements (default: true)
- `phantom_settings.stuck_detection_ticks`: How often to check for stuck phantoms (default: 100 ticks = 5 seconds)
- `phantom_settings.stuck_threshold`: Consecutive checks before considering stuck (default: 3)
- `phantom_settings.stuck_distance_threshold`: Minimum movement distance (default: 1.0 blocks)
- `phantom_settings.max_stuck_attempts`: Maximum escape attempts before removal (default: 5)
- `phantom_settings.tree_avoidance_enabled`: Enable/disable tree avoidance (default: true)
- `phantom_settings.tree_avoidance_radius`: Detection radius for chorus fruit trees (default: 3.0 blocks)

### Technical Details
- Single optimized monitoring task handles both stuck detection and tree avoidance
- Tree avoidance uses gentle velocity adjustments instead of teleportation for natural flight
- Stuck detection uses distance-based movement tracking with configurable thresholds

## [1.2.5b2] - 2025-07-07

### Added
- **Per-chunk mobcap system**: Prevents infinite phantom spawning with a limit of 8 phantoms per chunk
- **Configurable mobcap**: New `phantom_settings.max_phantoms_per_chunk` setting allows server admins to adjust the per-chunk limit
- **Mobcap monitoring**: Enhanced `/passivephantoms status` command shows phantom populations per world
- **Chunk-based spawning**: Phantoms now spawn based on chunk limits rather than world-wide limits
- **Better distribution**: Prevents single players from hogging all phantoms in a world
- **Debug logging**: Shows chunk coordinates and mobcap status in debug messages. Enable `debug_logging: true` to see when chunk mobcaps are reached.

### Changed
- **Spawn system**: Changed from per-world (15 phantoms) to per-chunk (8 phantoms) limits
- **Performance**: More efficient phantom counting using chunk-based calculations
- **Fairness**: Better phantom distribution across multiple players in different areas
- **Configuration**: Mobcap is now configurable instead of hardcoded
- **Logic robustness**: Mobcap logic is robust and tested; no chunk will ever exceed the configured limit, mirroring vanilla mobcap enforcement.

### Technical Details
- Uses chunk coordinates (`blockX >> 4`, `blockZ >> 4`) for efficient counting
- Each chunk can independently have up to the configured limit (default: 8 phantoms)
- Multiple chunks can each reach their limit, allowing higher total phantom populations
- Maintains compatibility with multiple End worlds
- Configurable mobcap supports values from 5 (conservative) to 15+ (generous)
- Mobcap enforcement is logged when debug_logging is enabled

## [1.2.5b1] - 2025-07-07

### Fixed
- **CRITICAL**: Fixed phantom targeting issue where Phantom aggresion would randomly break
- **CRITICAL**: Fixed phantom aggression persistence - phantoms now stay aggressive after being hit
- Changed event priority from LOWEST to HIGH for better compatibility with other plugins
- Improved phantom damage event handling to ensure immediate aggression
- Fixed targeting logic to properly handle null targets for aggressive phantoms
- Added immediate re-targeting for aggressive phantoms when they lose their target
- Fixed variable scoping issues in lambda expressions

### Added
- New `/passivephantoms debug` command to toggle debug logging in-game
- New `/passivephantoms status` command to check plugin status and aggressive phantom count
- New `/passivephantoms list` command to list all aggressive phantoms and their current targets
- Enhanced debug logging with phantom UUIDs for better troubleshooting
- **Tab completion** for all `/passivephantoms` commands with smart filtering
- Comprehensive tracking of when phantoms are added/removed from aggressive set

### Changed
- Updated version to 1.2.5 to reflect critical bug fixes
- Improved command help messages with new subcommands
- Enhanced targeting event logging to show target information and aggressive status
- Better error handling and safety checks for phantom targeting

### Technical Details
- Aggressive phantoms now re-target immediately when they lose their target (null)
- Re-targeting uses next-tick scheduling to avoid event conflicts
- Only re-targets when phantom actually loses target, not constantly
- Preserves natural phantom AI attack cycles while maintaining aggression

## [1.2.4] - 2025-07-06

### Added
-Projectile Aggression: Phantoms now become aggressive when hit by projectiles (arrows, tridents, snowballs, etc.) from players
-Enhanced command feedback with colored messages
-Permission system for the reload command
-Better debug logging for projectile attacks
-Better Config migration when updating version

### Fixed
- Updated API version to 1.13 for better compatibility with Minecraft 1.13-1.21
- Changed Java version from 17 to 8 for broader server compatibility
- Fixed default debug logging setting (now defaults to false)
- Improved command usage and help messages

### Changed
- Updated plugin description to mention projectile aggression
- Enhanced configuration documentation
- Better error handling for permission checks

## [1.2.3] - 2025-07-06

### Fixed
- Updated API version from 1.13 to 1.21 to match Spigot API dependency
- Fixed version inconsistency in compile.bat (was 1.0.9, now 1.2.3)
- Fixed typo in README ("natrually" → "naturally")
- Updated compatibility information to show correct version range (1.13 - 1.21+)

### Changed
- Default spawn control enabled (prevents Overworld spawning, enables End spawning)

## [1.2.2] - 2025-07-06

### Added
- `/passivephantoms reload` command
- Spawn rate documentation in config

### Changed
- Default spawn rate changed to 5% (moderate)

## [1.2.1] - 2025-07-06

### Added
- Automatic config updates for new settings

## [1.2.0] - 2025-07-06

### Added
- Custom spawn control (Overworld prevention, End spawning)
- Configurable spawn rates for The End

## [1.1.6] - 2025-06-28

### Removed
- Unused configuration settings

## [1.1.5] - 2025-06-15

### Fixed
- Phantom targeting when provoked

## [1.1.4] - 2025-06-03

### Fixed
- Removed AI interference, restored vanilla flight behavior

## [1.1.3] - 2025-05-25

### Fixed
- Added back essential targeting for aggressive phantoms

## [1.1.2] - 2025-05-18

### Fixed
- Simplified approach, removed excessive retargeting

## [1.1.1] - 2025-05-12

### Fixed
- Aggression persistence during combat

## [1.1.0] - 2025-05-08

### Changed
- Complete redesign with UUID-based tracking
- Natural attack cycles preserved

## [1.0.8] - 2025-05-05

### Added
- Enhanced debug logging and tag persistence

## [1.0.7] - 2025-05-05

### Fixed
- Aggression persistence after attacks

## [1.0.6] - 2025-05-05

### Changed
- Removed retargeting when phantoms hit players

## [1.0.5] - 2025-05-05

### Changed
- Combat-based retargeting only

## [1.0.4] - 2025-05-05

### Changed
- Reduced retargeting frequency for natural flight

## [1.0.3] - 2025-05-05

### Changed
- Continuous retargeting for persistent aggression

## [1.0.1] - 2025-05-05

### Fixed
- Phantom targeting and immediate response

## [1.0.0] - 2025-05-05

### Added
- Initial release with basic passive phantom functionality


## Planned Features

- [ ] Per-world configuration options?
- [ ] Phantom size customization?
- [ ] Improve movement and collision detection for verions 1.21.5+
