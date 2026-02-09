package ax.nk.noteblock.persistence;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Very small SQLite bootstrapper.
 *
 * Keeps a single connection open for the plugin lifetime.
 * (SQLite is file-based; a single shared connection is fine for our basic use.
 * If we later add heavy concurrent reads/writes, we can swap this for a pool.)
 */
public final class SqliteDatabase {

    private final Plugin plugin;
    private final File dbFile;

    private Connection connection;

    public SqliteDatabase(Plugin plugin, File dbFile) {
        this.plugin = plugin;
        this.dbFile = dbFile;
    }

    public synchronized void open() throws SQLException {
        if (connection != null && !connection.isClosed()) return;

        final File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }

        // Ensures the JDBC driver gets loaded.
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ignored) {
            // DriverManager will still often work via ServiceLoader, but keep going.
        }

        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

        // Basic pragmas: WAL improves concurrency and reduces corruption risk.
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("PRAGMA foreign_keys=ON;");
        }

        migrate();
        plugin.getLogger().info("SQLite opened: " + dbFile.getAbsolutePath());
    }

    private void migrate() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS songs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        owner_uuid TEXT NOT NULL,
                        name TEXT NOT NULL,
                        data_json TEXT NOT NULL,
                        created_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_songs_owner ON songs(owner_uuid);");
        }
    }

    public synchronized Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            open();
        }
        return connection;
    }

    public synchronized void close() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to close SQLite connection: " + e.getMessage());
        } finally {
            connection = null;
        }
    }
}

