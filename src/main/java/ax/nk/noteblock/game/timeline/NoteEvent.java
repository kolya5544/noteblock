package ax.nk.noteblock.game.timeline;

/**
 * A note placed on the track.
 *
 * @param pos          world position (x/z encode time/pitch; y is the layer height)
 * @param instrumentId palette instrument id
 * @param pitch        pitch row (0..TRACK_PITCH_WIDTH-1)
 */
public record NoteEvent(BlockPos pos, int instrumentId, int pitch) {
}
