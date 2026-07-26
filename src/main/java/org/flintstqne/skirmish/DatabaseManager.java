package org.flintstqne.skirmish;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Single owner of the plugin's SQLite connection (data.db). Every subsystem that
 * persists anything goes through this class — there are no per-subsystem Db classes
 * (design doc §5). Query/update methods are added here as subsystems need them.
 */
public final class DatabaseManager {

    private final File dataFolder;
    private final Logger logger;
    private Connection connection;

    public DatabaseManager(File dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
    }

    public void open() throws SQLException {
        File file = new File(dataFolder, "data.db");
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        try {
            // Plugin classloaders don't always pick the driver up via ServiceLoader.
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("Shaded sqlite-jdbc driver missing", e);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
        }
        createSchema();
    }

    public void close() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException e) {
            logger.warning("Failed closing data.db: " + e.getMessage());
        }
        connection = null;
    }

    /** Live connection. Callers must not close it — {@link #close()} owns the lifecycle. */
    public Connection getConnection() {
        return connection;
    }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS loadout_presets (
                        preset_id      INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid    TEXT NOT NULL,
                        name           TEXT NOT NULL,
                        slot_index     INTEGER NOT NULL,
                        primary_item   TEXT,
                        secondary_item TEXT,
                        armor_item     TEXT,
                        potion_item    TEXT,
                        tool_item      TEXT,
                        created_at     INTEGER NOT NULL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS active_loadout (
                        player_uuid TEXT PRIMARY KEY,
                        preset_id   INTEGER REFERENCES loadout_presets(preset_id) ON DELETE SET NULL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_stats (
                        player_uuid      TEXT PRIMARY KEY,
                        player_name      TEXT NOT NULL,
                        kills            INTEGER DEFAULT 0,
                        deaths           INTEGER DEFAULT 0,
                        knife_kills      INTEGER DEFAULT 0,
                        objective_points INTEGER DEFAULT 0,
                        rounds_played    INTEGER DEFAULT 0,
                        rounds_won       INTEGER DEFAULT 0,
                        wins_by_mode     TEXT
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS round_history (
                        round_id      INTEGER PRIMARY KEY AUTOINCREMENT,
                        gamemode      TEXT NOT NULL,
                        winner        TEXT,
                        started_at    INTEGER NOT NULL,
                        ended_at      INTEGER,
                        final_score_a INTEGER,
                        final_score_b INTEGER
                    )""");
        }
    }
}
