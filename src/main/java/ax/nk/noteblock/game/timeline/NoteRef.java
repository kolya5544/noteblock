package ax.nk.noteblock.game.timeline;

/**
 * Reverse index for note removal: tells which layer and tick a placed block belongs to.
 */
record NoteRef(int layerIndex, int tickIndex) {
}

