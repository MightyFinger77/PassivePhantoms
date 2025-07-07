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
import org.bukkit.command.TabCompleter;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Random;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

public class PassivePhantoms extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private boolean debugLogging;
    private boolean passivePhantomsEnabled;
    private boolean customSpawnControl;
    private double endSpawnChance;
    private Random random;
    
    // Simple set to track aggressive phantoms
    private Set<UUID> aggressivePhantoms = new HashSet<>();
    
    // Helper method to add phantom to aggressive set with logging
    private void addAggressivePhantom(UUID phantomId, String reason) {
        if (aggressivePhantoms.add(phantomId)) {
            if (debugLogging) getLogger().info("Added phantom " + phantomId + " to aggressive set: " + reason);
        } else {
            if (debugLogging) getLogger().info("Phantom " + phantomId + " already in aggressive set: " + reason);
        }
    }
    
    // Helper method to remove phantom from aggressive set with logging
    private void removeAggressivePhantom(UUID phantomId, String reason) {
        if (aggressivePhantoms.remove(phantomId)) {
            if (debugLogging) getLogger().info("Removed phantom " + phantomId + " from aggressive set: " + reason);
        } else {
            if (debugLogging) getLogger().info("Phantom " + phantomId + " not found in aggressive set: " + reason);
        }
    }

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
        getCommand("passivephantoms").setTabCompleter(this);
        
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

    @EventHandler(priority = EventPriority.HIGH)
    public void onPhantomTarget(EntityTargetLivingEntityEvent event) {
        if (!passivePhantomsEnabled) {
            if (debugLogging) getLogger().info("Targeting event ignored - plugin disabled");
            return;
        }
        if (!(event.getEntity() instanceof Phantom)) {
            if (debugLogging) getLogger().info("Targeting event not from phantom: " + event.getEntity().getType());
            return;
        }
        
        Phantom phantom = (Phantom) event.getEntity();
        UUID phantomId = phantom.getUniqueId();
        
        if (debugLogging) {
            String targetInfo = event.getTarget() != null ? 
                event.getTarget().getType().toString() + " (" + (event.getTarget() instanceof Player ? ((Player)event.getTarget()).getName() : "non-player") + ")" : 
                "null";
            getLogger().info("Phantom " + phantomId + " targeting event: " + targetInfo + " (aggressive: " + aggressivePhantoms.contains(phantomId) + ")");
        }
        
        // If phantom is aggressive, allow all targeting (including null targets for target loss)
        if (aggressivePhantoms.contains(phantomId)) {
            if (debugLogging) getLogger().info("Allowing targeting - phantom is aggressive");
            
            // If aggressive phantom is losing its target (null), try to re-target the nearest player
            if (event.getTarget() == null) {
                Player nearestPlayer = findNearestPlayer(phantom);
                if (nearestPlayer != null) {
                    // Schedule the re-targeting for next tick to avoid event conflicts
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (phantom.isValid() && !phantom.isDead() && aggressivePhantoms.contains(phantomId)) {
                            phantom.setTarget(nearestPlayer);
                            if (debugLogging) getLogger().info("Re-targeted aggressive phantom " + phantomId + " to " + nearestPlayer.getName());
                        }
                    });
                } else {
                    if (debugLogging) getLogger().info("No nearby players found for aggressive phantom " + phantomId);
                }
            }
            
            return; // Don't interfere with aggressive phantoms
        }
        
        // If phantom is passive, only cancel targeting of players
        if (event.getTarget() instanceof Player) {
            event.setCancelled(true);
            if (debugLogging) getLogger().info("Cancelled targeting - phantom is passive");
        } else {
            if (debugLogging) getLogger().info("Allowing non-player targeting - phantom is passive");
        }
    }
    
    // Helper method to find the nearest player to a phantom
    private Player findNearestPlayer(Phantom phantom) {
        Player nearestPlayer = null;
        double nearestDistance = Double.MAX_VALUE;
        
        for (Player player : phantom.getWorld().getPlayers()) {
            if (player.isValid() && !player.isDead()) {
                double distance = phantom.getLocation().distance(player.getLocation());
                if (distance < nearestDistance && distance <= 64) { // Within 64 blocks
                    nearestDistance = distance;
                    nearestPlayer = player;
                }
            }
        }
        
        return nearestPlayer;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPhantomDamaged(EntityDamageByEntityEvent event) {
        if (!passivePhantomsEnabled) {
            if (debugLogging) getLogger().info("Phantom damage event ignored - plugin disabled");
            return;
        }
        if (!(event.getEntity() instanceof Phantom)) {
            if (debugLogging) getLogger().info("Damage event not from phantom: " + event.getEntity().getType());
            return;
        }
        
        Phantom phantom = (Phantom) event.getEntity();
        UUID phantomId = phantom.getUniqueId();
        
        if (debugLogging) getLogger().info("Phantom " + phantomId + " damaged by: " + event.getDamager().getType());
        
        // Check if the damager is a player or a projectile shot by a player
        final Player damager;
        
        if (event.getDamager() instanceof Player) {
            damager = (Player) event.getDamager();
            if (debugLogging) getLogger().info("Direct player damage from: " + damager.getName());
        } else if (event.getDamager() instanceof Projectile) {
            Projectile projectile = (Projectile) event.getDamager();
            if (projectile.getShooter() instanceof Player) {
                damager = (Player) projectile.getShooter();
                if (debugLogging) getLogger().info("Projectile damage from: " + damager.getName());
            } else {
                if (debugLogging) getLogger().info("Projectile not from player: " + projectile.getShooter());
                return;
            }
        } else {
            if (debugLogging) getLogger().info("Damage not from player or projectile: " + event.getDamager().getType());
            return;
        }
        
        // Make phantom aggressive immediately
        addAggressivePhantom(phantomId, "direct attack from " + damager.getName());
        
        // Force the phantom to target the player immediately
        Bukkit.getScheduler().runTask(this, () -> {
            if (phantom.isValid() && !phantom.isDead()) {
                phantom.setTarget(damager);
                if (debugLogging) getLogger().info("Forced phantom " + phantomId + " to target " + damager.getName());
            }
        });
    }
    


    @EventHandler(priority = EventPriority.HIGH)
    public void onPhantomSpawn(EntitySpawnEvent event) {
        if (!passivePhantomsEnabled) return;
        if (!(event.getEntity() instanceof Phantom)) return;
        
        Phantom phantom = (Phantom) event.getEntity();
        UUID phantomId = phantom.getUniqueId();
        
        // Ensure phantom starts passive
        removeAggressivePhantom(phantomId, "spawned");
        
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
                    removeAggressivePhantom(phantom.getUniqueId(), "spawned");
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
            removeAggressivePhantom(phantomId, "died");
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
            } else if (args.length == 1 && args[0].equalsIgnoreCase("debug")) {
                if (!sender.hasPermission("passivephantoms.reload")) {
                    sender.sendMessage("§cYou don't have permission to use this command!");
                    return true;
                }
                // Toggle debug logging
                debugLogging = !debugLogging;
                getConfig().set("debug_logging", debugLogging);
                saveConfig();
                sender.sendMessage("§aDebug logging " + (debugLogging ? "enabled" : "disabled") + "!");
                return true;
            } else if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
                if (!sender.hasPermission("passivephantoms.reload")) {
                    sender.sendMessage("§cYou don't have permission to use this command!");
                    return true;
                }
                sender.sendMessage("§6PassivePhantoms Status:");
                sender.sendMessage("§7Plugin enabled: §a" + passivePhantomsEnabled);
                sender.sendMessage("§7Debug logging: §a" + debugLogging);
                sender.sendMessage("§7Custom spawn control: §a" + customSpawnControl);
                sender.sendMessage("§7Aggressive phantoms tracked: §a" + aggressivePhantoms.size());
                return true;
            } else if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
                if (!sender.hasPermission("passivephantoms.reload")) {
                    sender.sendMessage("§cYou don't have permission to use this command!");
                    return true;
                }
                if (aggressivePhantoms.isEmpty()) {
                    sender.sendMessage("§7No aggressive phantoms currently tracked.");
                } else {
                    sender.sendMessage("§6Aggressive Phantoms (" + aggressivePhantoms.size() + "):");
                    for (UUID phantomId : aggressivePhantoms) {
                        // Try to find the actual phantom entity
                        Entity entity = Bukkit.getEntity(phantomId);
                        
                        if (entity != null && entity instanceof Phantom) {
                            Phantom phantom = (Phantom) entity;
                            String targetInfo = phantom.getTarget() != null ? 
                                phantom.getTarget().getName() : "none";
                            sender.sendMessage("§7- " + phantomId + " (target: " + targetInfo + ", alive: " + !phantom.isDead() + ")");
                        } else {
                            sender.sendMessage("§7- " + phantomId + " (entity not found - may be dead)");
                        }
                    }
                }
                return true;
            } else if (args.length == 0) {
                sender.sendMessage("§6PassivePhantoms v" + getDescription().getVersion());
                sender.sendMessage("§7Use §f/passivephantoms reload §7to reload the configuration");
                sender.sendMessage("§7Use §f/passivephantoms debug §7to toggle debug logging");
                sender.sendMessage("§7Use §f/passivephantoms status §7to check plugin status");
                sender.sendMessage("§7Use §f/passivephantoms list §7to list aggressive phantoms");
                return true;
            }
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("passivephantoms")) {
            if (args.length == 1) {
                List<String> options = new ArrayList<>();
                String partial = args[0].toLowerCase();
                
                // Add all available commands
                options.add("reload");
                options.add("debug");
                options.add("status");
                options.add("list");
                
                // Filter based on what user has typed
                List<String> filtered = new ArrayList<>();
                for (String option : options) {
                    if (option.startsWith(partial)) {
                        filtered.add(option);
                    }
                }
                
                return filtered;
            }
        }
        return new ArrayList<>();
    }
}
