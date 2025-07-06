# PassivePhantoms

A Minecraft Bukkit/Spigot plugin that makes phantoms spawn natrually in the end & are passive until they are attacked by a player.

## How It Works

1. **Natural Spawning**: Phantoms spawn normally in the end
2. **Passive Behavior**: New phantoms are completely passive and won't target players
3. **Provocation**: When a player attacks a phantom, it becomes "angry" and can attack back
4. **Immediate Response**: Angry phantoms immediately target and attack the player who hit them
5. **Persistent Anger**: Once a phantom is angry, it remains aggressive, retaining vanilla behaviors

## Installation

1. **Download**: Get the latest JAR file from the releases
2. **Install**: Place `PassivePhantoms-X.X.X.jar` in your server's `plugins/` folder
3. **Restart**: Restart your server
4. **Verify**: Check the console for "PassivePhantoms plugin enabled!" message

## Configuration

See config.yml after loading plugin once

## Commands

/passivephantoms reload #reloads config

## Permissions

passivephantoms.reload 

## Building from Source

### Prerequisites
- Java 17 or higher
- Maven 3.6 or higher

### Steps
1. Clone this repository
2. Run: `mvn clean package`
3. Find the compiled JAR in the `target/` folder

## Compatibility

- **Minecraft Version**: 1.21.5+
- **Server Type**: Bukkit, Spigot, Paper

## Troubleshooting

**Phantoms not attacking after being hit:**
- Make sure you're using the latest version
- Check server console for any error messages
- Verify the plugin is enabled in the console

**Plugin not loading:**
- Ensure you're using Java 17 or higher
- Check that the JAR file is in the correct plugins folder
- Verify your server version is compatible

## License

This project is open source. Feel free to modify and distribute as needed.

## Support

If you encounter any issues or have questions, please create an issue on the GitHub repository. 
