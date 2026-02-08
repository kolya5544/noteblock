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

        final Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline() && player.getWorld().equals(world)) {
            final World fallback = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            if (fallback != null) {
                player.teleportAsync(fallback.getSpawnLocation());
            }
        }

        Bukkit.unloadWorld(world, false);

        final File worldFolder = world.getWorldFolder();
        if (worldFolder != null) {
            final Path path = worldFolder.toPath();
            WorldDeletion.deleteDirectoryWithRetries(plugin, path, 5);
        }
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
