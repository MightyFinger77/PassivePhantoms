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
        
        debugLogging = config.getBoolean("debug_logging", true);
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
        if (!(event.getDamager() instanceof Player)) return;
        
        Phantom phantom = (Phantom) event.getEntity();
        UUID phantomId = phantom.getUniqueId();
        
        // Make phantom aggressive
        aggressivePhantoms.add(phantomId);
        if (debugLogging) getLogger().info("Phantom made aggressive");
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
                reloadConfig();
                loadConfig();
                sender.sendMessage("PassivePhantoms configuration reloaded!");
                return true;
            }
        }
        return false;
    }
} 
