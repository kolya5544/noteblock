package ax.nk.noteblock.game.timeline.render;

import ax.nk.noteblock.game.timeline.BlockPos;
import ax.nk.noteblock.game.timeline.InstrumentPalette;
import ax.nk.noteblock.game.timeline.NoteEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/** Responsible for building the track in the world and repainting notes. */
public final class TrackRenderer {

    private final int baseY;
    private final int trackY;
    private final int layerCount;
    private final int trackPitchWidth;

    private final int originX;
    private final int originZ;

    public TrackRenderer(int baseY, int trackY, int layerCount, int trackPitchWidth, int originX, int originZ) {
        this.baseY = baseY;
        this.trackY = trackY;
        this.layerCount = layerCount;
        this.trackPitchWidth = trackPitchWidth;
        this.originX = originX;
        this.originZ = originZ;
    }

    public int layerY(int layerIndex) {
        return trackY + layerIndex;
    }

    public void buildTrack(World world, Player player, int trackLength) {
        if (world == null) return;

        // Build BASE_Y floor once, and clear all layers above.
        for (int dx = 0; dx < trackLength; dx++) {
            for (int dz = 0; dz < trackPitchWidth; dz++) {
                final int x = originX + dx;
                final int z = originZ + dz;

                final Material floor = (dz & 1) == 0 ? Material.BROWN_CONCRETE : Material.TERRACOTTA;
                world.getBlockAt(x, baseY, z).setType(floor, false);

                for (int layer = 0; layer < layerCount; layer++) {
                    world.getBlockAt(x, layerY(layer), z).setType(Material.AIR, false);
                }

                // Only keep the time borders black; pitch edges remain playable alternating floor.
                if (dx == 0 || dx == trackLength - 1) {
                    world.getBlockAt(x, baseY, z).setType(Material.BLACK_CONCRETE, false);
                }
            }
        }

        // Direction indicator strip
        final int zArrow = originZ + trackPitchWidth;
        for (int dx = 0; dx < trackLength; dx++) {
            world.getBlockAt(originX + dx, baseY, zArrow).setType(Material.DARK_OAK_PLANKS, false);
        }
    }

    public void redrawNotes(World world, int trackLength, List<Map<Integer, List<NoteEvent>>> scoreByLayer) {
        if (world == null || scoreByLayer == null) return;

        for (int layer = 0; layer < layerCount; layer++) {
            final Map<Integer, List<NoteEvent>> map = scoreByLayer.get(layer);
            if (map == null || map.isEmpty()) continue;

            final int y = layerY(layer);
            for (Map.Entry<Integer, List<NoteEvent>> e : map.entrySet()) {
                final int tickIndex = e.getKey();
                if (tickIndex < 0 || tickIndex >= trackLength) continue;

                final List<NoteEvent> list = e.getValue();
                if (list == null) continue;

                for (NoteEvent n : list) {
                    final BlockPos pos = n.pos();
                    final int dx = pos.x() - originX;
                    final int dz = pos.z() - originZ;
                    if (dx < 0 || dx >= trackLength) continue;
                    if (dz < 0 || dz >= trackPitchWidth) continue;

                    final Material marker = InstrumentPalette.byId(n.instrumentId()).marker;
                    world.getBlockAt(pos.x(), y, pos.z()).setType(marker, false);
                }
            }
        }
    }

    public void clearWorldColumnsOutsideLength(World world, int startInclusive, int endExclusive) {
        if (world == null) return;

        for (int dx = startInclusive; dx < endExclusive; dx++) {
            final int x = originX + dx;

            for (int dz = 0; dz < trackPitchWidth; dz++) {
                final int z = originZ + dz;

                // Clear all note layers.
                for (int layer = 0; layer < layerCount; layer++) {
                    world.getBlockAt(x, layerY(layer), z).setType(Material.AIR, false);
                }

                // Optional: clear floor too.
                world.getBlockAt(x, baseY, z).setType(Material.AIR, false);
            }

            // Clear arrow strip column too.
            final int zArrow = originZ + trackPitchWidth;
            world.getBlockAt(x, baseY, zArrow).setType(Material.AIR, false);
        }
    }
}
