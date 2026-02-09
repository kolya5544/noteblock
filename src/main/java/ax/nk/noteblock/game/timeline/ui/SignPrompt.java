package ax.nk.noteblock.game.timeline.ui;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Sign-based text input prompt.
 *
 * Works without ProtocolLib by:
 * - placing a temporary sign in the session world
 * - opening the sign editor
 * - capturing the sign change event
 */
public final class SignPrompt implements Listener {

    private final Map<UUID, Pending> pendingByPlayer = new HashMap<>();
    private final Plugin plugin;

    private record Pending(
            Block signBlock,
            BlockData oldSignData,
            Block supportBlock,
            BlockData oldSupportData,
            boolean supportPlaced,
            Consumer<String[]> onComplete
    ) {}

    public SignPrompt(Plugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void shutdown() {
        HandlerList.unregisterAll(this);
        pendingByPlayer.clear();
    }

    /**
     * Opens a sign editor with some prefilled text.
     *
     * @param player     player
     * @param loc        where to place the temporary sign (air block)
     * @param lines      4 lines (will be truncated if longer)
     * @param onComplete called when the player finishes editing
     * @return true if opened, false if player already had a pending prompt
     */
    public boolean open(Player player, Location loc, String[] lines, Consumer<String[]> onComplete) {
        final UUID uuid = player.getUniqueId();
        if (pendingByPlayer.containsKey(uuid)) return false;

        final Block signBlock = loc.getBlock();
        if (!signBlock.getType().isAir()) return false;

        // Wall sign facing the player (cardinal)
        final BlockFace facing = player.getFacing();

        // The supporting "wall" block behind the sign (opposite of facing)
        final Block supportBlock = signBlock.getRelative(facing.getOppositeFace());

        // We only ever place support if it's air; if it's passable (water/grass), bail.
        final boolean supportIsAir = supportBlock.getType().isAir();
        if (!supportIsAir && supportBlock.isPassable()) return false;

        final BlockData oldSignData = signBlock.getBlockData();
        final BlockData oldSupportData = supportBlock.getBlockData();

        boolean supportPlaced = false;

        if (supportIsAir) {
            supportBlock.setType(Material.BARRIER, false);
            supportPlaced = true;
        }

        // Place a wall sign and set its facing
        signBlock.setType(Material.OAK_WALL_SIGN, false);
        final BlockData bd = signBlock.getBlockData();
        if (bd instanceof WallSign ws) {
            ws.setFacing(facing);
            signBlock.setBlockData(ws, false);
        }

        final var state = signBlock.getState();
        if (!(state instanceof Sign sign)) {
            // revert
            signBlock.setBlockData(oldSignData, false);
            if (supportPlaced) supportBlock.setBlockData(oldSupportData, false);
            return false;
        }

        // Prefill FRONT side
        final var front = sign.getSide(Side.FRONT);
        for (int i = 0; i < 4; i++) {
            final String line = (lines != null && i < lines.length && lines[i] != null) ? lines[i] : "";
            front.setLine(i, line);
        }

        // Restrict editing to this player (helps prevent weirdness if others click it)
        sign.setAllowedEditorUniqueId(uuid);

        sign.update(true, false);

        pendingByPlayer.put(uuid, new Pending(signBlock, oldSignData, supportBlock, oldSupportData, supportPlaced, onComplete));

        // Open next tick to ensure the client has the sign + text
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Player may have quit in between
            if (!player.isOnline()) return;

            final Pending p = pendingByPlayer.get(uuid);
            if (p == null) return;

            final var st = p.signBlock().getState();
            if (st instanceof Sign s) {
                s.setAllowedEditorUniqueId(uuid);
                s.update(true, false);
                player.openSign(s, Side.FRONT);
            }
        }, 1L);

        return true;
    }


    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSignChange(SignChangeEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();

        final Pending pending = pendingByPlayer.get(uuid);
        if (pending == null) return;

        // Only react to OUR sign
        if (!event.getBlock().equals(pending.signBlock())) return;

        // Now we can consume the pending entry
        pendingByPlayer.remove(uuid);

        try {
            // Depending on API version, SignChangeEvent lines can be deprecated/side-agnostic.
            // To be consistent with Side.FRONT editing, read from the updated Sign state.
            final var st = pending.signBlock().getState();
            final String[] out = new String[4];
            if (st instanceof Sign sign) {
                final var front = sign.getSide(Side.FRONT);
                for (int i = 0; i < 4; i++) {
                    out[i] = front.getLine(i);
                }
            } else {
                for (int i = 0; i < 4; i++) {
                    out[i] = event.getLine(i);
                }
            }

            pending.onComplete().accept(out);
        } finally {
            cleanup(pending);
        }
    }

    private void cleanup(Pending pending) {
        pending.signBlock().setBlockData(pending.oldSignData(), false);
        if (pending.supportPlaced()) {
            pending.supportBlock().setBlockData(pending.oldSupportData(), false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        final Pending pending = pendingByPlayer.remove(event.getPlayer().getUniqueId());
        if (pending != null) cleanup(pending);
    }
}
