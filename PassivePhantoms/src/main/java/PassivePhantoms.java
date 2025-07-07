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
import org.bukkit.Material;
import org.bukkit.block.Block;
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
import java.util.HashMap;
import java.util.Map;

public class PassivePhantoms extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private boolean debugLogging;
    private boolean passivePhantomsEnabled;
    private boolean customSpawnControl;
    private double endSpawnChance;
    private int maxPhantomsPerChunk;
    private Random random;
    
    // Simple set to track aggressive phantoms
    private Set<UUID> aggressivePhantoms = new HashSet<>();
    
    // Movement tracking for stuck detection
    private Map<UUID, Location> lastPhantomLocations = new HashMap<>();
    private Map<UUID, Integer> stuckCounter = new HashMap<>();
    private Map<UUID, Long> lastMovementTime = new HashMap<>();
    private Map<UUID, Integer> stuckAttempts = new HashMap<>();
    private Map<UUID, Long> lastTreeAvoidanceTime = new HashMap<>();
    
    // Configuration for movement improvements
    private boolean movementImprovementsEnabled;
    private int stuckDetectionTicks;
    private int stuckThreshold;
    private double stuckDistanceThreshold;
    private int maxStuckAttempts;
    private boolean treeAvoidanceEnabled;
    private double treeAvoidanceRadius;
    
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
    
    // Helper method to count phantoms in a specific chunk
    private int countPhantomsInChunk(World world, int chunkX, int chunkZ) {
        int count = 0;
        for (Entity entity : world.getEntities()) {
            if (entity instanceof Phantom) {
                Location loc = entity.getLocation();
                if (loc.getBlockX() >> 4 == chunkX && loc.getBlockZ() >> 4 == chunkZ) {
                    count++;
                }
            }
        }
        return count;
    }
    
    // Optimized method to check if a location is near chorus fruit
    private boolean isNearChorusFruit(Location location) {
        int radius = (int) treeAvoidanceRadius;
        World world = location.getWorld();
        if (world == null) return false;
        
        // Use squared distance for better performance (avoid square root)
        int radiusSquared = radius * radius;
        
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    // Skip if outside the sphere (performance optimization)
                    if (x * x + y * y + z * z > radiusSquared) continue;
                    
                    Block block = world.getBlockAt(
                        location.getBlockX() + x,
                        location.getBlockY() + y,
                        location.getBlockZ() + z
                    );
                    
                    Material type = block.getType();
                    if (type == Material.CHORUS_PLANT || type == Material.CHORUS_FLOWER) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    // Helper method to find a safe spawn location away from chorus fruit
    private Location findSafeSpawnLocation(World world, int chunkX, int chunkZ) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int attempts = 0;
        int maxAttempts = 20;
        
        while (attempts < maxAttempts) {
            double x = baseX + random.nextInt(16) + 0.5;
            double z = baseZ + random.nextInt(16) + 0.5;
            double y = world.getHighestBlockYAt((int)x, (int)z) + 10;
            Location testLoc = new Location(world, x, y, z);
            
            if (!isNearChorusFruit(testLoc)) {
                return testLoc;
            }
            attempts++;
        }
        
        // If no safe location found, return a random one anyway
        double x = baseX + random.nextInt(16) + 0.5;
        double z = baseZ + random.nextInt(16) + 0.5;
        double y = world.getHighestBlockYAt((int)x, (int)z) + 10;
        return new Location(world, x, y, z);
    }
    
    // Helper method to help stuck phantoms escape
    private void helpPhantomEscape(Phantom phantom) {
        if (!phantom.isValid() || phantom.isDead()) return;
        
        Location currentLoc = phantom.getLocation();
        UUID phantomId = phantom.getUniqueId();
        
        // Try to find a clear path upward or to the side
        Location escapeLoc = findEscapeLocation(currentLoc);
        if (escapeLoc != null) {
            // Teleport the phantom to the escape location
            phantom.teleport(escapeLoc);
            if (debugLogging) getLogger().info("Helped stuck phantom " + phantomId + " escape to " + escapeLoc);
            
            // Reset stuck counter
            stuckCounter.put(phantomId, 0);
            lastMovementTime.put(phantomId, System.currentTimeMillis());
        } else {
            // If no escape location found, try to move the phantom up
            Location upLoc = currentLoc.clone().add(0, 5, 0);
            phantom.teleport(upLoc);
            if (debugLogging) getLogger().info("Moved stuck phantom " + phantomId + " upward");
        }
    }
    
    // Helper method to find an escape location for stuck phantoms
    private Location findEscapeLocation(Location currentLoc) {
        World world = currentLoc.getWorld();
        if (world == null) return null;
        
        // Try different directions and heights
        Location[] escapeAttempts = {
            currentLoc.clone().add(0, 8, 0),   // Straight up
            currentLoc.clone().add(5, 3, 0),   // North and up
            currentLoc.clone().add(-5, 3, 0),  // South and up
            currentLoc.clone().add(0, 3, 5),   // East and up
            currentLoc.clone().add(0, 3, -5),  // West and up
            currentLoc.clone().add(8, 0, 0),   // North
            currentLoc.clone().add(-8, 0, 0),  // South
            currentLoc.clone().add(0, 0, 8),   // East
            currentLoc.clone().add(0, 0, -8)   // West
        };
        
        for (Location attempt : escapeAttempts) {
            if (isLocationSafe(attempt)) {
                return attempt;
            }
        }
        
        return null;
    }
    
    // Helper method to check if a location is safe for phantoms
    private boolean isLocationSafe(Location location) {
        if (location.getWorld() == null) return false;
        
        // Check if the location is not inside blocks
        Block block = location.getBlock();
        if (block.getType() != Material.AIR && !block.getType().isSolid()) {
            return false;
        }
        
        // Check if there's enough space above
        for (int y = 1; y <= 3; y++) {
            Block above = location.clone().add(0, y, 0).getBlock();
            if (above.getType() != Material.AIR && !above.getType().isSolid()) {
                return false;
            }
        }
        
        // Check if not too close to chorus fruit
        return !isNearChorusFruit(location);
    }
    
    // Helper method to guide phantoms to fly around trees naturally
    private void guidePhantomAroundTrees(Phantom phantom) {
        if (!phantom.isValid() || phantom.isDead()) return;
        
        Location currentLoc = phantom.getLocation();
        UUID phantomId = phantom.getUniqueId();
        
        // Find the best direction to fly around the tree
        Location guidanceLoc = findFlightGuidanceLocation(currentLoc);
        if (guidanceLoc != null) {
            // Set the phantom's velocity to guide it around the tree
            // This creates a gentle push in the right direction
            double dx = guidanceLoc.getX() - currentLoc.getX();
            double dy = guidanceLoc.getY() - currentLoc.getY();
            double dz = guidanceLoc.getZ() - currentLoc.getZ();
            
            // Normalize and scale the velocity for gentle guidance
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > 0) {
                double speed = 0.3; // Gentle speed
                phantom.setVelocity(new org.bukkit.util.Vector(
                    (dx / distance) * speed,
                    (dy / distance) * speed,
                    (dz / distance) * speed
                ));
                
                if (debugLogging) getLogger().info("Guided phantom " + phantomId + " around trees with velocity");
            }
        }
    }
    
    // Helper method to find a flight guidance location around trees
    private Location findFlightGuidanceLocation(Location currentLoc) {
        World world = currentLoc.getWorld();
        if (world == null) return null;
        
        // Find the nearest chorus fruit to determine which direction to guide
        Location nearestTree = findNearestChorusFruit(currentLoc);
        if (nearestTree == null) return null;
        
        // Calculate direction away from the tree
        double dx = currentLoc.getX() - nearestTree.getX();
        double dy = currentLoc.getY() - nearestTree.getY();
        double dz = currentLoc.getZ() - nearestTree.getZ();
        
        // Normalize the direction
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance == 0) return null;
        
        // Create a guidance point slightly away from the tree
        double guidanceDistance = 3.0;
        return new Location(world,
            currentLoc.getX() + (dx / distance) * guidanceDistance,
            currentLoc.getY() + (dy / distance) * guidanceDistance,
            currentLoc.getZ() + (dz / distance) * guidanceDistance
        );
    }
    
    // Helper method to find the nearest chorus fruit
    private Location findNearestChorusFruit(Location location) {
        int radius = (int) treeAvoidanceRadius;
        World world = location.getWorld();
        if (world == null) return null;
        
        Location nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    // Skip if outside the sphere (performance optimization)
                    if (x * x + y * y + z * z > radius * radius) continue;
                    
                    Block block = world.getBlockAt(
                        location.getBlockX() + x,
                        location.getBlockY() + y,
                        location.getBlockZ() + z
                    );
                    
                    Material type = block.getType();
                    if (type == Material.CHORUS_PLANT || type == Material.CHORUS_FLOWER) {
                        Location treeLoc = block.getLocation().add(0.5, 0.5, 0.5);
                        double distance = location.distance(treeLoc);
                        if (distance < nearestDistance) {
                            nearestDistance = distance;
                            nearest = treeLoc;
                        }
                    }
                }
            }
        }
        
        return nearest;
    }
    
    // Optimized method to monitor phantom movement, stuck detection, and tree avoidance
    private void monitorPhantomMovement() {
        if (!movementImprovementsEnabled) return;
        
        // Cache current time for performance
        long currentTime = System.currentTimeMillis();
        
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.THE_END) continue;
            
            // Get all entities once and filter efficiently
            List<Entity> entities = world.getEntities();
            if (entities.isEmpty()) continue;
            
            for (Entity entity : entities) {
                if (!(entity instanceof Phantom)) continue;
                
                Phantom phantom = (Phantom) entity;
                UUID phantomId = phantom.getUniqueId();
                
                // Skip if phantom is dead or invalid (early return for performance)
                if (!phantom.isValid() || phantom.isDead()) {
                    cleanupPhantomData(phantomId);
                    continue;
                }
                
                Location currentLoc = phantom.getLocation();
                
                // Handle stuck detection
                handleStuckDetection(phantom, phantomId, currentLoc, currentTime);
                
                // Handle tree avoidance (only if enabled and phantom is near trees, with cooldown)
                if (treeAvoidanceEnabled && isNearChorusFruit(currentLoc)) {
                    long lastAvoidance = lastTreeAvoidanceTime.getOrDefault(phantomId, 0L);
                    if (currentTime - lastAvoidance > 2000) { // 2 second cooldown
                        guidePhantomAroundTrees(phantom);
                        lastTreeAvoidanceTime.put(phantomId, currentTime);
                    }
                }
                
                // Update last location
                lastPhantomLocations.put(phantomId, currentLoc);
            }
        }
    }
    
    // Helper method to handle stuck detection logic
    private void handleStuckDetection(Phantom phantom, UUID phantomId, Location currentLoc, long currentTime) {
        Location lastLoc = lastPhantomLocations.get(phantomId);
        if (lastLoc == null) return;
        
        double distance = lastLoc.distance(currentLoc);
        
        if (distance < stuckDistanceThreshold) {
            // Phantom hasn't moved much, increment stuck counter
            int stuckCount = stuckCounter.getOrDefault(phantomId, 0) + 1;
            stuckCounter.put(phantomId, stuckCount);
            
            if (stuckCount >= stuckThreshold) {
                // Phantom is stuck, try to help it escape
                int attempts = stuckAttempts.getOrDefault(phantomId, 0);
                if (attempts < maxStuckAttempts) {
                    helpPhantomEscape(phantom);
                    stuckAttempts.put(phantomId, attempts + 1);
                } else {
                    // Too many attempts, remove the phantom
                    if (debugLogging) getLogger().info("Removing permanently stuck phantom " + phantomId);
                    phantom.remove();
                    cleanupPhantomData(phantomId);
                }
            }
        } else {
            // Phantom has moved, reset counters
            stuckCounter.put(phantomId, 0);
            stuckAttempts.put(phantomId, 0);
            lastMovementTime.put(phantomId, currentTime);
        }
    }
    
    // Helper method to clean up phantom data
    private void cleanupPhantomData(UUID phantomId) {
        lastPhantomLocations.remove(phantomId);
        stuckCounter.remove(phantomId);
        stuckAttempts.remove(phantomId);
        lastMovementTime.remove(phantomId);
        lastTreeAvoidanceTime.remove(phantomId);
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
        
        // Start optimized movement monitoring if enabled
        if (passivePhantomsEnabled && movementImprovementsEnabled) {
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                monitorPhantomMovement();
            }, stuckDetectionTicks, stuckDetectionTicks);
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
        
        if (!config.contains("phantom_settings.max_phantoms_per_chunk")) {
            config.set("phantom_settings.max_phantoms_per_chunk", 8);
            needsSave = true;
            if (debugLogging) getLogger().info("Added missing config option: phantom_settings.max_phantoms_per_chunk");
        }
        
        // Add movement improvement settings
        if (!config.contains("phantom_settings.movement_improvements_enabled")) {
            config.set("phantom_settings.movement_improvements_enabled", true);
            needsSave = true;
            if (debugLogging) getLogger().info("Added missing config option: phantom_settings.movement_improvements_enabled");
        }
        
        if (!config.contains("phantom_settings.stuck_detection_ticks")) {
            config.set("phantom_settings.stuck_detection_ticks", 100);
            needsSave = true;
            if (debugLogging) getLogger().info("Added missing config option: phantom_settings.stuck_detection_ticks");
        }
        
        if (!config.contains("phantom_settings.stuck_threshold")) {
            config.set("phantom_settings.stuck_threshold", 3);
            needsSave = true;
            if (debugLogging) getLogger().info("Added missing config option: phantom_settings.stuck_threshold");
        }
        
        if (!config.contains("phantom_settings.stuck_distance_threshold")) {
            config.set("phantom_settings.stuck_distance_threshold", 1.0);
            needsSave = true;
            if (debugLogging) getLogger().info("Added missing config option: phantom_settings.stuck_distance_threshold");
        }
        
        if (!config.contains("phantom_settings.max_stuck_attempts")) {
            config.set("phantom_settings.max_stuck_attempts", 5);
            needsSave = true;
            if (debugLogging) getLogger().info("Added missing config option: phantom_settings.max_stuck_attempts");
        }
        
        // Add tree avoidance settings
        if (!config.contains("phantom_settings.tree_avoidance_enabled")) {
            config.set("phantom_settings.tree_avoidance_enabled", true);
            needsSave = true;
            if (debugLogging) getLogger().info("Added missing config option: phantom_settings.tree_avoidance_enabled");
        }
        
        if (!config.contains("phantom_settings.tree_avoidance_radius")) {
            config.set("phantom_settings.tree_avoidance_radius", 3.0);
            needsSave = true;
            if (debugLogging) getLogger().info("Added missing config option: phantom_settings.tree_avoidance_radius");
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
                            "# - Phantoms can spawn in The End based on the configured chance\n" +
                            "# - Movement improvements help phantoms avoid getting stuck and fly around chorus fruit trees naturally";
            
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
        maxPhantomsPerChunk = config.getInt("phantom_settings.max_phantoms_per_chunk", 8);
        
        // Movement improvement settings
        movementImprovementsEnabled = config.getBoolean("phantom_settings.movement_improvements_enabled", true);
        stuckDetectionTicks = config.getInt("phantom_settings.stuck_detection_ticks", 100);
        stuckThreshold = config.getInt("phantom_settings.stuck_threshold", 3);
        stuckDistanceThreshold = config.getDouble("phantom_settings.stuck_distance_threshold", 1.0);
        maxStuckAttempts = config.getInt("phantom_settings.max_stuck_attempts", 5);
        
        // Tree avoidance settings
        treeAvoidanceEnabled = config.getBoolean("phantom_settings.tree_avoidance_enabled", true);
        treeAvoidanceRadius = config.getDouble("phantom_settings.tree_avoidance_radius", 3.0);
        
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
            
            // Attempt to spawn for every player in The End
            for (Player targetPlayer : world.getPlayers()) {
                if (random.nextDouble() < endSpawnChance) {
                    Location playerLoc = targetPlayer.getLocation();
                    int chunkX = playerLoc.getBlockX() >> 4;
                    int chunkZ = playerLoc.getBlockZ() >> 4;
                    int currentPhantoms = countPhantomsInChunk(world, chunkX, chunkZ);
                    if (currentPhantoms >= maxPhantomsPerChunk) {
                        if (debugLogging) getLogger().info("Chunk mobcap reached at " + chunkX + "," + chunkZ + " in " + world.getName() + " (" + currentPhantoms + "/" + maxPhantomsPerChunk + " phantoms)");
                        continue;
                    }
                    // Spawn phantom at a safe location away from chorus fruit
                    Location spawnLoc = findSafeSpawnLocation(world, chunkX, chunkZ);
                    Phantom phantom = world.spawn(spawnLoc, Phantom.class);
                    if (phantom != null) {
                        removeAggressivePhantom(phantom.getUniqueId(), "spawned");
                        if (debugLogging) getLogger().info("Spawned phantom in The End at " + spawnLoc + " (chunk " + chunkX + "," + chunkZ + ": " + (currentPhantoms + 1) + "/" + maxPhantomsPerChunk + " phantoms)");
                    }
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
            
            // Clean up movement tracking
            lastPhantomLocations.remove(phantomId);
            stuckCounter.remove(phantomId);
            stuckAttempts.remove(phantomId);
            lastMovementTime.remove(phantomId);
            
            if (debugLogging) getLogger().info("Phantom died - removed from all tracking");
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
                sender.sendMessage("§7Movement improvements: §a" + movementImprovementsEnabled);
                sender.sendMessage("§7Aggressive phantoms tracked: §a" + aggressivePhantoms.size());
                sender.sendMessage("§7Max phantoms per chunk: §a" + maxPhantomsPerChunk);
                if (movementImprovementsEnabled) {
                    sender.sendMessage("§7Movement monitoring: §aEnabled (every " + stuckDetectionTicks + " ticks)");
                }
                if (treeAvoidanceEnabled) {
                    sender.sendMessage("§7Tree avoidance: §aEnabled (integrated with movement monitoring)");
                }
                
                // Show phantom counts per End world (total)
                sender.sendMessage("§6Phantom Populations:");
                for (World world : Bukkit.getWorlds()) {
                    if (world.getEnvironment() == World.Environment.THE_END) {
                        int totalPhantoms = 0;
                        for (Entity entity : world.getEntities()) {
                            if (entity instanceof Phantom) {
                                totalPhantoms++;
                            }
                        }
                        sender.sendMessage("§7" + world.getName() + ": §a" + totalPhantoms + " total phantoms");
                    }
                }
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
