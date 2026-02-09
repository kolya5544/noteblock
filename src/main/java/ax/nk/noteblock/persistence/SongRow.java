package ax.nk.noteblock.persistence;

/** Lightweight row for listing songs. */
public record SongRow(long id, String ownerUuid, String name, long createdAtMs, long updatedAtMs) {
}

