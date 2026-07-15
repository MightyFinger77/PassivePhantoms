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
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class PassivePhantoms extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    /** Config version in default config.yml; bump when adding/removing options so user config is merged. */
    private static final int CONFIG_VERSION = 3;
    /** Modrinth API v2: GET /project/{id|slug}/version returns JSON array; first item has version_number. */
    private static final String MODRINTH_VERSION_URL = "https://api.modrinth.com/v2/project/%s/version";
    private static final String MODRINTH_PROJECT_ID = "passivephantoms";

    private volatile boolean debugLogging;
    private volatile boolean passivePhantomsEnabled;
    private volatile boolean customSpawnControl;
    private volatile double endSpawnChance;
    private volatile int maxPhantomsPerChunk;
    /** Radius (blocks) around player to count phantoms for cap; prevents flying phantoms from bypassing by leaving chunk. */
    private volatile double spawnCheckRadius;
    /** Ticks between spawn rolls in The End (200 = 10 seconds). Only applied on reload/restart. */
    private volatile long endSpawnIntervalTicks;
    
    // Concurrent: Folia may touch these from multiple region threads
    private final Set<UUID> aggressivePhantoms = ConcurrentHashMap.newKeySet();
    
    // Movement tracking for stuck detection
    private final Map<UUID, Location> lastPhantomLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> stuckCounter = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMovementTime = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> stuckAttempts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTreeAvoidanceTime = new ConcurrentHashMap<>();
    
    // Configuration for movement improvements
    private volatile boolean movementImprovementsEnabled;
    private volatile int stuckDetectionTicks;
    private volatile int stuckThreshold;
    private volatile double stuckDistanceThreshold;
    private volatile int maxStuckAttempts;
    private volatile boolean treeAvoidanceEnabled;
    private volatile double treeAvoidanceRadius;
    
    // Modrinth update checker
    private volatile boolean updateCheckerEnabled;
    private volatile String latestVersion;
    private volatile boolean updateAvailable = false;

    /** Folia/Paper tasks to cancel on disable (BukkitTask or Folia ScheduledTask). */
    private final List<Object> liveTasks = new CopyOnWriteArrayList<>();

    /**
     * Folia only — do not use {@code getGlobalRegionScheduler()} for detection; Paper exposes it too.
     * Cached: Class.forName is unnecessary on every schedule call.
     */
    private final boolean folia = detectFolia();

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private boolean isFolia() {
        return folia;
    }

    private void trackTask(Object task) {
        if (task != null) liveTasks.add(task);
    }

    private void cancelAllTasks() {
        for (Object task : liveTasks) {
            try {
                task.getClass().getMethod("cancel").invoke(task);
            } catch (Exception ignored) {
            }
        }
        liveTasks.clear();
    }

    /** True on Paper always; on Folia only if this thread owns the chunk. */
    private boolean isOwnedByCurrentRegion(World world, int chunkX, int chunkZ) {
        if (!isFolia()) return true;
        try {
            Object result = Bukkit.class
                    .getMethod("isOwnedByCurrentRegion", World.class, int.class, int.class)
                    .invoke(null, world, chunkX, chunkZ);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            try {
                Object result = getServer().getClass()
                        .getMethod("isOwnedByCurrentRegion", World.class, int.class, int.class)
                        .invoke(getServer(), world, chunkX, chunkZ);
                return Boolean.TRUE.equals(result);
            } catch (Exception e2) {
                getLogger().log(Level.WARNING, "isOwnedByCurrentRegion unavailable", e2);
                return false;
            }
        }
    }

    private boolean isOwnedByCurrentRegion(Location location) {
        if (location == null || location.getWorld() == null) return false;
        if (!isFolia()) return true;
        return isOwnedByCurrentRegion(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private boolean isOwnedByCurrentRegion(Entity entity) {
        if (entity == null) return false;
        if (!isFolia()) return true;
        try {
            Object result = Bukkit.class.getMethod("isOwnedByCurrentRegion", Entity.class).invoke(null, entity);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            return false;
        }
    }

    /** Non-world work only on Folia (global region). Prefer {@link #runAtEntity} / {@link #runAtChunk} for world work. */
    private void runSync(Runnable task) {
        if (isFolia()) {
            try {
                Object scheduler = getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(getServer());
                scheduler.getClass().getMethod("execute", Plugin.class, Runnable.class).invoke(scheduler, this, task);
                return;
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Folia GlobalRegionScheduler.execute failed", e);
                return;
            }
        }
        Bukkit.getScheduler().runTask(this, task);
    }

    private void runTimer(Runnable task, long delayTicks, long periodTicks) {
        if (isFolia()) {
            try {
                Object scheduler = getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(getServer());
                Object scheduled = scheduler.getClass()
                        .getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class)
                        .invoke(scheduler, this, (Consumer<Object>) t -> task.run(), delayTicks, periodTicks);
                trackTask(scheduled);
                return;
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Folia GlobalRegionScheduler.runAtFixedRate failed", e);
                return;
            }
        }
        trackTask(Bukkit.getScheduler().runTaskTimer(this, task, delayTicks, periodTicks));
    }

    /** Run on the region that owns this chunk. Safe to call from any thread on Folia. @return false if scheduling failed */
    private boolean runAtChunk(World world, int chunkX, int chunkZ, Runnable task) {
        if (world == null || task == null) return false;
        if (isFolia()) {
            try {
                Object scheduler = getServer().getClass().getMethod("getRegionScheduler").invoke(getServer());
                scheduler.getClass()
                        .getMethod("execute", Plugin.class, World.class, int.class, int.class, Runnable.class)
                        .invoke(scheduler, this, world, chunkX, chunkZ, task);
                return true;
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Folia RegionScheduler.execute failed", e);
                return false;
            }
        }
        Bukkit.getScheduler().runTask(this, task);
        return true;
    }

    /** Run on the entity's owning region. Safe to call from any thread. */
    private void runAtEntity(Entity entity, Runnable task) {
        runAtEntity(entity, task, null);
    }

    private void runAtEntity(Entity entity, Runnable task, Runnable retired) {
        if (entity == null) {
            if (retired != null) retired.run();
            return;
        }
        if (isFolia()) {
            try {
                Object scheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
                scheduler.getClass().getMethod("run", Plugin.class, Consumer.class, Runnable.class)
                        .invoke(scheduler, this, (Consumer<Object>) t -> task.run(), retired);
                return;
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Folia EntityScheduler.run failed", e);
                if (retired != null) retired.run();
                return;
            }
        }
        Bukkit.getScheduler().runTask(this, task);
    }

    /** Delayed entity-region task (Folia EntityScheduler / Paper sync delayed). */
    private void runAtEntityLater(Entity entity, Runnable task, long delayTicks) {
        if (entity == null) return;
        if (isFolia()) {
            try {
                Object scheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
                Object scheduled = scheduler.getClass()
                        .getMethod("runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class)
                        .invoke(scheduler, this, (Consumer<Object>) t -> task.run(), null, delayTicks);
                trackTask(scheduled);
                return;
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Folia EntityScheduler.runDelayed failed", e);
                return;
            }
        }
        trackTask(Bukkit.getScheduler().runTaskLater(this, task, delayTicks));
    }

    @SuppressWarnings("unchecked")
    private void runAsync(Runnable task) {
        if (isFolia()) {
            try {
                Object async = getServer().getClass().getMethod("getAsyncScheduler").invoke(getServer());
                async.getClass().getMethod("runNow", Plugin.class, Consumer.class)
                        .invoke(async, this, (Consumer<Object>) st -> task.run());
                return;
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Folia AsyncScheduler.runNow failed", e);
                return;
            }
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, task);
    }

    private void reply(CommandSender sender, String message) {
        if (sender instanceof Player) {
            Player player = (Player) sender;
            if (isFolia()) {
                runAtEntity(player, () -> player.sendMessage(message));
            } else {
                player.sendMessage(message);
            }
        } else {
            sender.sendMessage(message);
        }
    }

    private void reply(CommandSender sender, List<String> messages) {
        if (sender instanceof Player) {
            Player player = (Player) sender;
            Runnable send = () -> {
                for (String message : messages) player.sendMessage(message);
            };
            if (isFolia()) {
                runAtEntity(player, send);
            } else {
                send.run();
            }
        } else {
            for (String message : messages) sender.sendMessage(message);
        }
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

    /** Iterate loaded chunks overlapping a horizontal radius (inclusive). */
    private void forEachChunkInRadius(Location center, double radiusBlocks, ChunkConsumer consumer) {
        World world = center.getWorld();
        if (world == null) return;
        int minCX = (int) Math.floor((center.getX() - radiusBlocks) / 16.0);
        int maxCX = (int) Math.floor((center.getX() + radiusBlocks) / 16.0);
        int minCZ = (int) Math.floor((center.getZ() - radiusBlocks) / 16.0);
        int maxCZ = (int) Math.floor((center.getZ() + radiusBlocks) / 16.0);
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                consumer.accept(world, cx, cz);
            }
        }
    }

    @FunctionalInterface
    private interface ChunkConsumer {
        void accept(World world, int chunkX, int chunkZ);
    }

    /**
     * Count phantoms in one chunk. Caller must own the chunk region on Folia
     * (e.g. CreatureSpawnEvent at that chunk, or {@link #runAtChunk}).
     */
    private int countPhantomsInChunk(World world, int chunkX, int chunkZ) {
        if (world == null || !world.isChunkLoaded(chunkX, chunkZ)) return 0;
        if (!isOwnedByCurrentRegion(world, chunkX, chunkZ)) return 0;
        int count = 0;
        for (Entity entity : world.getChunkAt(chunkX, chunkZ).getEntities()) {
            if (entity instanceof Phantom) count++;
        }
        return count;
    }

    /**
     * Sync count of phantoms near a location using only chunks owned by the current region.
     * Safe on Folia when already on a region thread; may under-count across region borders.
     * Prefer {@link #countPhantomsNearAsync} when a full radius count is required.
     */
    private int countPhantomsNear(World world, Location center, double radiusBlocks) {
        if (center == null || world == null) return 0;
        double radiusSq = radiusBlocks * radiusBlocks;
        int count = 0;
        int minCX = (int) Math.floor((center.getX() - radiusBlocks) / 16.0);
        int maxCX = (int) Math.floor((center.getX() + radiusBlocks) / 16.0);
        int minCZ = (int) Math.floor((center.getZ() - radiusBlocks) / 16.0);
        int maxCZ = (int) Math.floor((center.getZ() + radiusBlocks) / 16.0);
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (!world.isChunkLoaded(cx, cz)) continue;
                if (!isOwnedByCurrentRegion(world, cx, cz)) continue;
                for (Entity entity : world.getChunkAt(cx, cz).getEntities()) {
                    if (!(entity instanceof Phantom)) continue;
                    if (center.distanceSquared(entity.getLocation()) <= radiusSq) count++;
                }
            }
        }
        return count;
    }

    /**
     * Full radius phantom count. Paper: sync. Folia: RegionScheduler per chunk, then callback.
     * Callback may run on a region thread — hop with {@link #runAtEntity} before touching other entities.
     */
    private void countPhantomsNearAsync(Location center, double radiusBlocks, IntConsumer callback) {
        World world = center.getWorld();
        if (center == null || world == null) {
            callback.accept(0);
            return;
        }
        if (!isFolia()) {
            callback.accept(countPhantomsNear(world, center, radiusBlocks));
            return;
        }

        final double radiusSq = radiusBlocks * radiusBlocks;
        final List<int[]> chunks = new ArrayList<>();
        forEachChunkInRadius(center, radiusBlocks, (w, cx, cz) -> chunks.add(new int[]{cx, cz}));
        if (chunks.isEmpty()) {
            callback.accept(0);
            return;
        }

        final AtomicInteger pending = new AtomicInteger(chunks.size());
        final AtomicInteger total = new AtomicInteger();
        final Set<UUID> seen = ConcurrentHashMap.newKeySet();
        final Location centerCopy = center.clone();

        for (int[] chunk : chunks) {
            final int cx = chunk[0];
            final int cz = chunk[1];
            Runnable work = () -> {
                try {
                    if (!world.isChunkLoaded(cx, cz)) return;
                    for (Entity entity : world.getChunkAt(cx, cz).getEntities()) {
                        if (!(entity instanceof Phantom)) continue;
                        if (centerCopy.distanceSquared(entity.getLocation()) > radiusSq) continue;
                        if (seen.add(entity.getUniqueId())) total.incrementAndGet();
                    }
                } finally {
                    if (pending.decrementAndGet() == 0) {
                        callback.accept(total.get());
                    }
                }
            };
            if (!runAtChunk(world, cx, cz, work)) {
                if (pending.decrementAndGet() == 0) {
                    callback.accept(total.get());
                }
            }
        }
    }
    
    // Optimized method to check if a location is near chorus fruit
    private boolean isNearChorusFruit(Location location) {
        int radius = (int) treeAvoidanceRadius;
        World world = location.getWorld();
        if (world == null) return false;
        
        // Use squared distance for better performance (avoid square root)
        int radiusSquared = radius * radius;
        int baseX = location.getBlockX();
        int baseY = location.getBlockY();
        int baseZ = location.getBlockZ();
        
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    // Skip if outside the sphere (performance optimization)
                    if (x * x + y * y + z * z > radiusSquared) continue;
                    int bx = baseX + x;
                    int by = baseY + y;
                    int bz = baseZ + z;
                    if (!isOwnedByCurrentRegion(world, bx >> 4, bz >> 4)) continue;
                    
                    Block block = world.getBlockAt(bx, by, bz);
                    
                    Material type = block.getType();
                    if (type == Material.CHORUS_PLANT || type == Material.CHORUS_FLOWER) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    // Helper method to find a safe spawn location away from chorus fruit (caller must own chunk)
    private Location findSafeSpawnLocation(World world, int chunkX, int chunkZ) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int attempts = 0;
        int maxAttempts = 20;
        
        while (attempts < maxAttempts) {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            double x = baseX + rng.nextInt(16) + 0.5;
            double z = baseZ + rng.nextInt(16) + 0.5;
            double y = world.getHighestBlockYAt((int)x, (int)z) + 10;
            Location testLoc = new Location(world, x, y, z);
            
            if (!isNearChorusFruit(testLoc)) {
                return testLoc;
            }
            attempts++;
        }
        
        // If no safe location found, return a random one anyway
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double x = baseX + rng.nextInt(16) + 0.5;
        double z = baseZ + rng.nextInt(16) + 0.5;
        double y = world.getHighestBlockYAt((int)x, (int)z) + 10;
        return new Location(world, x, y, z);
    }
    
    // Helper method to help stuck phantoms escape
    private void helpPhantomEscape(Phantom phantom) {
        if (!phantom.isValid() || phantom.isDead()) return;
        if (!isOwnedByCurrentRegion(phantom)) return;
        
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
            // If no escape location found, try to move the phantom up (same chunk — always owned)
            Location upLoc = currentLoc.clone().add(0, 5, 0);
            if (isOwnedByCurrentRegion(upLoc)) {
                phantom.teleport(upLoc);
                if (debugLogging) getLogger().info("Moved stuck phantom " + phantomId + " upward");
            }
        }
    }
    
    // Helper method to find an escape location for stuck phantoms
    private Location findEscapeLocation(Location currentLoc) {
        World world = currentLoc.getWorld();
        if (world == null) return null;
        
        // Prefer upward escapes (same chunk) before horizontal (may cross region)
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
            if (!isOwnedByCurrentRegion(attempt)) continue;
            if (isLocationSafe(attempt)) {
                return attempt;
            }
        }
        
        return null;
    }
    
    // Helper method to check if a location is safe for phantoms
    private boolean isLocationSafe(Location location) {
        if (location.getWorld() == null) return false;
        if (!isOwnedByCurrentRegion(location)) return false;
        
        // Check if the location is not inside blocks
        Block block = location.getBlock();
        if (block.getType() != Material.AIR && !block.getType().isSolid()) {
            return false;
        }
        
        // Check if there's enough space above
        for (int y = 1; y <= 3; y++) {
            Location aboveLoc = location.clone().add(0, y, 0);
            if (!isOwnedByCurrentRegion(aboveLoc)) return false;
            Block above = aboveLoc.getBlock();
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
        if (!isOwnedByCurrentRegion(phantom)) return;
        
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
        int baseX = location.getBlockX();
        int baseY = location.getBlockY();
        int baseZ = location.getBlockZ();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + y * y + z * z > radiusSquared) continue;
                    int bx = baseX + x;
                    int by = baseY + y;
                    int bz = baseZ + z;
                    if (!isOwnedByCurrentRegion(world, bx >> 4, bz >> 4)) continue;
                    
                    Block block = world.getBlockAt(bx, by, bz);
                    
                    Material type = block.getType();
                    if (type == Material.CHORUS_PLANT || type == Material.CHORUS_FLOWER) {
                        Location treeLoc = block.getLocation().add(0.5, 0.5, 0.5);
                        double distance = location.distanceSquared(treeLoc);
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
    
    // Optimized method to monitor phantom movement, stuck detection, and tree avoidance
    private void monitorPhantomMovement() {
        if (!movementImprovementsEnabled) return;
        
        long currentTime = System.currentTimeMillis();
        // Dedupe when multiple End players / chunk tasks share phantoms
        Set<UUID> processed = ConcurrentHashMap.newKeySet();
        
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isFolia()) {
                // Do not read player/world state from the global timer — schedule onto the player first
                runAtEntity(player, () -> monitorPhantomsNearPlayer(player, processed, currentTime));
            } else {
                World world = player.getWorld();
                if (world == null || world.getEnvironment() != World.Environment.THE_END) continue;
                if (!player.isValid() || player.isDead()) continue;
                monitorPhantomsNearPlayer(player, processed, currentTime);
            }
        }
        
        pruneStaleAggressivePhantoms();
    }
    
    /**
     * From the player's region, fan out per-chunk region tasks so entity access never crosses regions.
     * Paper runs chunk work inline on the main thread.
     */
    private void monitorPhantomsNearPlayer(Player player, Set<UUID> processed, long currentTime) {
        if (!player.isValid() || player.isDead()) return;
        World world = player.getWorld();
        if (world == null || world.getEnvironment() != World.Environment.THE_END) return;
        
        final Location playerLoc = player.getLocation().clone();
        final double radiusSq = MOVEMENT_MONITOR_PLAYER_RADIUS * MOVEMENT_MONITOR_PLAYER_RADIUS;
        
        forEachChunkInRadius(playerLoc, MOVEMENT_MONITOR_PLAYER_RADIUS, (w, cx, cz) -> {
            Runnable chunkWork = () -> {
                if (!w.isChunkLoaded(cx, cz)) return;
                for (Entity entity : w.getChunkAt(cx, cz).getEntities()) {
                    if (!(entity instanceof Phantom)) continue;
                    if (playerLoc.distanceSquared(entity.getLocation()) > radiusSq) continue;
                    
                    Phantom phantom = (Phantom) entity;
                    UUID phantomId = phantom.getUniqueId();
                    if (!processed.add(phantomId)) continue;
                    processMonitoredPhantom(phantom, phantomId, currentTime);
                }
            };
            if (isFolia()) {
                runAtChunk(w, cx, cz, chunkWork);
            } else {
                chunkWork.run();
            }
        });
    }
    
    private void processMonitoredPhantom(Phantom phantom, UUID phantomId, long currentTime) {
        if (!phantom.isValid() || phantom.isDead()) {
            removeAggressivePhantom(phantomId, "invalid during monitor");
            cleanupPhantomData(phantomId);
            return;
        }
        if (!isOwnedByCurrentRegion(phantom)) return;
        
        Location currentLoc = phantom.getLocation();
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
    
    /**
     * Paper: prune via UUID lookup on main thread.
     * Folia: skip global getEntity — cleanup happens in monitor/death on the owning region.
     */
    private void pruneStaleAggressivePhantoms() {
        if (isFolia() || aggressivePhantoms.isEmpty()) return;
        List<UUID> toRemove = new ArrayList<>();
        for (UUID uuid : aggressivePhantoms) {
            if (Bukkit.getEntity(uuid) == null) toRemove.add(uuid);
        }
        for (UUID uuid : toRemove) {
            aggressivePhantoms.remove(uuid);
            cleanupPhantomData(uuid);
        }
    }
    
    // Helper method to handle stuck detection logic
    private void handleStuckDetection(Phantom phantom, UUID phantomId, Location currentLoc, long currentTime) {
        Location lastLoc = lastPhantomLocations.get(phantomId);
        if (lastLoc == null) return;
        if (lastLoc.getWorld() == null || currentLoc.getWorld() == null
                || lastLoc.getWorld() != currentLoc.getWorld()) {
            lastPhantomLocations.put(phantomId, currentLoc);
            return;
        }
        
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
                    removeAggressivePhantom(phantomId, "permanently stuck");
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
        cancelAllTasks();
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
            // Always double-quote strings so migration round-trips safely through SnakeYAML.
            return "\"" + escapeForYamlDoubleQuotedString((String) value) + "\"";
        }
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        return value.toString();
    }

    private String escapeForYamlDoubleQuotedString(String str) {
        StringBuilder sb = new StringBuilder(str.length() + 8);
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '"') {
                sb.append("\\\"");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
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
                    // Schedule the re-targeting on the phantom's region (Folia-safe)
                    runAtEntity(phantom, () -> {
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
    
    /**
     * Nearest player within 64 blocks. Caller must already be on the phantom's region (event / entity task).
     * Scans only chunks owned by the current region (Folia-safe; no cross-region getPlayers/getLocation).
     */
    private Player findNearestPlayer(Phantom phantom) {
        Location loc = phantom.getLocation();
        World world = loc.getWorld();
        if (world == null) return null;
        
        Player nearestPlayer = null;
        double nearestDistanceSq = Double.MAX_VALUE;
        final double maxRange = 64.0;
        final double maxRangeSq = maxRange * maxRange;
        
        int minCX = (int) Math.floor((loc.getX() - maxRange) / 16.0);
        int maxCX = (int) Math.floor((loc.getX() + maxRange) / 16.0);
        int minCZ = (int) Math.floor((loc.getZ() - maxRange) / 16.0);
        int maxCZ = (int) Math.floor((loc.getZ() + maxRange) / 16.0);
        
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (!world.isChunkLoaded(cx, cz)) continue;
                if (!isOwnedByCurrentRegion(world, cx, cz)) continue;
                for (Entity entity : world.getChunkAt(cx, cz).getEntities()) {
                    if (!(entity instanceof Player)) continue;
                    Player player = (Player) entity;
                    if (!player.isValid() || player.isDead()) continue;
                    double distanceSq = loc.distanceSquared(player.getLocation());
                    if (distanceSq <= maxRangeSq && distanceSq < nearestDistanceSq) {
                        nearestDistanceSq = distanceSq;
                        nearestPlayer = player;
                    }
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
        
        // Force the phantom to target the player on the phantom's region (Folia-safe)
        runAtEntity(phantom, () -> {
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
        
        for (Player targetPlayer : Bukkit.getOnlinePlayers()) {
            // Chance roll is thread-safe; entity/world reads happen only on the correct thread below
            if (ThreadLocalRandom.current().nextDouble() >= endSpawnChance) continue;
            
            if (isFolia()) {
                runAtEntity(targetPlayer, () -> spawnPhantomNearPlayer(targetPlayer));
            } else {
                World world = targetPlayer.getWorld();
                if (world == null || world.getEnvironment() != World.Environment.THE_END) continue;
                if (!targetPlayer.isValid() || targetPlayer.isDead()) continue;
                spawnPhantomNearPlayer(targetPlayer);
            }
        }
    }
    
    /** Must run on the player's region thread (Folia) or main thread (Paper). */
    private void spawnPhantomNearPlayer(Player targetPlayer) {
        if (!targetPlayer.isValid() || targetPlayer.isDead()) return;
        World world = targetPlayer.getWorld();
        if (world == null || world.getEnvironment() != World.Environment.THE_END) return;
        
        final Location playerLoc = targetPlayer.getLocation().clone();
        final int chunkX = playerLoc.getBlockX() >> 4;
        final int chunkZ = playerLoc.getBlockZ() >> 4;
        
        if (isFolia()) {
            // Full multi-region count, then hop back to the player to spawn in their chunk
            countPhantomsNearAsync(playerLoc, spawnCheckRadius, count -> {
                if (count >= maxPhantomsPerChunk) {
                    if (debugLogging) getLogger().info("Spawn cap reached near player at " + chunkX + "," + chunkZ + " (" + count + "/" + maxPhantomsPerChunk + " phantoms within " + (int)spawnCheckRadius + " blocks)");
                    return;
                }
                runAtEntity(targetPlayer, () -> {
                    if (!targetPlayer.isValid() || targetPlayer.isDead()) return;
                    World w = targetPlayer.getWorld();
                    if (w == null || w.getEnvironment() != World.Environment.THE_END) return;
                    Location loc = targetPlayer.getLocation();
                    int cx = loc.getBlockX() >> 4;
                    int cz = loc.getBlockZ() >> 4;
                    runAtChunk(w, cx, cz, () -> doSpawnPhantom(w, cx, cz, count));
                });
            });
            return;
        }
        
        int currentPhantomsNear = countPhantomsNear(world, playerLoc, spawnCheckRadius);
        if (currentPhantomsNear >= maxPhantomsPerChunk) {
            if (debugLogging) getLogger().info("Spawn cap reached near player at " + chunkX + "," + chunkZ + " (" + currentPhantomsNear + "/" + maxPhantomsPerChunk + " phantoms within " + (int)spawnCheckRadius + " blocks)");
            return;
        }
        doSpawnPhantom(world, chunkX, chunkZ, currentPhantomsNear);
    }
    
    /** Caller must own the target chunk region. */
    private void doSpawnPhantom(World world, int chunkX, int chunkZ, int currentPhantomsNear) {
        if (!isOwnedByCurrentRegion(world, chunkX, chunkZ)) return;
        Location spawnLoc = findSafeSpawnLocation(world, chunkX, chunkZ);
        Phantom phantom = world.spawn(spawnLoc, Phantom.class);
        if (phantom != null) {
            removeAggressivePhantom(phantom.getUniqueId(), "spawned");
            if (debugLogging) getLogger().info("Spawned phantom in The End at " + spawnLoc + " (" + (currentPhantomsNear + 1) + "/" + maxPhantomsPerChunk + " near player)");
        }
    }
    
    /**
     * Paper: full-world entity scan. Folia: per-chunk RegionScheduler counts near End players.
     */
    private void sendPhantomPopulations(CommandSender sender) {
        if (!isFolia()) {
            List<String> lines = new ArrayList<>();
            for (World world : Bukkit.getWorlds()) {
                if (world.getEnvironment() != World.Environment.THE_END) continue;
                int totalPhantoms = 0;
                for (Entity entity : world.getEntities()) {
                    if (entity instanceof Phantom) totalPhantoms++;
                }
                lines.add("§7" + world.getName() + ": §a" + totalPhantoms + " total phantoms");
            }
            reply(sender, lines);
            return;
        }
        
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            reply(sender, "§7No online players (Folia: counts phantoms near End players)");
            return;
        }
        
        Map<String, Set<UUID>> byWorld = new ConcurrentHashMap<>();
        AtomicInteger pending = new AtomicInteger(players.size());
        Runnable finishOne = () -> {
            if (pending.decrementAndGet() == 0) {
                List<String> lines = new ArrayList<>();
                lines.add("§7(Folia: phantoms near online End players)");
                if (byWorld.isEmpty()) {
                    lines.add("§7No phantoms near End players");
                } else {
                    for (Map.Entry<String, Set<UUID>> entry : byWorld.entrySet()) {
                        lines.add("§7" + entry.getKey() + ": §a" + entry.getValue().size() + " phantoms near players");
                    }
                }
                reply(sender, lines);
            }
        };
        
        for (Player p : players) {
            runAtEntity(p, () -> collectPhantomsNearPlayerForStatus(p, byWorld, finishOne), finishOne);
        }
    }
    
    /** Folia status helper: fan out per-chunk, then invoke done exactly once. */
    private void collectPhantomsNearPlayerForStatus(Player p, Map<String, Set<UUID>> byWorld, Runnable done) {
        if (!p.isValid() || p.isDead()) {
            done.run();
            return;
        }
        World w = p.getWorld();
        if (w == null || w.getEnvironment() != World.Environment.THE_END) {
            done.run();
            return;
        }
        final Location loc = p.getLocation().clone();
        final double radiusSq = MOVEMENT_MONITOR_PLAYER_RADIUS * MOVEMENT_MONITOR_PLAYER_RADIUS;
        final Set<UUID> ids = byWorld.computeIfAbsent(w.getName(), n -> ConcurrentHashMap.newKeySet());
        
        final List<int[]> chunks = new ArrayList<>();
        forEachChunkInRadius(loc, MOVEMENT_MONITOR_PLAYER_RADIUS, (world, cx, cz) -> chunks.add(new int[]{cx, cz}));
        if (chunks.isEmpty()) {
            done.run();
            return;
        }
        
        AtomicInteger pendingChunks = new AtomicInteger(chunks.size());
        for (int[] chunk : chunks) {
            final int cx = chunk[0];
            final int cz = chunk[1];
            Runnable work = () -> {
                try {
                    if (w.isChunkLoaded(cx, cz)) {
                        for (Entity e : w.getChunkAt(cx, cz).getEntities()) {
                            if (!(e instanceof Phantom)) continue;
                            if (loc.distanceSquared(e.getLocation()) > radiusSq) continue;
                            ids.add(e.getUniqueId());
                        }
                    }
                } finally {
                    if (pendingChunks.decrementAndGet() == 0) {
                        done.run();
                    }
                }
            };
            if (!runAtChunk(w, cx, cz, work)) {
                if (pendingChunks.decrementAndGet() == 0) {
                    done.run();
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
        Player player = event.getPlayer();
        runAtEntityLater(player, () -> {
            if (!updateAvailable || latestVersion == null) return;
            if (!player.isOnline()) return;
            player.sendMessage("§6[PassivePhantoms] §eUpdate available: §f" + latestVersion + " §7(current: " + getDescription().getVersion() + ")");
            player.sendMessage("§7Download: §fhttps://modrinth.com/plugin/" + MODRINTH_PROJECT_ID);
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
                
                // Show phantom counts per End world
                sender.sendMessage("§6Phantom Populations:");
                sendPhantomPopulations(sender);
                return true;
            } else if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
                if (!sender.hasPermission("passivephantoms.reload")) {
                    sender.sendMessage("§cYou don't have permission to use this command!");
                    return true;
                }
                if (aggressivePhantoms.isEmpty()) {
                    reply(sender, "§7No aggressive phantoms currently tracked.");
                } else {
                    reply(sender, "§6Aggressive Phantoms (" + aggressivePhantoms.size() + "):");
                    for (UUID phantomId : aggressivePhantoms) {
                        Entity entity = Bukkit.getEntity(phantomId);
                        if (!(entity instanceof Phantom)) {
                            reply(sender, "§7- " + phantomId + " (entity not found - may be dead)");
                            continue;
                        }
                        Phantom phantom = (Phantom) entity;
                        final UUID id = phantomId;
                        Runnable report = () -> {
                            if (!phantom.isValid()) {
                                reply(sender, "§7- " + id + " (entity not found - may be dead)");
                                return;
                            }
                            String targetInfo = "none";
                            try {
                                if (phantom.getTarget() != null) {
                                    targetInfo = phantom.getTarget().getName();
                                }
                            } catch (Exception ignored) {
                                targetInfo = "unknown";
                            }
                            reply(sender, "§7- " + id + " (target: " + targetInfo + ", alive: " + !phantom.isDead() + ")");
                        };
                        if (isFolia()) {
                            runAtEntity(phantom, report);
                        } else {
                            report.run();
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