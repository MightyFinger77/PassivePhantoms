import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Random;

public class PassivePhantoms extends JavaPlugin implements Listener, CommandExecutor {

    private boolean debugLogging;
    private boolean passivePhantomsEnabled;
    private int targetDelay;
    private boolean permanentAggression;
    private boolean customSpawnControl;
    private double endSpawnChance;
    private Random random;
    
    // Track aggressive phantoms and their targets
    private Map<UUID, UUID> aggressivePhantoms = new HashMap<>();

    @Override
    public void onEnable() {
        // Save default config if it doesn't exist
        saveDefaultConfig();
        
        // Update config with any new settings while preserving existing ones
        updateConfig();
        
        // Load configuration
        loadConfig();
        
        // Register events
        Bukkit.getPluginManager().registerEvents(this, this);
        
        // Register commands
        getCommand("passivephantoms").setExecutor(this);
        
        // Start custom phantom spawning in The End if enabled
        if (passivePhantomsEnabled && customSpawnControl) {
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                spawnPhantomsInEnd();
            }, 200L, 200L); // Check every 10 seconds (200 ticks)
        }
        
        getLogger().info("PassivePhantoms plugin enabled!");
        if (debugLogging) {
            getLogger().info("Debug logging: ENABLED");
            getLogger().info("Passive phantoms: " + (passivePhantomsEnabled ? "ENABLED" : "DISABLED"));
            if (customSpawnControl) {
                getLogger().info("Custom spawn control: ENABLED");
                getLogger().info("End spawn chance: " + (endSpawnChance * 100) + "%");
            }
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("PassivePhantoms plugin disabled!");
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();
        
        debugLogging = config.getBoolean("debug_logging", false);
        passivePhantomsEnabled = config.getBoolean("passive_phantoms_enabled", true);
        targetDelay = config.getInt("phantom_settings.target_delay", 5);
        permanentAggression = config.getBoolean("phantom_settings.permanent_aggression", true);
        customSpawnControl = config.getBoolean("phantom_settings.custom_spawn_control", true);
        endSpawnChance = config.getDouble("phantom_settings.end_spawn_chance", 0.05);
        random = new Random();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPhantomTarget(EntityTargetLivingEntityEvent event) {
        if (!passivePhantomsEnabled) return;
        if (!(event.getEntity() instanceof Phantom)) return;
        if (!(event.getTarget() instanceof Player)) return;
        Phantom phantom = (Phantom) event.getEntity();
        Player target = (Player) event.getTarget();
        
        // Check if this phantom is aggressive towards this specific player
        UUID phantomId = phantom.getUniqueId();
        UUID targetId = target.getUniqueId();
        
        if (aggressivePhantoms.containsKey(phantomId) && aggressivePhantoms.get(phantomId).equals(targetId)) {
            // Phantom is aggressive towards this player, allow targeting
            if (debugLogging) getLogger().info("Phantom targeting allowed - phantom is aggressive towards " + target.getName());
        } else {
            // Phantom is not aggressive towards this player, cancel targeting
            event.setCancelled(true);
            if (debugLogging) getLogger().info("Cancelled phantom targeting - phantom is passive towards " + target.getName());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPhantomDamaged(EntityDamageByEntityEvent event) {
        if (!passivePhantomsEnabled) return;
        if (!(event.getEntity() instanceof Phantom)) return;
        if (!(event.getDamager() instanceof Player)) return;
        Phantom phantom = (Phantom) event.getEntity();
        Player player = (Player) event.getDamager();
        
        UUID phantomId = phantom.getUniqueId();
        UUID playerId = player.getUniqueId();
        
        // Make phantom aggressive towards the player who damaged it
        if (!aggressivePhantoms.containsKey(phantomId) || !aggressivePhantoms.get(phantomId).equals(playerId)) {
            aggressivePhantoms.put(phantomId, playerId);
            if (debugLogging) getLogger().info("Phantom made aggressive towards " + player.getName());
            // Set the target ONCE to trigger vanilla attack cycles
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (phantom.isValid() && !phantom.isDead() && player.isValid() && !player.isDead()) {
                    phantom.setTarget(player);
                    if (debugLogging) getLogger().info("Set target for aggressive phantom: " + player.getName());
                }
            }, 2L); // Small delay to let the damage event complete
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPhantomSpawn(EntitySpawnEvent event) {
        if (!passivePhantomsEnabled) return;
        if (!(event.getEntity() instanceof Phantom)) return;
        Phantom phantom = (Phantom) event.getEntity();
        
        // Remove any existing aggression tracking for this phantom
        aggressivePhantoms.remove(phantom.getUniqueId());
        
        if (debugLogging) getLogger().info("New phantom spawned - set to passive");
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!passivePhantomsEnabled || !customSpawnControl) return;
        if (!(event.getEntity() instanceof Phantom)) return;
        
        World world = event.getLocation().getWorld();
        if (world == null) return;
        
        // Cancel phantom spawning in the Overworld
        if (world.getEnvironment() == World.Environment.NORMAL) {
            event.setCancelled(true);
            if (debugLogging) getLogger().info("Cancelled phantom spawn in Overworld");
        }
    }
    
    // Custom phantom spawning in The End
    public void spawnPhantomsInEnd() {
        if (!passivePhantomsEnabled || !customSpawnControl) return;
        
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.THE_END) continue;
            
            // Check if there are players in The End
            if (world.getPlayers().isEmpty()) continue;
            
            // Random chance to spawn phantoms
            if (random.nextDouble() < endSpawnChance) {
                // Find a random player in The End
                Player targetPlayer = world.getPlayers().get(random.nextInt(world.getPlayers().size()));
                Location playerLoc = targetPlayer.getLocation();
                
                // Spawn phantom near the player (but not too close)
                double x = playerLoc.getX() + (random.nextDouble() - 0.5) * 40; // ±20 blocks
                double z = playerLoc.getZ() + (random.nextDouble() - 0.5) * 40; // ±20 blocks
                double y = world.getHighestBlockYAt((int)x, (int)z) + 10; // 10 blocks above ground
                
                Location spawnLoc = new Location(world, x, y, z);
                
                // Spawn the phantom
                Phantom phantom = world.spawn(spawnLoc, Phantom.class);
                if (phantom != null) {
                    // Set to passive initially
                    aggressivePhantoms.remove(phantom.getUniqueId());
                    if (debugLogging) getLogger().info("Spawned phantom in The End at " + spawnLoc);
                }
            }
        }
    }
    
    // Clean up tracking when phantoms die
    @EventHandler
    public void onPhantomDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        if (event.getEntity() instanceof Phantom) {
            aggressivePhantoms.remove(event.getEntity().getUniqueId());
            if (debugLogging) getLogger().info("Phantom died - removed from aggression tracking");
        }
    }
    
    private void updateConfig() {
        FileConfiguration config = getConfig();
        FileConfiguration defaultConfig = getDefaultConfig();
        boolean needsSave = false;
        
        // Check and add missing settings
        if (!config.contains("phantom_settings.custom_spawn_control")) {
            config.set("phantom_settings.custom_spawn_control", defaultConfig.getBoolean("phantom_settings.custom_spawn_control", true));
            needsSave = true;
            if (debugLogging) getLogger().info("Added missing config: phantom_settings.custom_spawn_control");
        }
        
        if (!config.contains("phantom_settings.end_spawn_chance")) {
            config.set("phantom_settings.end_spawn_chance", defaultConfig.getDouble("phantom_settings.end_spawn_chance", 0.05));
            needsSave = true;
            if (debugLogging) getLogger().info("Added missing config: phantom_settings.end_spawn_chance");
        }
        
        // Save if any new settings were added
        if (needsSave) {
            try {
                saveConfig();
                getLogger().info("Config file updated with new settings!");
            } catch (Exception e) {
                getLogger().warning("Failed to save updated config: " + e.getMessage());
            }
        }
    }
    
    private FileConfiguration getDefaultConfig() {
        // Create a temporary config from the default resource
        FileConfiguration defaultConfig = new org.bukkit.configuration.file.YamlConfiguration();
        try {
            java.io.InputStream inputStream = getResource("config.yml");
            if (inputStream != null) {
                defaultConfig.loadFromString(new String(inputStream.readAllBytes()));
            }
        } catch (Exception e) {
            getLogger().warning("Failed to load default config: " + e.getMessage());
        }
        return defaultConfig;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("passivephantoms")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                loadConfig();
                updateConfig();
                sender.sendMessage("PassivePhantoms configuration reloaded!");
                return true;
            }
        }
        return false;
    }
} 