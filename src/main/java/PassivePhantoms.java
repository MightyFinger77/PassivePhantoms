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
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Random;
import java.util.Collection;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.Locale;
import java.util.function.Consumer;

public class PassivePhantoms extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    /** Config version in default config.yml; bump when adding/removing options so user config is merged. */
    private static final int CONFIG_VERSION = 2;
    /** Modrinth API v2: GET /project/{id|slug}/version returns JSON array; first item has version_number. */
    private static final String MODRINTH_VERSION_URL = "https://api.modrinth.com/v2/project/%s/version";
    private static final String MODRINTH_PROJECT_ID = "passivephantoms";

    private boolean debugLogging;
    private boolean passivePhantomsEnabled;
    private boolean customSpawnControl;
    private double endSpawnChance;
    private int maxPhantomsPerChunk;
    /** Radius (blocks) around player to count phantoms for cap; prevents flying phantoms from bypassing by leaving chunk. */
    private double spawnCheckRadius;
    /** Ticks between spawn rolls in The End (200 = 10 seconds). Only applied on reload/restart. */
    private long endSpawnIntervalTicks;
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
    
    // Modrinth update checker
    private boolean updateCheckerEnabled;
    private volatile String latestVersion;
    private volatile boolean updateAvailable = false;

    /** Folia only — do not use {@code getGlobalRegionScheduler()} for detection; Paper exposes it too. */
    private boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private void runSync(Runnable task) {
        if (isFolia()) {
            try {
                Object scheduler = getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(getServer());
                scheduler.getClass().getMethod("execute", Plugin.class, Runnable.class).invoke(scheduler, this, task);
                return;
            } catch (Exception ignored) {
            }
        }
        Bukkit.getScheduler().runTask(this, task);
    }

    private void runLater(Runnable task, long delayTicks) {
        if (isFolia()) {
            try {
                Object scheduler = getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(getServer());
                scheduler.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, long.class)
                        .invoke(scheduler, this, (Consumer<Object>) t -> task.run(), delayTicks);
                return;
            } catch (Exception ignored) {
            }
        }
        Bukkit.getScheduler().runTaskLater(this, task, delayTicks);
    }

    private void runTimer(Runnable task, long delayTicks, long periodTicks) {
        if (isFolia()) {
            try {
                Object scheduler = getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(getServer());
                scheduler.getClass().getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class)
                        .invoke(scheduler, this, (Consumer<Object>) t -> task.run(), delayTicks, periodTicks);
                return;
            } catch (Exception ignored) {
            }
        }
        Bukkit.getScheduler().runTaskTimer(this, task, delayTicks, periodTicks);
    }

    @SuppressWarnings("unchecked")
    private void runAsync(Runnable task) {
        if (isFolia()) {
            try {
                Object async = getServer().getClass().getMethod("getAsyncScheduler").invoke(getServer());
                async.getClass().getMethod("runNow", Plugin.class, Consumer.class)
                        .invoke(async, this, (Consumer<Object>) st -> task.run());
                return;
            } catch (Exception ignored) { }
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, task);
    }
    
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
    
    /** Count phantoms in one chunk. Uses getNearbyEntities for the chunk column to avoid iterating the whole world. */
    private int countPhantomsInChunk(World world, int chunkX, int chunkZ) {
        Location chunkCenter = new Location(world, (chunkX << 4) + 8, 64, (chunkZ << 4) + 8);
        Collection<Entity> inChunk = world.getNearbyEntities(chunkCenter, 8, 128, 8);
        int count = 0;
        for (Entity entity : inChunk) {
            if (entity instanceof Phantom) count++;
        }
        return count;
    }
    
    /** Count phantoms within radius of a location. Uses getNearbyEntities so we only scan a box, not the whole world. */
    private int countPhantomsNear(World world, Location center, double radiusBlocks) {
        if (center == null || world == null) return 0;
        double radiusSq = radiusBlocks * radiusBlocks;
        Collection<Entity> nearby = world.getNearbyEntities(center, radiusBlocks, radiusBlocks, radiusBlocks);
        int count = 0;
        for (Entity entity : nearby) {
            if (entity instanceof Phantom && center.distanceSquared(entity.getLocation()) <= radiusSq) count++;
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
        int radiusSquared = radius * radius;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + y * y + z * z > radiusSquared) continue;
                    
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
    
    /** Radius (blocks) within which phantoms are considered "near a player" for stuck/tree logic. Saves work for distant phantoms. */
    private static final double MOVEMENT_MONITOR_PLAYER_RADIUS = 128.0;
    
    private boolean isNearAnyPlayer(Location phantomLoc, List<Location> playerLocations) {
        if (phantomLoc == null || playerLocations.isEmpty()) return false;
        double radiusSq = MOVEMENT_MONITOR_PLAYER_RADIUS * MOVEMENT_MONITOR_PLAYER_RADIUS;
        for (Location playerLoc : playerLocations) {
            if (playerLoc != null && playerLoc.getWorld() == phantomLoc.getWorld()
                    && phantomLoc.distanceSquared(playerLoc) <= radiusSq) return true;
        }
        return false;
    }
    
    // Optimized method to monitor phantom movement, stuck detection, and tree avoidance, stuck detection, and tree avoidance
    private void monitorPhantomMovement() {
        if (!movementImprovementsEnabled) return;
        
        long currentTime = System.currentTimeMillis();
        
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.THE_END) continue;
            
            List<Player> players = world.getPlayers();
            if (players.isEmpty()) continue;
            
            // Only process phantoms near at least one player (stuck/tree logic is irrelevant far away)
            List<Location> playerLocations = new ArrayList<>(players.size());
            for (Player p : players) {
                if (p.isValid() && !p.isDead()) playerLocations.add(p.getLocation());
            }
            if (playerLocations.isEmpty()) continue;
            
            Collection<Entity> entities = world.getEntities();
            for (Entity entity : entities) {
                if (!(entity instanceof Phantom)) continue;
                
                Phantom phantom = (Phantom) entity;
                UUID phantomId = phantom.getUniqueId();
                
                if (!phantom.isValid() || phantom.isDead()) {
                    cleanupPhantomData(phantomId);
                    continue;
                }
                
                Location currentLoc = phantom.getLocation();
                if (!isNearAnyPlayer(currentLoc, playerLocations)) continue;
                
                handleStuckDetection(phantom, phantomId, currentLoc, currentTime);
                
                if (treeAvoidanceEnabled && isNearChorusFruit(currentLoc)) {
                    long lastAvoidance = lastTreeAvoidanceTime.getOrDefault(phantomId, 0L);
                    if (currentTime - lastAvoidance > 2000) {
                        guidePhantomAroundTrees(phantom);
                        lastTreeAvoidanceTime.put(phantomId, currentTime);
                    }
                }
                
                lastPhantomLocations.put(phantomId, currentLoc);
            }
        }
        // Prune stale UUIDs from aggressive set (phantoms removed without EntityDeathEvent)
        if (!aggressivePhantoms.isEmpty()) {
            List<UUID> toRemove = new ArrayList<>();
            for (UUID uuid : aggressivePhantoms) {
                if (Bukkit.getEntity(uuid) == null) toRemove.add(uuid);
            }
            for (UUID uuid : toRemove) {
                aggressivePhantoms.remove(uuid);
                cleanupPhantomData(uuid);
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
            long interval = endSpawnIntervalTicks;
            runTimer(this::spawnPhantomsInEnd, interval, interval);
        }
        
        // Start optimized movement monitoring if enabled
        if (passivePhantomsEnabled && movementImprovementsEnabled) {
            runTimer(() -> {
                monitorPhantomMovement();
            }, stuckDetectionTicks, stuckDetectionTicks);
        }
        
        runUpdateCheckAsync();
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
        aggressivePhantoms.clear();
        lastPhantomLocations.clear();
        stuckCounter.clear();
        lastMovementTime.clear();
        stuckAttempts.clear();
        lastTreeAvoidanceTime.clear();
        getLogger().info("PassivePhantoms plugin disabled!");
    }
    
    /** Modrinth update check (async); notifies console and players with permission on join. */
    private void runUpdateCheckAsync() {
        if (!updateCheckerEnabled) return;
        runAsync(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(String.format(MODRINTH_VERSION_URL, MODRINTH_PROJECT_ID)).toURL().openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", "PassivePhantoms/" + getDescription().getVersion() + " (MightyFinger77)");
                if (conn.getResponseCode() != 200) {
                    conn.disconnect();
                    return;
                }
                String body = readFully(conn.getInputStream());
                conn.disconnect();
                String fetchedVersion = parseModrinthVersionNumber(body);
                if (fetchedVersion == null || fetchedVersion.isEmpty()) return;
                String currentVersion = getDescription().getVersion();
                final String latest = fetchedVersion.trim();
                final boolean newer = isNewerVersion(latest, currentVersion);
                runSync(() -> {
                    latestVersion = latest;
                    updateAvailable = newer;
                    if (newer) {
                        getLogger().info("[PassivePhantoms] Update available: " + latest + " (current: " + currentVersion + ")");
                        getLogger().info("[PassivePhantoms] Download: https://modrinth.com/plugin/" + MODRINTH_PROJECT_ID);
                    }
                });
            } catch (Exception ignored) { }
        });
    }
    
    private static String parseModrinthVersionNumber(String json) {
        if (json == null) return null;
        int keyIdx = json.indexOf("\"version_number\"");
        if (keyIdx == -1) return null;
        int colon = json.indexOf(':', keyIdx);
        if (colon == -1) return null;
        int start = json.indexOf('"', colon + 1);
        if (start == -1) return null;
        start++;
        int end = json.indexOf('"', start);
        if (end == -1) return null;
        return json.substring(start, end).trim();
    }
    
    private static String readFully(java.io.InputStream in) throws IOException {
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8); StringWriter w = new StringWriter()) {
            char[] buf = new char[512];
            int n;
            while ((n = r.read(buf)) != -1) w.write(buf, 0, n);
            return w.toString();
        }
    }
    
    /**
     * Version comparison that supports dev/pre-release suffixes (e.g. 1.2.6b1, 1.2.6-Dev1a, 1.2.6-dev2).
     * Release is newer than dev of same base; dev vs dev compared by suffix number then letter.
     */
    private boolean isNewerVersion(String latest, String current) {
        if (latest == null || current == null) return false;
        String cleanLatest = latest.trim().replaceAll("^(v|version|alpha|beta|release)\\s*", "").trim();
        String cleanCurrent = current.trim().replaceAll("^(v|version|alpha|beta|release)\\s*", "").trim();
        cleanLatest = cleanLatest.replaceAll("^(Alpha|Beta|Release|V|Version)\\s*", "").trim();
        cleanCurrent = cleanCurrent.replaceAll("^(Alpha|Beta|Release|V|Version)\\s*", "").trim();
        boolean latestIsDev = isDevVersion(cleanLatest);
        boolean currentIsDev = isDevVersion(cleanCurrent);
        String baseLatest = stripDevSuffix(cleanLatest);
        String baseCurrent = stripDevSuffix(cleanCurrent);
        try {
            String[] latestParts = baseLatest.split("\\.");
            String[] currentParts = baseCurrent.split("\\.");
            int maxLen = Math.max(latestParts.length, currentParts.length);
            for (int i = 0; i < maxLen; i++) {
                int l = i < latestParts.length ? parseIntSafe(latestParts[i].replaceAll("[^0-9].*$", ""), 0) : 0;
                int c = i < currentParts.length ? parseIntSafe(currentParts[i].replaceAll("[^0-9].*$", ""), 0) : 0;
                if (l > c) return true;
                if (l < c) return false;
            }
            if (baseLatest.equals(baseCurrent)) {
                if (!latestIsDev && currentIsDev) return true;
                if (latestIsDev && currentIsDev) {
                    int latestNum = extractDevSuffixNumber(cleanLatest);
                    int currentNum = extractDevSuffixNumber(cleanCurrent);
                    if (latestNum != currentNum) return latestNum > currentNum;
                    char latestLetter = extractDevSuffixLetter(cleanLatest);
                    char currentLetter = extractDevSuffixLetter(cleanCurrent);
                    return latestLetter > currentLetter;
                }
                return false;
            }
            return false;
        } catch (Exception e) {
            return !baseLatest.equals(baseCurrent);
        }
    }
    
    private static boolean isDevVersion(String version) {
        if (version == null) return false;
        return version.matches(".*[-_](?i)(dev|snapshot|alpha|beta|rc|build|pre)[\\d\\w]*$")
                || version.matches(".*\\d+[a-zA-Z][\\d]*$");
    }
    
    private static String stripDevSuffix(String version) {
        if (version == null) return "";
        return version.replaceAll("[-_](?i)(dev|snapshot|alpha|beta|rc|build|pre)[\\d\\w]*$", "")
                .replaceAll("(\\d+)[a-zA-Z][\\d]*$", "$1").trim();
    }
    
    /** Matches -dev2, b2, beta3 (group 1) or trailing letter+digits like b1 in 6b1 (group 2). */
    private static final Pattern DEV_SUFFIX_NUMBER = Pattern.compile("(?i)(?:dev|b|beta|alpha|rc)[-_]?(\\d+)|[a-zA-Z](\\d+)$");
    /** Matches -Dev1a / -dev2a (group 1) or trailing 6b1 -> letter b (group 2). Letter is case-insensitive. */
    private static final Pattern DEV_SUFFIX_LETTER = Pattern.compile("(?i)[-_]?dev\\d+([a-zA-Z])$|\\d+([a-zA-Z])[\\d]*$");
    
    private static int extractDevSuffixNumber(String version) {
        if (version == null) return 0;
        Matcher m = DEV_SUFFIX_NUMBER.matcher(version);
        if (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                String g = m.group(i);
                if (g != null && !g.isEmpty()) return parseIntSafe(g, 0);
            }
        }
        return 0;
    }
    
    private static char extractDevSuffixLetter(String version) {
        if (version == null) return 'a';
        Matcher m = DEV_SUFFIX_LETTER.matcher(version);
        if (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                String g = m.group(i);
                if (g != null && !g.isEmpty()) return g.toLowerCase(Locale.ROOT).charAt(0);
            }
        }
        return 'a';
    }
    
    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private boolean hasMissingLeafKeysComparedToJarDefaults(YamlConfiguration current, YamlConfiguration defaults, String fileVersionKey) {
        List<String> missing = new ArrayList<>();
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) continue;
            if (key.equals(fileVersionKey)) continue;
            if (key.equals("config_version") || key.equals("messages_version") || key.equals("gui_version")) continue;
            if (!current.contains(key)) missing.add(key);
        }
        if (!missing.isEmpty()) {
            getLogger().info("Config migration: merging missing keys from jar defaults: " + String.join(", ", missing));
            return true;
        }
        return false;
    }

    /**
     * Locktight-style config migration: merge default config (comments/formatting) with user values.
     * Preserves comments and adds missing keys without wiping user settings.
     */
    private void migrateConfig() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) return;
        try {
            InputStream defaultStream = getResource("config.yml");
            if (defaultStream == null) return;
            List<String> defaultLines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(defaultStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) defaultLines.add(line);
            }
            InputStream defaultYamlStream = getResource("config.yml");
            if (defaultYamlStream == null) return;
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultYamlStream, StandardCharsets.UTF_8));
            YamlConfiguration currentConfig = YamlConfiguration.loadConfiguration(configFile);
            int defaultVersion = defaultConfig.getInt("config_version", 1);
            int currentVersion = currentConfig.getInt("config_version", 0);
            boolean missingLeaves = hasMissingLeafKeysComparedToJarDefaults(currentConfig, defaultConfig, "config_version");
            if (currentVersion == defaultVersion && currentConfig.contains("config_version") && !missingLeaves) {
                return;
            }
            getLogger().info("Config migration: current version=" + currentVersion + ", default version=" + defaultVersion);
            List<String> mergedLines = mergeConfigs(defaultLines, currentConfig, defaultConfig);
            Set<String> deprecatedKeys = findDeprecatedKeys(currentConfig, defaultConfig);
            if (!deprecatedKeys.isEmpty() && debugLogging) getLogger().info("Removed deprecated config keys: " + String.join(", ", deprecatedKeys));
            updateConfigVersion(mergedLines, defaultVersion, defaultLines, "config_version");
            Files.write(configFile.toPath(), mergedLines, StandardCharsets.UTF_8);
            getLogger().info("Config migration completed - merged with default config, preserving user values and comments");
            reloadConfig();
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error during config migration: " + e.getMessage(), e);
        }
    }
    
    private List<String> mergeConfigs(List<String> defaultLines, YamlConfiguration userConfig, YamlConfiguration defaultConfig) {
        List<String> merged = new ArrayList<>();
        Stack<Pair<String, Integer>> pathStack = new Stack<>();
        for (int i = 0; i < defaultLines.size(); i++) {
            String line = defaultLines.get(i);
            String trimmed = line.trim();
            int currentIndent = line.length() - trimmed.length();
            if (trimmed.isEmpty() || line.startsWith("#")) {
                merged.add(line);
                continue;
            }
            while (!pathStack.isEmpty() && currentIndent <= pathStack.peek().value) pathStack.pop();
            if (trimmed.startsWith("-")) {
                merged.add(line);
                continue;
            }
            if (trimmed.contains(":") && !trimmed.startsWith("#")) {
                int colonIndex = trimmed.indexOf(':');
                String keyPart = trimmed.substring(0, colonIndex).trim();
                String valuePart = trimmed.substring(colonIndex + 1).trim();
                StringBuilder fullPathBuilder = new StringBuilder();
                for (Pair<String, Integer> p : pathStack) {
                    if (fullPathBuilder.length() > 0) fullPathBuilder.append(".");
                    fullPathBuilder.append(p.key);
                }
                if (fullPathBuilder.length() > 0) fullPathBuilder.append(".");
                fullPathBuilder.append(keyPart);
                String fullPath = fullPathBuilder.toString();
                boolean isSection = valuePart.isEmpty();
                if (isSection && i + 1 < defaultLines.size()) {
                    for (int j = i + 1; j < defaultLines.size() && j < i + 10; j++) {
                        String nextLine = defaultLines.get(j);
                        String nextTrimmed = nextLine.trim();
                        if (nextTrimmed.isEmpty() || nextLine.startsWith("#")) continue;
                        int nextIndent = nextLine.length() - nextTrimmed.length();
                        if (nextTrimmed.startsWith("-") || nextIndent > currentIndent) {
                            isSection = true;
                            break;
                        } else break;
                    }
                }
                if (isSection) {
                    merged.add(line);
                    pathStack.push(new Pair<>(keyPart, currentIndent));
                } else {
                    if (keyPart.equals("config_version")) {
                        merged.add(line);
                    } else if (userConfig.contains(fullPath)) {
                        Object userValue = userConfig.get(fullPath);
                        String userValueStr = formatYamlValue(userValue);
                        int commentIndex = valuePart.indexOf('#');
                        String inlineComment = commentIndex >= 0 ? " " + valuePart.substring(commentIndex) : "";
                        merged.add(" ".repeat(currentIndent) + keyPart + ": " + userValueStr + inlineComment);
                    } else {
                        merged.add(line);
                    }
                }
            } else {
                merged.add(line);
            }
        }
        return merged;
    }
    
    private Set<String> findDeprecatedKeys(YamlConfiguration userConfig, YamlConfiguration defaultConfig) {
        Set<String> deprecated = new HashSet<>();
        findDeprecatedKeysRecursive(userConfig, defaultConfig, "", deprecated);
        return deprecated;
    }
    
    private void findDeprecatedKeysRecursive(YamlConfiguration userConfig, YamlConfiguration defaultConfig, String basePath, Set<String> deprecated) {
        for (String key : userConfig.getKeys(false)) {
            String fullPath = basePath.isEmpty() ? key : basePath + "." + key;
            if (key.equals("config_version")) continue;
            if (!defaultConfig.contains(fullPath)) deprecated.add(fullPath);
            else if (userConfig.isConfigurationSection(key) && defaultConfig.isConfigurationSection(fullPath)) {
                findDeprecatedKeysRecursive(userConfig.getConfigurationSection(key), defaultConfig.getConfigurationSection(fullPath), fullPath, deprecated);
            }
        }
    }
    
    private void findDeprecatedKeysRecursive(ConfigurationSection userSection, ConfigurationSection defaultSection, String basePath, Set<String> deprecated) {
        for (String key : userSection.getKeys(false)) {
            String fullPath = basePath.isEmpty() ? key : basePath + "." + key;
            if (key.equals("config_version")) continue;
            if (!defaultSection.contains(key)) deprecated.add(fullPath);
            else if (userSection.isConfigurationSection(key) && defaultSection.isConfigurationSection(key)) {
                findDeprecatedKeysRecursive(userSection.getConfigurationSection(key), defaultSection.getConfigurationSection(key), fullPath, deprecated);
            }
        }
    }
    
    private static final class Pair<K, V> {
        final K key;
        final V value;
        Pair(K key, V value) { this.key = key; this.value = value; }
    }
    
    private String formatYamlValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String) {
            String str = (String) value;
            if (str.contains(":") || str.contains("#") || str.trim().isEmpty() || str.equalsIgnoreCase("true") || str.equalsIgnoreCase("false") || str.equalsIgnoreCase("null") || str.matches("^-?\\d+$"))
                return "\"" + str.replace("\"", "\\\"") + "\"";
            return str;
        }
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        return value.toString();
    }
    
    private void updateConfigVersion(List<String> lines, int newVersion, List<String> defaultLines, String versionKey) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith(versionKey + ":") || trimmed.startsWith(versionKey + " ")) {
                int indent = line.length() - trimmed.length();
                String restOfLine = "";
                int colonIndex = trimmed.indexOf(':');
                if (colonIndex >= 0 && colonIndex + 1 < trimmed.length()) {
                    String afterColon = trimmed.substring(colonIndex + 1).trim();
                    int commentIndex = afterColon.indexOf('#');
                    if (commentIndex >= 0) restOfLine = " #" + afterColon.substring(commentIndex + 1);
                }
                lines.set(i, " ".repeat(indent) + versionKey + ": " + newVersion + restOfLine);
                return;
            }
        }
        int insertIndex = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !line.startsWith("#") && !trimmed.startsWith(versionKey)) {
                insertIndex = i;
                break;
            }
        }
        String commentLine = "# Config version - do not modify (used for migration)";
        lines.add(insertIndex, commentLine);
        lines.add(insertIndex + 1, versionKey + ": " + newVersion);
        if (insertIndex + 2 < lines.size() && !lines.get(insertIndex + 2).trim().isEmpty()) lines.add(insertIndex + 2, "");
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();
        
        debugLogging = config.getBoolean("debug_logging", false);
        passivePhantomsEnabled = config.getBoolean("passive_phantoms_enabled", true);
        customSpawnControl = config.getBoolean("phantom_settings.custom_spawn_control", true);
        
        // Spawn settings with validation (invalid values clamped, no crash)
        endSpawnChance = clamp(config.getDouble("phantom_settings.end_spawn_chance", 0.05), 0.0, 1.0, "end_spawn_chance");
        maxPhantomsPerChunk = (int) clamp(config.getInt("phantom_settings.max_phantoms_per_chunk", 8), 1, 64, "max_phantoms_per_chunk");
        spawnCheckRadius = clamp(config.getDouble("phantom_settings.spawn_check_radius", 64.0), 8.0, 256.0, "spawn_check_radius");
        endSpawnIntervalTicks = (long) clamp(config.getInt("phantom_settings.end_spawn_interval_ticks", 200), 100, 600, "end_spawn_interval_ticks");
        
        // Movement improvement settings
        movementImprovementsEnabled = config.getBoolean("phantom_settings.movement_improvements_enabled", true);
        stuckDetectionTicks = (int) clamp(config.getInt("phantom_settings.stuck_detection_ticks", 100), 20, 600, "stuck_detection_ticks");
        stuckThreshold = (int) clamp(config.getInt("phantom_settings.stuck_threshold", 3), 1, 20, "stuck_threshold");
        stuckDistanceThreshold = clamp(config.getDouble("phantom_settings.stuck_distance_threshold", 1.0), 0.1, 10.0, "stuck_distance_threshold");
        maxStuckAttempts = (int) clamp(config.getInt("phantom_settings.max_stuck_attempts", 5), 1, 20, "max_stuck_attempts");
        
        // Tree avoidance (radius cast to int; large values = expensive block scan)
        treeAvoidanceEnabled = config.getBoolean("phantom_settings.tree_avoidance_enabled", true);
        treeAvoidanceRadius = clamp(config.getDouble("phantom_settings.tree_avoidance_radius", 3.0), 1.0, 16.0, "tree_avoidance_radius");
        
        updateCheckerEnabled = config.getBoolean("update_checker", true);
        random = new Random();
    }
    
    /** Clamp value to [min, max]; if clamped, log warning. Returns double for use with getInt/getDouble. */
    private double clamp(double value, double min, double max, String key) {
        if (value < min) {
            getLogger().warning("Config phantom_settings." + key + " " + value + " is below minimum " + min + "; using " + min);
            return min;
        }
        if (value > max) {
            getLogger().warning("Config phantom_settings." + key + " " + value + " is above maximum " + max + "; using " + max);
            return max;
        }
        return value;
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
                    runSync(() -> {
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
        runSync(() -> {
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
            return;
        }
        // In The End: enforce per-chunk cap for any spawn source (our task uses radius cap; this catches others)
        if (world.getEnvironment() == World.Environment.THE_END) {
            int chunkX = event.getLocation().getBlockX() >> 4;
            int chunkZ = event.getLocation().getBlockZ() >> 4;
            if (countPhantomsInChunk(world, chunkX, chunkZ) >= maxPhantomsPerChunk) {
                event.setCancelled(true);
                if (debugLogging) getLogger().info("Cancelled phantom spawn in The End (chunk " + chunkX + "," + chunkZ + " at cap " + maxPhantomsPerChunk + ")");
            }
        }
    }
    
    // Custom phantom spawning in The End
    public void spawnPhantomsInEnd() {
        if (!passivePhantomsEnabled || !customSpawnControl) return;
        if (endSpawnChance <= 0.0) return;
        
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
                    // Cap by phantoms near player (radius), not just in chunk - phantoms fly so per-chunk alone would allow unlimited spawns
                    int currentPhantomsNear = countPhantomsNear(world, playerLoc, spawnCheckRadius);
                    if (currentPhantomsNear >= maxPhantomsPerChunk) {
                        if (debugLogging) getLogger().info("Spawn cap reached near player at " + chunkX + "," + chunkZ + " (" + currentPhantomsNear + "/" + maxPhantomsPerChunk + " phantoms within " + (int)spawnCheckRadius + " blocks)");
                        continue;
                    }
                    // Spawn phantom at a safe location away from chorus fruit (still in player's chunk)
                    Location spawnLoc = findSafeSpawnLocation(world, chunkX, chunkZ);
                    Phantom phantom = world.spawn(spawnLoc, Phantom.class);
                    if (phantom != null) {
                        removeAggressivePhantom(phantom.getUniqueId(), "spawned");
                        if (debugLogging) getLogger().info("Spawned phantom in The End at " + spawnLoc + " (" + (currentPhantomsNear + 1) + "/" + maxPhantomsPerChunk + " near player)");
                    }
                }
            }
        }
    }
    
    @EventHandler
    public void onPhantomDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        if (event.getEntity() instanceof Phantom) {
            UUID phantomId = event.getEntity().getUniqueId();
            removeAggressivePhantom(phantomId, "died");
            cleanupPhantomData(phantomId);
            if (debugLogging) getLogger().info("Phantom died - removed from all tracking");
        }
    }
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!updateCheckerEnabled || !event.getPlayer().hasPermission("passivephantoms.notify")) return;
        runLater(() -> {
            if (updateAvailable && latestVersion != null) {
                org.bukkit.entity.Player p = event.getPlayer();
                if (p != null && p.isOnline()) {
                    p.sendMessage("§6[PassivePhantoms] §eUpdate available: §f" + latestVersion + " §7(current: " + getDescription().getVersion() + ")");
                    p.sendMessage("§7Download: §fhttps://modrinth.com/plugin/" + MODRINTH_PROJECT_ID);
                }
            }
        }, 100L);
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
                sender.sendMessage("§7End spawn chance: §a" + (endSpawnChance * 100) + "% §7(every " + (endSpawnIntervalTicks / 20) + "s)");
                sender.sendMessage("§7Movement improvements: §a" + movementImprovementsEnabled);
                sender.sendMessage("§7Aggressive phantoms tracked: §a" + aggressivePhantoms.size());
                sender.sendMessage("§7Max phantoms per chunk: §a" + maxPhantomsPerChunk);
                sender.sendMessage("§7Spawn check radius: §a" + (int)spawnCheckRadius + " blocks");
                sender.sendMessage("§7Update checker: §a" + (updateCheckerEnabled ? "Enabled" : "Disabled"));
                if (updateAvailable && latestVersion != null) sender.sendMessage("§eUpdate available: §f" + latestVersion + " §7(current: " + getDescription().getVersion() + ")");
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