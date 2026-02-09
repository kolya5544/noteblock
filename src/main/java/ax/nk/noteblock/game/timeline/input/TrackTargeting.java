package ax.nk.noteblock.game.timeline.input;

import org.bukkit.FluidCollisionMode;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;

/** Raycast helpers for selecting a track cell/time index. */
public final class TrackTargeting {

    private final int originX;
    private final int originZ;
    private final int trackPitchWidth;
    private final double rayDistance;

    public TrackTargeting(int originX, int originZ, int trackPitchWidth, double rayDistance) {
        this.originX = originX;
        this.originZ = originZ;
        this.trackPitchWidth = trackPitchWidth;
        this.rayDistance = rayDistance;
    }

    public Block raycastTrackCellBlock(Player player, World sessionWorld, int trackLength, int activeLayerY) {
        if (player == null || sessionWorld == null) return null;
        if (!sessionWorld.equals(player.getWorld())) return null;

        final RayTraceResult rr = player.rayTraceBlocks(rayDistance, FluidCollisionMode.NEVER);
        if (rr == null) return null;

        Block hit = rr.getHitBlock();
        if (hit == null) return null;

        // When you target the side of a block, pick the adjacent block.
        final BlockFace face = rr.getHitBlockFace();
        if (face != null) hit = hit.getRelative(face);

        final int dx = hit.getX() - originX;
        final int dz = hit.getZ() - originZ;
        if (dx < 0 || dx >= trackLength) return null;
        if (dz < 0 || dz >= trackPitchWidth) return null;

        return sessionWorld.getBlockAt(hit.getX(), activeLayerY, hit.getZ());
    }

    public Integer raycastTimeIndex(Player player, World sessionWorld, int trackLength, int activeLayerY) {
        final Block b = raycastTrackCellBlock(player, sessionWorld, trackLength, activeLayerY);
        if (b == null) return null;
        final int dx = b.getX() - originX;
        if (dx < 0 || dx >= trackLength) return null;
        return dx;
    }
}

