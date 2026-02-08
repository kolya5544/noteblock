package ax.nk.noteblock.session;

import ax.nk.noteblock.game.GameController;
import ax.nk.noteblock.game.GameControllerFactory;
import ax.nk.noteblock.world.VoidChunkGenerator;
import ax.nk.noteblock.world.WorldDeletion;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionManager {

    private static final String WORLD_PREFIX = "player_";

    private final Plugin plugin;
    private final GameControllerFactory controllerFactory;

    private final Map<UUID, GameSession> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> creating = ConcurrentHashMap.newKeySet();

    public SessionManager(Plugin plugin, GameControllerFactory controllerFactory) {
        this.plugin = plugin;
        this.controllerFactory = controllerFactory;
    }

    /**
     * Creates (or reuses) a dedicated void world for the player.
     * Must run on the main server thread.
     */
    public void startSession(Player player) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> startSession(player));
            return;
        }

        final UUID playerId = player.getUniqueId();
        final GameSession existing = sessions.get(playerId);
        if (existing != null) {
            player.teleportAsync(existing.spawn());
            return;
        }

        if (!creating.add(playerId)) {
            return;
        }

        try {
            final String worldName = WORLD_PREFIX + playerId;

            final WorldCreator creator = new WorldCreator(worldName)
                    .environment(World.Environment.NORMAL)
                    .generator(new VoidChunkGenerator());

            final World world = Bukkit.createWorld(creator);
            if (world == null) {
                plugin.getLogger().severe("Failed to create world for " + player.getName());
                return;
            }

            configureWorld(world);

            final Location spawn = ensureSpawnPlatform(world);

            // Create controller last, after world/spawn exist.
            final GameSession temp = new GameSession(playerId, world, spawn, null);
            final GameController controller = controllerFactory.create(temp);
            final GameSession session = new GameSession(playerId, world, spawn, controller);

            sessions.put(playerId, session);

            player.teleportAsync(spawn).thenRun(() -> {
                if (!player.isOnline()) return;
                Bukkit.getScheduler().runTask(plugin, () -> controller.onStart(session, player));
            });
        } finally {
            creating.remove(playerId);
        }
    }

    /** Ends session, unloads and deletes the player's world. */
    public void endSession(UUID playerId) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> endSession(playerId));
            return;
        }

        final GameSession session = sessions.remove(playerId);
        if (session == null) return;

        try {
            session.controller().onStop(session);
        } catch (Throwable t) {
            plugin.getLogger().severe("Controller onStop failed: " + t.getMessage());
            t.printStackTrace();
        }

        final World world = session.world();
        final String worldName = world.getName();

        final Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline() && player.getWorld().equals(world)) {
            final World fallback = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            if (fallback != null) {
                player.teleportAsync(fallback.getSpawnLocation());
            }
        }

        // Unload + delete with retries (Windows can hold locks briefly).
        unloadAndDeleteWorld(worldName, 10);
    }

    private void unloadAndDeleteWorld(String worldName, int triesLeft) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> unloadAndDeleteWorld(worldName, triesLeft));
            return;
        }

        final World world = Bukkit.getWorld(worldName);
        if (world != null) {
            // Make sure no players are still inside.
            for (Player p : world.getPlayers()) {
                final World fallback = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
                if (fallback != null) {
                    p.teleportAsync(fallback.getSpawnLocation());
                }
            }

            final boolean unloaded = Bukkit.unloadWorld(world, false);
            if (!unloaded) {
                if (triesLeft > 0) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> unloadAndDeleteWorld(worldName, triesLeft - 1), 20L);
                } else {
                    plugin.getLogger().severe("Failed to unload world '" + worldName + "' for deletion.");
                }
                return;
            }
        }

        // Delete using the known world container + name (folder can still exist when world is already null).
        final Path worldPath = Bukkit.getWorldContainer().toPath().resolve(worldName);
        WorldDeletion.deleteDirectoryWithRetries(plugin, worldPath, 30);
    }

    public void shutdown() {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, this::shutdown);
            return;
        }

        // Avoid ConcurrentModification by iterating over a snapshot.
        for (UUID playerId : sessions.keySet().toArray(UUID[]::new)) {
            endSession(playerId);
        }
    }

    /**
     * Deletes leftover session worlds from previous server runs.
     * This is intentionally only called on plugin enable ("server reboot" cleanup).
     */
    public void cleanupLeftoverWorldsOnBoot() {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, this::cleanupLeftoverWorldsOnBoot);
            return;
        }

        final File container = Bukkit.getWorldContainer();
        final File[] children = container.listFiles();
        if (children == null) return;

        for (File f : children) {
            if (!f.isDirectory()) continue;
            final String name = f.getName();
            if (!name.startsWith(WORLD_PREFIX)) continue;

            // Don't touch loaded worlds.
            if (Bukkit.getWorld(name) != null) continue;

            WorldDeletion.deleteDirectoryWithRetries(plugin, f.toPath(), 30);
        }
    }

    private static void configureWorld(World world) {
        world.setAutoSave(false);

        // KeepSpawnInMemory and several GameRule constants are deprecated/marked for removal in recent Paper.
        // You can still set gamerules in your controller if you want, but this base avoids those APIs.

        world.setTime(6000);
        world.setStorm(false);
        world.setThundering(false);

        world.setSpawnLocation(0, 64, 0);
    }

    /** Creates a tiny safe platform in the void and returns a good spawn location. */
    private static Location ensureSpawnPlatform(World world) {
        final Location spawn = new Location(world, 0.5, 65, 0.5, 0f, 0f);

        final int baseY = 64;
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                final Block b = world.getBlockAt(x, baseY, z);
                b.setType(Material.BARRIER, false);
            }
        }

        // Ensure air above.
        for (int y = 65; y <= 68; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }

        return spawn;
    }
}
