import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Random;

public class PassivePhantoms extends JavaPlugin implements Listener, CommandExecutor {

    private boolean debugLogging;
    private boolean passivePhantomsEnabled;
    private boolean customSpawnControl;
    private double endSpawnChance;
    private Random random;
    
    // Simple set to track aggressive phantoms
    private Set<UUID> aggressivePhantoms = new HashSet<>();

    @Override
    public void onEnable() {
        // Save default config if it doesn't exist
        saveDefaultConfig();
        
        // Migrate config to add any new settings
        migrateConfig();
        
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

    private void migrateConfig() {
        FileConfiguration config = getConfig();
        boolean needsSave = false;
        
        // Check and add missing configuration options
        if (!config.contains("debug_logging")) {
            config.set("debug_logging", false);
            needsSave = true;
            if (debugLogging) getLogger().info("Added missing config option: debug_logging");
        }
        
        if (!config.contains("passive_phantoms_enabled")) {
            config.set("passive_phantoms_enabled", true);
            needsSave = true;
            if (debugLogging) getLogger().info("Added missing config option: passive_phantoms_enabled");
        }
        
        if (!config.contains("phantom_settings.custom_spawn_control")) {
            config.set("phantom_settings.custom_spawn_control", true);
            needsSave = true;
            if (debugLogging) getLogger().info("Added missing config option: phantom_settings.custom_spawn_control");
        }
        
        if (!config.contains("phantom_settings.end_spawn_chance")) {
            config.set("phantom_settings.end_spawn_chance", 0.05);
            needsSave = true;
            if (debugLogging) getLogger().info("Added missing config option: phantom_settings.end_spawn_chance");
        }
        
        // Remove deprecated/old config options
        if (config.contains("phantom_settings.target_delay")) {
            config.set("phantom_settings.target_delay", null);
            needsSave = true;
            if (debugLogging) getLogger().info("Removed deprecated config option: phantom_settings.target_delay");
        }
        
        if (config.contains("phantom_settings.permanent_aggression")) {
            config.set("phantom_settings.permanent_aggression", null);
            needsSave = true;
            if (debugLogging) getLogger().info("Removed deprecated config option: phantom_settings.permanent_aggression");
        }
        
        // Save config if changes were made
        if (needsSave) {
            saveConfig();
            if (debugLogging) getLogger().info("Configuration migrated successfully");
        }
        
        // Add behavior information comments if they don't exist
        addBehaviorComments();
    }
    
    private void addBehaviorComments() {
        try {
            java.io.File configFile = new java.io.File(getDataFolder(), "config.yml");
            if (!configFile.exists()) return;
            
            java.nio.file.Path configPath = configFile.toPath();
            String content = new String(java.nio.file.Files.readAllBytes(configPath));
            
            // Check if behavior comments already exist
            if (content.contains("# Behavior Information:")) {
                return; // Comments already exist
            }
            
            // Add behavior comments at the end
            String comments = "\n# Behavior Information:\n" +
                            "# - Phantoms start passive and won't target players\n" +
                            "# - Phantoms become aggressive when hit by players or projectiles (arrows, tridents, etc.)\n" +
                            "# - Once aggressive, phantoms will target and attack players normally\n" +
                            "# - Custom spawn control prevents phantoms from spawning in the Overworld\n" +
                            "# - Phantoms can spawn in The End based on the configured chance";
            
            java.nio.file.Files.write(configPath, (content + comments).getBytes());
            if (debugLogging) getLogger().info("Added behavior information comments to config file");
            
        } catch (Exception e) {
            if (debugLogging) getLogger().warning("Could not add behavior comments to config: " + e.getMessage());
        }
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();
        
        debugLogging = config.getBoolean("debug_logging", false);
        passivePhantomsEnabled = config.getBoolean("passive_phantoms_enabled", true);
        customSpawnControl = config.getBoolean("phantom_settings.custom_spawn_control", true);
        endSpawnChance = config.getDouble("phantom_settings.end_spawn_chance", 0.05);
        random = new Random();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPhantomTarget(EntityTargetLivingEntityEvent event) {
        if (!passivePhantomsEnabled) return;
        if (!(event.getEntity() instanceof Phantom)) return;
        if (!(event.getTarget() instanceof Player)) return;
        
        Phantom phantom = (Phantom) event.getEntity();
        UUID phantomId = phantom.getUniqueId();
        
        // If phantom is not aggressive, cancel targeting
        if (!aggressivePhantoms.contains(phantomId)) {
            event.setCancelled(true);
            if (debugLogging) getLogger().info("Cancelled targeting - phantom is passive");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPhantomDamaged(EntityDamageByEntityEvent event) {
        if (!passivePhantomsEnabled) return;
        if (!(event.getEntity() instanceof Phantom)) return;
        
        // Check if the damager is a player or a projectile shot by a player
        Player damager = null;
        
        if (event.getDamager() instanceof Player) {
            damager = (Player) event.getDamager();
        } else if (event.getDamager() instanceof Projectile) {
            Projectile projectile = (Projectile) event.getDamager();
            if (projectile.getShooter() instanceof Player) {
                damager = (Player) projectile.getShooter();
            }
        }
        
        if (damager == null) return;
        
        Phantom phantom = (Phantom) event.getEntity();
        UUID phantomId = phantom.getUniqueId();
        
        // Make phantom aggressive
        aggressivePhantoms.add(phantomId);
        if (debugLogging) {
            if (event.getDamager() instanceof Projectile) {
                getLogger().info("Phantom made aggressive by projectile from " + damager.getName());
            } else {
                getLogger().info("Phantom made aggressive by direct attack from " + damager.getName());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPhantomSpawn(EntitySpawnEvent event) {
        if (!passivePhantomsEnabled) return;
        if (!(event.getEntity() instanceof Phantom)) return;
        
        Phantom phantom = (Phantom) event.getEntity();
        UUID phantomId = phantom.getUniqueId();
        
        // Ensure phantom starts passive
        aggressivePhantoms.remove(phantomId);
        
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
            UUID phantomId = event.getEntity().getUniqueId();
            aggressivePhantoms.remove(phantomId);
            if (debugLogging) getLogger().info("Phantom died - removed from tracking");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("passivephantoms")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("passivephantoms.reload")) {
                    sender.sendMessage("§cYou don't have permission to use this command!");
                    return true;
                }
                reloadConfig();
                migrateConfig(); // Run migration again after reload
                loadConfig();
                sender.sendMessage("§aPassivePhantoms configuration reloaded!");
                return true;
            } else if (args.length == 0) {
                sender.sendMessage("§6PassivePhantoms v" + getDescription().getVersion());
                sender.sendMessage("§7Use §f/passivephantoms reload §7to reload the configuration");
                return true;
            }
        }
        return false;
    }
} 
