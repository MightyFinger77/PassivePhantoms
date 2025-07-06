# Changelog

## [1.2.4] - 2025-01-27

### Added
- **Projectile Aggression**: Phantoms now become aggressive when hit by projectiles (arrows, tridents, snowballs, etc.) from players
- Enhanced command feedback with colored messages
- Permission system for the reload command
- Better debug logging for projectile attacks

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

## [1.1.6] - 2025-07-06

### Removed
- Unused configuration settings

## [1.1.5] - 2025-07-06

### Fixed
- Phantom targeting when provoked

## [1.1.4] - 2025-07-06

### Fixed
- Removed AI interference, restored vanilla flight behavior

## [1.1.3] - 2025-07-06

### Fixed
- Added back essential targeting for aggressive phantoms

## [1.1.2] - 2025-07-06

### Fixed
- Simplified approach, removed excessive retargeting

## [1.1.1] - 2025-07-06

### Fixed
- Aggression persistence during combat

## [1.1.0] - 2025-07-06

### Changed
- Complete redesign with UUID-based tracking
- Natural attack cycles preserved

## [1.0.8] - 2025-07-06

### Added
- Enhanced debug logging and tag persistence

## [1.0.7] - 2025-07-06

### Fixed
- Aggression persistence after attacks

## [1.0.6] - 2025-07-06

### Changed
- Removed retargeting when phantoms hit players

## [1.0.5] - 2025-07-06

### Changed
- Combat-based retargeting only

## [1.0.4] - 2025-07-06

### Changed
- Reduced retargeting frequency for natural flight

## [1.0.3] - 2025-07-06

### Changed
- Continuous retargeting for persistent aggression

## [1.0.1] - 2025-07-05

### Fixed
- Phantom targeting and immediate response

## [1.0.0] - 2025-07-05

### Added
- Initial release with basic passive phantom functionality

## Version History

- **1.0.1**: Fixed targeting issues, improved event handling
- **1.0.0**: Initial release with basic functionality

## Planned Features

- [x] Commands to toggle plugin on/off
- [x] Add Projectile-aggression triggering
- [ ] Per-world configuration options
- [ ] Phantom size customization
