package org.flintstqne.skirmish;

import org.flintstqne.skirmish.LoadoutLogic.LoadoutPreset;
import org.flintstqne.skirmish.StatLogic.PlayerStats;
import org.flintstqne.skirmish.StatLogic.WinsByMode;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Single owner of the plugin's SQLite connection (data.db). Every subsystem that
 * persists anything goes through this class - there are no per-subsystem Db classes
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

    /** Live connection. Callers must not close it - {@link #close()} owns the lifecycle. */
    public Connection getConnection() {
        return connection;
    }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            migrateOldPresetsTableIfPresent(st);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS loadout_presets (
                        preset_id   INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT NOT NULL,
                        name        TEXT NOT NULL,
                        slot_index  INTEGER NOT NULL,
                        items       TEXT NOT NULL,
                        created_at  INTEGER NOT NULL
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

    /**
     * The old schema stored exactly one item per category as five separate columns
     * (one-per-category loadouts); the new bedwars-style shop stores an ordered, possibly-
     * repeating list of purchased keys instead, which doesn't fit that shape at all. There's
     * no real migration between the two - this is a dev/test plugin with no production
     * presets worth preserving - so an old-format table is dropped and rebuilt rather than
     * silently misreading it.
     */
    private void migrateOldPresetsTableIfPresent(Statement st) throws SQLException {
        boolean isOldFormat = false;
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(loadout_presets)")) {
            while (rs.next()) {
                if ("primary_item".equals(rs.getString("name"))) isOldFormat = true;
            }
        }
        if (isOldFormat) {
            logger.warning("loadout_presets is in the old one-item-per-category format - "
                    + "dropping and recreating it for the new shopping-list format (saved presets will be lost).");
            st.executeUpdate("DROP TABLE loadout_presets");
        }
    }

    // ---- loadout presets (design doc §5.1, §7.6) -----------------------------

    private static final String PRESET_COLUMNS = "preset_id, name, slot_index, items";
    /** One "slot:key" pair per entry, comma-joined - catalog keys are plain lowercase_underscore
     * identifiers (see loadout-catalog.yml), so neither delimiter can appear inside one. */
    private static final String ENTRY_DELIMITER = ",";
    private static final String SLOT_KEY_DELIMITER = ":";

    public int createPreset(UUID playerUuid, String name, int slotIndex, Map<Integer, String> layout) throws SQLException {
        String sql = "INSERT INTO loadout_presets (player_uuid, name, slot_index, items, created_at) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, name);
            ps.setInt(3, slotIndex);
            ps.setString(4, serializeLayout(layout));
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private String serializeLayout(Map<Integer, String> layout) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> entry : layout.entrySet()) {
            if (sb.length() > 0) sb.append(ENTRY_DELIMITER);
            sb.append(entry.getKey()).append(SLOT_KEY_DELIMITER).append(entry.getValue());
        }
        return sb.toString();
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

    /** The FK is ON DELETE SET NULL - deleting someone's active preset just clears the pointer. */
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
        String raw = rs.getString("items");
        Map<Integer, String> layout = new LinkedHashMap<>();
        if (raw != null && !raw.isEmpty()) {
            for (String pair : raw.split(ENTRY_DELIMITER)) {
                int split = pair.indexOf(SLOT_KEY_DELIMITER);
                if (split < 0) continue;
                layout.put(Integer.parseInt(pair.substring(0, split)), pair.substring(split + 1));
            }
        }
        return new LoadoutPreset(rs.getInt("preset_id"), rs.getString("name"), rs.getInt("slot_index"), layout);
    }

    // ---- player stats & round history (design doc §5.1, §7.10) --------------

    /** Every write here upserts - a brand-new player's first kill creates their row. */
    private void ensurePlayerRow(UUID playerUuid, String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO player_stats (player_uuid, player_name) VALUES (?, ?) "
                        + "ON CONFLICT(player_uuid) DO UPDATE SET player_name = excluded.player_name")) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    public void incrementKills(UUID playerUuid, String name, int amount) throws SQLException {
        ensurePlayerRow(playerUuid, name);
        addToColumn(playerUuid, "kills", amount);
    }

    public void incrementDeaths(UUID playerUuid, String name, int amount) throws SQLException {
        ensurePlayerRow(playerUuid, name);
        addToColumn(playerUuid, "deaths", amount);
    }

    public void incrementKnifeKills(UUID playerUuid, String name, int amount) throws SQLException {
        ensurePlayerRow(playerUuid, name);
        addToColumn(playerUuid, "knife_kills", amount);
    }

    public void incrementObjectivePoints(UUID playerUuid, String name, int amount) throws SQLException {
        ensurePlayerRow(playerUuid, name);
        addToColumn(playerUuid, "objective_points", amount);
    }

    public void incrementRoundsPlayed(UUID playerUuid, String name) throws SQLException {
        ensurePlayerRow(playerUuid, name);
        addToColumn(playerUuid, "rounds_played", 1);
    }

    /** Also bumps the {@code wins_by_mode} JSON blob for this gamemode - a read-modify-write,
     * safe because every DatabaseManager call in this plugin runs on the main thread. */
    public void incrementRoundsWon(UUID playerUuid, String name, String gamemode) throws SQLException {
        ensurePlayerRow(playerUuid, name);
        addToColumn(playerUuid, "rounds_won", 1);

        Map<String, Integer> wins = WinsByMode.parse(readWinsByModeJson(playerUuid));
        wins.merge(gamemode, 1, Integer::sum);
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE player_stats SET wins_by_mode = ? WHERE player_uuid = ?")) {
            ps.setString(1, WinsByMode.serialize(wins));
            ps.setString(2, playerUuid.toString());
            ps.executeUpdate();
        }
    }

    private String readWinsByModeJson(UUID playerUuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT wins_by_mode FROM player_stats WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("wins_by_mode") : null;
            }
        }
    }

    private void addToColumn(UUID playerUuid, String column, int amount) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE player_stats SET " + column + " = " + column + " + ? WHERE player_uuid = ?")) {
            ps.setInt(1, amount);
            ps.setString(2, playerUuid.toString());
            ps.executeUpdate();
        }
    }

    public PlayerStats getPlayerStats(UUID playerUuid, String fallbackName) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT player_name, kills, deaths, knife_kills, objective_points, rounds_played, "
                        + "rounds_won, wins_by_mode FROM player_stats WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return PlayerStats.empty(fallbackName);
                return new PlayerStats(rs.getString("player_name"), rs.getInt("kills"), rs.getInt("deaths"),
                        rs.getInt("knife_kills"), rs.getInt("objective_points"), rs.getInt("rounds_played"),
                        rs.getInt("rounds_won"), WinsByMode.parse(rs.getString("wins_by_mode")));
            }
        }
    }

    /** One row per leaderboard entry: player name and their value in {@code column}. */
    public record LeaderboardRow(String name, int value) {
    }

    /**
     * @param column must be one of the caller-validated leaderboard columns - interpolated
     *               directly into the SQL since JDBC can't parameterize a column name, so
     *               this must never see a caller-supplied string directly (see StatService).
     */
    public List<LeaderboardRow> getLeaderboard(String column, int limit) throws SQLException {
        List<LeaderboardRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT player_name, " + column + " AS value FROM player_stats "
                        + "ORDER BY value DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new LeaderboardRow(rs.getString("player_name"), rs.getInt("value")));
            }
        }
        return rows;
    }

    public void recordRoundHistory(String gamemode, String winner, long startedAt, long endedAt,
                                   int finalScoreA, int finalScoreB) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO round_history (gamemode, winner, started_at, ended_at, final_score_a, final_score_b) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, gamemode);
            ps.setString(2, winner);
            ps.setLong(3, startedAt);
            ps.setLong(4, endedAt);
            ps.setInt(5, finalScoreA);
            ps.setInt(6, finalScoreB);
            ps.executeUpdate();
        }
    }

    /** One row of {@code round_history}, most recent first. */
    public record RoundHistoryRow(String gamemode, String winner, long startedAt, long endedAt,
                                  int finalScoreA, int finalScoreB) {
    }

    /** No per-player link exists in this schema (design doc §5.1) - this is the server's
     * recent rounds, not any one player's. */
    public List<RoundHistoryRow> getRecentRounds(int limit) throws SQLException {
        List<RoundHistoryRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT gamemode, winner, started_at, ended_at, final_score_a, final_score_b "
                        + "FROM round_history ORDER BY round_id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new RoundHistoryRow(rs.getString("gamemode"), rs.getString("winner"),
                            rs.getLong("started_at"), rs.getLong("ended_at"),
                            rs.getInt("final_score_a"), rs.getInt("final_score_b")));
                }
            }
        }
        return rows;
    }

    /** Wipes one player's lifetime stats - the row is recreated fresh on their next stat write. */
    public void resetPlayerStats(UUID playerUuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM player_stats WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid.toString());
            ps.executeUpdate();
        }
    }

    public void resetAllStats() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("DELETE FROM player_stats");
        }
    }
}
