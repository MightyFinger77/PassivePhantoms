# PassivePhantoms

A Minecraft Bukkit/Spigot plugin that makes phantoms spawn naturally in the end & are passive until they are attacked by a player. Features a per-chunk mobcap system to prevent infinite spawning and movement improvements to help prevent phantoms from getting hung up on chorus fruit trees.

**Note: Movement improvements are experimental and phantoms may still get hung up on chorus trees.**

## How It Works

1. **Natural Spawning**: Phantoms spawn normally in the end (no overworld spawning)
2. **Passive Behavior**: New phantoms are completely passive and won't target players
3. **Provocation**: When a player attacks a phantom (melee or projectile), it becomes "angry" and can attack back
4. **Projectile Aggression**: Phantoms also become aggressive when hit by arrows, tridents, snowballs, or other projectiles
5. **Immediate Response**: Angry phantoms immediately target and attack the player who hit them
6. **Persistent Anger**: Once a phantom is angry, it remains aggressive, retaining vanilla behaviors
7. **Mobcap Protection**: Maximum of 8 phantoms per chunk prevents infinite spawning and ensures fair distribution
8. **Movement Improvements**: Automatic stuck detection and escape assistance prevents phantoms from getting trapped in chorus fruit trees

## Installation

1. **Download**: Get the latest JAR file from the releases (v1.2.5)
2. **Install**: Place `PassivePhantoms-1.2.5.jar` in your server's `plugins/` folder
3. **(Recommended)**: If updating from an older version, delete the entire `PassivePhantoms` config folder and let the plugin generate a new one on next server start. This ensures you get all new config options and avoid legacy issues.
4. **Restart**: Restart your server
5. **Verify**: Check the console for "PassivePhantoms plugin enabled!" message

## Configuration

The plugin generates a `config.yml` file on first run with the following options:

- `debug_logging`: Enable/disable debug messages (default: false)
- `passive_phantoms_enabled`: Enable/disable the plugin (default: true)
- `phantom_settings.custom_spawn_control`: Control phantom spawning (default: true)
- `phantom_settings.end_spawn_chance`: Chance for phantoms to spawn in The End (default: 0.05 = 5%)
- `phantom_settings.max_phantoms_per_chunk`: Maximum phantoms per chunk (default: 8)
- `phantom_settings.movement_improvements_enabled`: Enable movement improvements (default: true)
- `phantom_settings.stuck_detection_ticks`: How often to check for stuck phantoms (default: 100 ticks = 5 seconds)
- `phantom_settings.stuck_threshold`: Consecutive checks before considering stuck (default: 3)
- `phantom_settings.stuck_distance_threshold`: Minimum movement distance (default: 1.0 blocks)
- `phantom_settings.max_stuck_attempts`: Maximum escape attempts before removal (default: 5)

## Commands

- `/passivephantoms` - Shows plugin version and usage
- `/passivephantoms reload` - Reloads the configuration
- `/passivephantoms debug` - Toggle debug logging
- `/passivephantoms status` - Check plugin status and phantom populations
- `/passivephantoms list` - List aggressive phantoms

## Permissions

- `passivephantoms.reload` - Allows reloading the configuration (default: op)

## Mobcap System

The plugin implements a per-chunk mobcap system to prevent infinite phantom spawning:

- **Per-chunk limit**: Configurable maximum phantoms per chunk (default: 8)
- **Fair distribution**: Prevents single players from monopolizing all phantoms
- **Multiple chunks**: Each chunk can independently reach its limit
- **Efficient counting**: Uses chunk coordinates for fast phantom counting
- **Debug monitoring**: Shows chunk status and phantom populations in debug logs
- **Configurable**: Server admins can adjust the limit via `phantom_settings.max_phantoms_per_chunk`
- **Strict enforcement**: The mobcap is strictly enforced; no chunk will ever exceed the configured limit, mirroring vanilla mobcap logic.
- **Verification**: Enable `debug_logging: true` in your config to see log messages whenever the chunk mobcap is reached or enforced.

## Building from Source

### Prerequisites
- Java 8 or higher
- Maven 3.6 or higher

### Steps
1. Clone this repository
2. Run: `mvn clean package`
3. Find the compiled JAR in the `target/` folder

## Compatibility

- **Minecraft Version**: 1.13 - 1.21+
- **Server Type**: Bukkit, Spigot, Paper
- **Java Version**: 8 or higher

## Troubleshooting

**Phantoms not spawning in the end**
- Verify plugin is enabled (true by defualt)
- Check spawn chance setting in config.yml, it is low be default, increase it to verify functionality

**Phantoms not attacking after being hit:**
- Make sure you're using the latest version
- Check server console for any error messages
- Verify the plugin is enabled in the console

**Plugin not loading:**
- Ensure you're using Java 8 or higher
- Check that the JAR file is in the correct plugins folder
- Verify your server version is compatible

**Projectiles not making phantoms aggressive:**
- Ensure you're using version 1.2.5 or higher
- Check that the projectile was shot by a player
- Verify debug logging is enabled to see detailed messages

**Too many phantoms spawning:**
- The mobcap system limits phantoms to 8 per chunk
- Use `/passivephantoms status` to check phantom populations
- Enable debug logging to see mobcap status messages

**Config issues after updating:**
- If you experience errors or missing features after updating the plugin, delete the entire `PassivePhantoms` config folder and let the plugin generate a new one. This ensures all new settings are present and avoids legacy config problems.

**Phantoms getting stuck in chorus fruit trees:**
- The movement improvement system automatically detects and helps stuck phantoms
- Enable `movement_improvements_enabled: true` in your config (default: enabled)
- Use `/passivephantoms status` to check plugin status
- Adjust `stuck_detection_ticks` for more/less frequent checks
- Increase `stuck_threshold` if phantoms are being helped too aggressively

## License

This project is open source. Feel free to modify and distribute as needed.

## Support

If you encounter any issues or have questions, please create an issue on the GitHub repository. 
