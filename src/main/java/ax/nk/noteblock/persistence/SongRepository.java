package ax.nk.noteblock.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class SongRepository {

    private final SqliteDatabase db;

    public SongRepository(SqliteDatabase db) {
        this.db = Objects.requireNonNull(db);
    }

    public void insertSong(UUID ownerUuid, String name, String dataJson) throws SQLException {
        Objects.requireNonNull(ownerUuid);
        Objects.requireNonNull(name);
        Objects.requireNonNull(dataJson);

        final long now = Instant.now().toEpochMilli();

        final Connection c = db.connection();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO songs(owner_uuid, name, data_json, created_at_ms, updated_at_ms) VALUES(?,?,?,?,?)")) {
            ps.setString(1, ownerUuid.toString());
            ps.setString(2, name);
            ps.setString(3, dataJson);
            ps.setLong(4, now);
            ps.setLong(5, now);
            ps.executeUpdate();
        }
    }

    public List<SongRow> listSongs(UUID ownerUuid, int limit, int offset) throws SQLException {
        Objects.requireNonNull(ownerUuid);
        limit = Math.max(1, Math.min(54, limit));
        offset = Math.max(0, offset);

        final Connection c = db.connection();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, owner_uuid, name, created_at_ms, updated_at_ms FROM songs WHERE owner_uuid = ? ORDER BY updated_at_ms DESC LIMIT ? OFFSET ?")) {
            ps.setString(1, ownerUuid.toString());
            ps.setInt(2, limit);
            ps.setInt(3, offset);

            try (ResultSet rs = ps.executeQuery()) {
                final List<SongRow> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new SongRow(
                            rs.getLong("id"),
                            rs.getString("owner_uuid"),
                            rs.getString("name"),
                            rs.getLong("created_at_ms"),
                            rs.getLong("updated_at_ms")
                    ));
                }
                return out;
            }
        }
    }

    public SongDataRow getSongById(UUID ownerUuid, long id) throws SQLException {
        Objects.requireNonNull(ownerUuid);

        final Connection c = db.connection();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, owner_uuid, name, data_json, created_at_ms, updated_at_ms FROM songs WHERE owner_uuid = ? AND id = ?")) {
            ps.setString(1, ownerUuid.toString());
            ps.setLong(2, id);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new SongDataRow(
                        rs.getLong("id"),
                        rs.getString("owner_uuid"),
                        rs.getString("name"),
                        rs.getString("data_json"),
                        rs.getLong("created_at_ms"),
                        rs.getLong("updated_at_ms")
                );
            }
        }
    }

    public boolean deleteSong(UUID ownerUuid, long id) throws SQLException {
        Objects.requireNonNull(ownerUuid);

        final Connection c = db.connection();
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM songs WHERE owner_uuid = ? AND id = ?")) {
            ps.setString(1, ownerUuid.toString());
            ps.setLong(2, id);
            final int affected = ps.executeUpdate();
            return affected > 0;
        }
    }
}
