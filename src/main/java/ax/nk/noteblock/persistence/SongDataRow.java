package ax.nk.noteblock.persistence;

/** Full song row including JSON payload. */
public record SongDataRow(long id, String ownerUuid, String name, String dataJson, long createdAtMs, long updatedAtMs) {
}

