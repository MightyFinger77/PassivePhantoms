# PassivePhantoms

A Minecraft Bukkit/Spigot plugin that makes phantoms spawn naturally in the end & are passive until they are attacked by a player or projectile.

## How It Works

1. **Natural Spawning**: Phantoms spawn normally in the end (no overworld spawning)
2. **Passive Behavior**: New phantoms are completely passive and won't target players
3. **Provocation**: When a player attacks a phantom (melee or projectile), it becomes "angry" and can attack back
4. **Projectile Aggression**: Phantoms also become aggressive when hit by arrows, tridents, snowballs, or other projectiles
5. **Immediate Response**: Angry phantoms immediately target and attack the player who hit them
6. **Persistent Anger**: Once a phantom is angry, it remains aggressive, retaining vanilla behaviors

## Installation

1. **Download**: Get the latest JAR file from the releases (v1.2.5)
2. **Install**: Place `PassivePhantoms-1.2.5.jar` in your server's `plugins/` folder
3. **Restart**: Restart your server
4. **Verify**: Check the console for "PassivePhantoms plugin enabled!" message

## Configuration

The plugin generates a `config.yml` file on first run with the following options:

- `debug_logging`: Enable/disable debug messages (default: false)
- `passive_phantoms_enabled`: Enable/disable the plugin (default: true)
- `phantom_settings.custom_spawn_control`: Control phantom spawning (default: true)
- `phantom_settings.end_spawn_chance`: Chance for phantoms to spawn in The End (default: 0.05 = 5%)

## Commands

- `/passivephantoms` - Shows plugin version and usage
- `/passivephantoms reload` - Reloads the configuration
- `/passivephantoms debug` - Toggle debug logging
- `/passivephantoms status` - Check plugin status
- `/passivephantoms list` - List aggressive phantoms

## Permissions

- `passivephantoms.reload` - Allows reloading the configuration (default: op)

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

## License

This project is open source. Feel free to modify and distribute as needed.

## Support

If you encounter any issues or have questions, please create an issue on the GitHub repository. 
