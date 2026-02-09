package ax.nk.noteblock.game.timeline.edit;

import ax.nk.noteblock.game.timeline.*;
import ax.nk.noteblock.game.timeline.score.TimelineCell;
import ax.nk.noteblock.game.timeline.score.TimelineScore;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** World-editing operations for placing/removing/previewing notes on the timeline. */
public final class TimelineEditor {

    private final int trackY;
    private final int layerCount;
    private final int originX;
    private final int originZ;
    private final int trackPitchWidth;

    public TimelineEditor(int trackY, int layerCount, int originX, int originZ, int trackPitchWidth) {
        this.trackY = trackY;
        this.layerCount = layerCount;
        this.originX = originX;
        this.originZ = originZ;
        this.trackPitchWidth = trackPitchWidth;
    }

    public TimelineCell toCell(Location loc, int trackLength) {
        final int y = loc.getBlockY();
        if (y < trackY || y >= trackY + layerCount) return null;

        final int dx = loc.getBlockX() - originX;
        final int dz = loc.getBlockZ() - originZ;

        if (dx < 0 || dx >= trackLength) return null;
        if (dz < 0 || dz >= trackPitchWidth) return null;

        return new TimelineCell(dx, dz);
    }

    public void upsertNote(TimelineScore score, Location loc, int instrumentId, int layerIndex, int trackLength) {
        final TimelineCell cell = toCell(loc, trackLength);
        if (cell == null) return;

        final BlockPos pos = BlockPos.from(loc);
        score.upsertNote(cell, pos, instrumentId, cell.pitch(), layerIndex);
    }

    public NoteEvent removeNoteAt(TimelineScore score, Block block) {
        final BlockPos pos = BlockPos.from(block.getLocation());
        final NoteEvent removed = score.removeNoteAt(pos);
        if (removed == null) return null;

        final World w = block.getWorld();
        final Material old = block.getType();
        block.setType(Material.AIR, false);

        w.spawnParticle(Particle.BLOCK, block.getLocation().add(0.5, 0.5, 0.5), 12, 0.2, 0.2, 0.2, old.createBlockData());
        w.playSound(block.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_WOOL_BREAK, SoundCategory.BLOCKS, 0.8f, 1.0f);

        return removed;
    }

    public void previewNote(Player player, int instrumentId, int pitchRow) {
        if (player == null) return;
        final InstrumentPalette palette = InstrumentPalette.byId(instrumentId);
        final float pitch = pitchFromRow(pitchRow);
        player.playSound(player.getLocation(), palette.sound, SoundCategory.RECORDS, 1.0f, pitch);
    }

    public float pitchFromRow(int row) {
        return 0.5f + (row / (float) (trackPitchWidth - 1)) * 1.5f;
    }
}
