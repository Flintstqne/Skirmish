package org.flintstqne.skirmish;

import org.flintstqne.skirmish.LoadoutLogic.LoadoutCatalog;
import org.flintstqne.skirmish.LoadoutLogic.LoadoutPreset;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    // ---- loadout presets (design doc §5.1, §7.6) -----------------------------

    private static final String PRESET_COLUMNS =
            "preset_id, name, slot_index, primary_item, secondary_item, armor_item, potion_item, tool_item";

    public int createPreset(UUID playerUuid, String name, int slotIndex,
                            Map<LoadoutCatalog.Category, String> selection) throws SQLException {
        String sql = "INSERT INTO loadout_presets "
                + "(player_uuid, name, slot_index, primary_item, secondary_item, armor_item, potion_item, tool_item, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, name);
            ps.setInt(3, slotIndex);
            ps.setString(4, selection.get(LoadoutCatalog.Category.PRIMARY));
            ps.setString(5, selection.get(LoadoutCatalog.Category.SECONDARY));
            ps.setString(6, selection.get(LoadoutCatalog.Category.ARMOR));
            ps.setString(7, selection.get(LoadoutCatalog.Category.POTION));
            ps.setString(8, selection.get(LoadoutCatalog.Category.TOOL));
            ps.setLong(9, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    public List<LoadoutPreset> listPresets(UUID playerUuid) throws SQLException {
        List<LoadoutPreset> presets = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT " + PRESET_COLUMNS + " FROM loadout_presets WHERE player_uuid = ? ORDER BY slot_index")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) presets.add(readPreset(rs));
            }
        }
        return presets;
    }

    public LoadoutPreset getPreset(int presetId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT " + PRESET_COLUMNS + " FROM loadout_presets WHERE preset_id = ?")) {
            ps.setInt(1, presetId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readPreset(rs) : null;
            }
        }
    }

    public int countPresets(UUID playerUuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM loadout_presets WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public void renamePreset(int presetId, String newName) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE loadout_presets SET name = ? WHERE preset_id = ?")) {
            ps.setString(1, newName);
            ps.setInt(2, presetId);
            ps.executeUpdate();
        }
    }

    /** The FK is ON DELETE SET NULL — deleting someone's active preset just clears the pointer. */
    public void deletePreset(int presetId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM loadout_presets WHERE preset_id = ?")) {
            ps.setInt(1, presetId);
            ps.executeUpdate();
        }
    }

    /** @param presetId null clears the active pointer without deleting anything. */
    public void setActivePreset(UUID playerUuid, Integer presetId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO active_loadout (player_uuid, preset_id) VALUES (?, ?) "
                        + "ON CONFLICT(player_uuid) DO UPDATE SET preset_id = excluded.preset_id")) {
            ps.setString(1, playerUuid.toString());
            if (presetId == null) ps.setNull(2, Types.INTEGER); else ps.setInt(2, presetId);
            ps.executeUpdate();
        }
    }

    /** @return null if the player has no active preset, or it was deleted out from under them. */
    public LoadoutPreset getActivePreset(UUID playerUuid) throws SQLException {
        Integer presetId;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT preset_id FROM active_loadout WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int id = rs.getInt("preset_id");
                presetId = rs.wasNull() ? null : id;
            }
        }
        return presetId == null ? null : getPreset(presetId);
    }

    private LoadoutPreset readPreset(ResultSet rs) throws SQLException {
        Map<LoadoutCatalog.Category, String> selection = new EnumMap<>(LoadoutCatalog.Category.class);
        putIfPresent(selection, LoadoutCatalog.Category.PRIMARY, rs.getString("primary_item"));
        putIfPresent(selection, LoadoutCatalog.Category.SECONDARY, rs.getString("secondary_item"));
        putIfPresent(selection, LoadoutCatalog.Category.ARMOR, rs.getString("armor_item"));
        putIfPresent(selection, LoadoutCatalog.Category.POTION, rs.getString("potion_item"));
        putIfPresent(selection, LoadoutCatalog.Category.TOOL, rs.getString("tool_item"));
        return new LoadoutPreset(rs.getInt("preset_id"), rs.getString("name"), rs.getInt("slot_index"), selection);
    }

    private void putIfPresent(Map<LoadoutCatalog.Category, String> map, LoadoutCatalog.Category category, String value) {
        if (value != null) map.put(category, value);
    }
}
