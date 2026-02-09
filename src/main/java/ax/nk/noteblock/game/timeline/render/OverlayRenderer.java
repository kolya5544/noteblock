package ax.nk.noteblock.game.timeline.render;

import ax.nk.noteblock.game.timeline.BlockPos;
import ax.nk.noteblock.game.timeline.util.TimelineMath;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.HashMap;
import java.util.Map;

/** Renders and tracks temporary overlays like playhead and range markers. */
public final class OverlayRenderer {

    private final int originX;
    private final int originZ;
    private final int trackPitchWidth;

    private final Map<BlockPos, Material> playheadOverlay = new HashMap<>();
    private final Map<BlockPos, Material> rangeOverlay = new HashMap<>();

    public OverlayRenderer(int originX, int originZ, int trackPitchWidth) {
        this.originX = originX;
        this.originZ = originZ;
        this.trackPitchWidth = trackPitchWidth;
    }

    public void clearPlayhead(World world) {
        if (world == null) {
            playheadOverlay.clear();
            return;
        }
        for (Map.Entry<BlockPos, Material> e : playheadOverlay.entrySet()) {
            final BlockPos pos = e.getKey();
            final Block b = world.getBlockAt(pos.x(), pos.y(), pos.z());
            if (b.getType() == Material.RED_STAINED_GLASS) {
                b.setType(e.getValue(), false);
            }
        }
        playheadOverlay.clear();
    }

    public void drawPlayhead(World world, int tickIndex, int y) {
        if (world == null) return;
        clearPlayhead(world);
        final int x = originX + tickIndex;
        for (int dz = 0; dz < trackPitchWidth; dz++) {
            final int z = originZ + dz;
            final Block b = world.getBlockAt(x, y, z);
            if (b.getType() == Material.AIR) {
                final BlockPos pos = BlockPos.from(b.getLocation());
                playheadOverlay.put(pos, Material.AIR);
                b.setType(Material.RED_STAINED_GLASS, false);
            }
        }
    }

    public void clearRange(World world) {
        if (world == null) {
            rangeOverlay.clear();
            return;
        }
        for (Map.Entry<BlockPos, Material> e : rangeOverlay.entrySet()) {
            final BlockPos pos = e.getKey();
            final Block b = world.getBlockAt(pos.x(), pos.y(), pos.z());
            if (b.getType() == Material.BLUE_STAINED_GLASS || b.getType() == Material.ORANGE_STAINED_GLASS) {
                b.setType(e.getValue(), false);
            }
        }
        rangeOverlay.clear();
    }

    public void redrawRange(World world, Integer begin, Integer end, int trackLength, int activeLayerY) {
        clearRange(world);
        if (world == null) return;
        if (trackLength <= 0) return;

        if (begin != null) {
            drawRangeMarker(world, TimelineMath.clamp(begin, 0, trackLength - 1), activeLayerY, Material.BLUE_STAINED_GLASS);
        }
        if (end != null) {
            drawRangeMarker(world, TimelineMath.clamp(end, 0, trackLength - 1), activeLayerY, Material.ORANGE_STAINED_GLASS);
        }
    }

    private void drawRangeMarker(World world, int timeIndex, int y, Material mat) {
        final int x = originX + timeIndex;
        for (int dz = 0; dz < trackPitchWidth; dz++) {
            final int z = originZ + dz;
            final Block b = world.getBlockAt(x, y, z);
            if (b.getType() == Material.AIR) {
                final BlockPos pos = BlockPos.from(b.getLocation());
                rangeOverlay.put(pos, Material.AIR);
                b.setType(mat, false);
            }
        }
    }
}

