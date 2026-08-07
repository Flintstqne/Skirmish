package org.flintstqne.skirmish;

import org.flintstqne.skirmish.LoadoutLogic.LoadoutPreset;
import org.flintstqne.skirmish.StatLogic.PlayerStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Schema from design doc §5.1 - all of it lives in one data.db owned by DatabaseManager. */
class DatabaseManagerTest {

    @TempDir
    Path tempDir;

    private DatabaseManager db;

    @BeforeEach
    void setUp() throws SQLException {
        db = new DatabaseManager(tempDir.toFile(), Logger.getLogger("test"));
        db.open();
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void createsDataDbFile() {
        assertTrue(new File(tempDir.toFile(), "data.db").isFile());
    }

    @Test
    void createsEverySchemaTable() throws SQLException {
        Set<String> tables = new HashSet<>();
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
            while (rs.next()) tables.add(rs.getString("name"));
        }
        assertTrue(tables.containsAll(Set.of(
                "loadout_presets", "active_loadout", "player_stats", "round_history")), tables.toString());
    }

    @Test
    void openIsIdempotentAcrossRestarts() throws SQLException {
        try (Statement st = db.getConnection().createStatement()) {
            st.executeUpdate("INSERT INTO player_stats (player_uuid, player_name, kills) VALUES ('u1', 'Ally', 7)");
        }
        db.close();

        db = new DatabaseManager(tempDir.toFile(), Logger.getLogger("test"));
        db.open();
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT kills FROM player_stats WHERE player_uuid = 'u1'")) {
            assertTrue(rs.next());
            assertEquals(7, rs.getInt("kills"));
        }
    }

    @Test
    void deletingAPresetClearsItAsSomeonesActiveLoadout() throws SQLException {
        try (Statement st = db.getConnection().createStatement()) {
            st.executeUpdate("""
                    INSERT INTO loadout_presets (preset_id, player_uuid, name, slot_index, items, created_at)
                    VALUES (1, 'u1', 'Rusher', 0, 'ak47', 0)""");
            st.executeUpdate("INSERT INTO active_loadout (player_uuid, preset_id) VALUES ('u1', 1)");
            st.executeUpdate("DELETE FROM loadout_presets WHERE preset_id = 1");
        }
        // FK is ON DELETE SET NULL, and PRAGMA foreign_keys is enabled - a deleted preset
        // must not leave a dangling active_loadout pointer.
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT preset_id FROM active_loadout WHERE player_uuid = 'u1'")) {
            assertTrue(rs.next());
            rs.getInt("preset_id");
            assertTrue(rs.wasNull());
        }
    }

    // ---- loadout presets, via the real API (design doc §7.6) ------------------
    // Presets are now an exact inventory layout - slot index (or a negative armor code) to
    // catalog key - not one item per category.

    private Map<Integer, String> layout(String... keys) {
        Map<Integer, String> layout = new LinkedHashMap<>();
        for (int i = 0; i < keys.length; i++) layout.put(i, keys[i]);
        return layout;
    }

    @Test
    void createdPresetRoundTripsExactlyWhatWasStored() throws SQLException {
        UUID player = UUID.randomUUID();
        Map<Integer, String> layout = Map.of(0, "ak47", -2, "netherite_chestplate");
        int id = db.createPreset(player, "Rusher Kit", 0, layout);

        LoadoutPreset preset = db.getPreset(id);
        assertEquals("Rusher Kit", preset.name());
        assertEquals(0, preset.slotIndex());
        assertEquals(layout, preset.layout());
    }

    @Test
    void repeatedKeysAtDifferentSlotsRoundTrip() throws SQLException {
        // A potion bought three times occupies three different slots, not one count.
        UUID player = UUID.randomUUID();
        Map<Integer, String> layout = layout("ak47", "potion_heal", "potion_heal", "potion_heal");
        int id = db.createPreset(player, "Healer", 0, layout);

        assertEquals(layout, db.getPreset(id).layout());
    }

    @Test
    void emptyPresetRoundTripsAsAnEmptyMap() throws SQLException {
        UUID player = UUID.randomUUID();
        int id = db.createPreset(player, "Blank", 0, Map.of());

        assertTrue(db.getPreset(id).layout().isEmpty());
    }

    @Test
    void listPresetsIsScopedToOnePlayerAndOrderedBySlot() throws SQLException {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        db.createPreset(a, "A-Second", 1, layout("m4a1"));
        db.createPreset(a, "A-First", 0, layout("ak47"));
        db.createPreset(b, "B-Only", 0, layout("uzi"));

        List<LoadoutPreset> aPresets = db.listPresets(a);
        assertEquals(List.of("A-First", "A-Second"), aPresets.stream().map(LoadoutPreset::name).toList());
        assertEquals(1, db.listPresets(b).size());
    }

    @Test
    void countPresetsMatchesListSize() throws SQLException {
        UUID player = UUID.randomUUID();
        assertEquals(0, db.countPresets(player));
        db.createPreset(player, "One", 0, layout("ak47"));
        db.createPreset(player, "Two", 1, layout("m4a1"));
        assertEquals(2, db.countPresets(player));
    }

    @Test
    void renameChangesNameButNothingElse() throws SQLException {
        UUID player = UUID.randomUUID();
        int id = db.createPreset(player, "Old Name", 0, layout("ak47"));
        db.renamePreset(id, "New Name");

        LoadoutPreset preset = db.getPreset(id);
        assertEquals("New Name", preset.name());
        assertEquals(layout("ak47"), preset.layout());
    }

    @Test
    void deletingAPresetRemovesItFromListAndGet() throws SQLException {
        UUID player = UUID.randomUUID();
        int id = db.createPreset(player, "Gone Soon", 0, layout("ak47"));
        db.deletePreset(id);

        assertNull(db.getPreset(id));
        assertTrue(db.listPresets(player).isEmpty());
    }

    @Test
    void activePresetDefaultsToNullAndCanBeSetAndCleared() throws SQLException {
        UUID player = UUID.randomUUID();
        int id = db.createPreset(player, "Main", 0, layout("ak47"));

        assertNull(db.getActivePreset(player), "no preset set active yet");

        db.setActivePreset(player, id);
        assertEquals("Main", db.getActivePreset(player).name());

        // Upsert: setting it again for the same player must update, not duplicate/conflict.
        db.setActivePreset(player, id);
        assertEquals(id, db.getActivePreset(player).id());

        db.setActivePreset(player, null);
        assertNull(db.getActivePreset(player));
    }

    @Test
    void activePresetGoesNullWhenThatPresetIsDeleted() throws SQLException {
        UUID player = UUID.randomUUID();
        int id = db.createPreset(player, "Main", 0, layout("ak47"));
        db.setActivePreset(player, id);

        db.deletePreset(id);

        assertNull(db.getActivePreset(player));
    }

    // ---- player stats & round history (design doc §5.1, §7.10) ----------------

    @Test
    void unknownPlayerGetsEmptyStatsRatherThanAnError() throws SQLException {
        PlayerStats stats = db.getPlayerStats(UUID.randomUUID(), "Nobody");
        assertEquals("Nobody", stats.name());
        assertEquals(0, stats.kills());
        assertTrue(stats.winsByMode().isEmpty());
    }

    @Test
    void firstIncrementCreatesTheRowRatherThanNeedingOneUpFront() throws SQLException {
        UUID player = UUID.randomUUID();
        db.incrementKills(player, "Ally", 1);
        assertEquals(1, db.getPlayerStats(player, "Ally").kills());
    }

    @Test
    void incrementsAccumulateAcrossCalls() throws SQLException {
        UUID player = UUID.randomUUID();
        db.incrementKills(player, "Ally", 1);
        db.incrementKills(player, "Ally", 1);
        db.incrementDeaths(player, "Ally", 1);
        db.incrementKnifeKills(player, "Ally", 1);
        db.incrementObjectivePoints(player, "Ally", 3);

        PlayerStats stats = db.getPlayerStats(player, "Ally");
        assertEquals(2, stats.kills());
        assertEquals(1, stats.deaths());
        assertEquals(1, stats.knifeKills());
        assertEquals(3, stats.objectivePoints());
    }

    @Test
    void playerNameUpdatesOnEveryIncrement() throws SQLException {
        // Covers a rename between rounds - stats must follow the UUID, and the display
        // name should reflect however they're named now, not when the row was first created.
        UUID player = UUID.randomUUID();
        db.incrementKills(player, "OldName", 1);
        db.incrementKills(player, "NewName", 1);
        assertEquals("NewName", db.getPlayerStats(player, "fallback").name());
    }

    @Test
    void roundsWonAlsoUpdatesTheWinsByModeBreakdown() throws SQLException {
        UUID player = UUID.randomUUID();
        db.incrementRoundsWon(player, "Ally", "TDM");
        db.incrementRoundsWon(player, "Ally", "TDM");
        db.incrementRoundsWon(player, "Ally", "KOTH");

        PlayerStats stats = db.getPlayerStats(player, "Ally");
        assertEquals(3, stats.roundsWon());
        assertEquals(Map.of("TDM", 2, "KOTH", 1), stats.winsByMode());
    }

    @Test
    void leaderboardOrdersDescendingAndRespectsTheLimit() throws SQLException {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        db.incrementKills(a, "Low", 1);
        db.incrementKills(b, "High", 10);
        db.incrementKills(c, "Mid", 5);

        List<DatabaseManager.LeaderboardRow> top2 = db.getLeaderboard("kills", 2);
        assertEquals(2, top2.size());
        assertEquals("High", top2.get(0).name());
        assertEquals(10, top2.get(0).value());
        assertEquals("Mid", top2.get(1).name());
    }

    @Test
    void roundHistoryRoundTrips() throws SQLException {
        db.recordRoundHistory("TDM", "RED", 1000L, 2000L, 75, 40);
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM round_history")) {
            assertTrue(rs.next());
            assertEquals("TDM", rs.getString("gamemode"));
            assertEquals("RED", rs.getString("winner"));
            assertEquals(1000L, rs.getLong("started_at"));
            assertEquals(2000L, rs.getLong("ended_at"));
            assertEquals(75, rs.getInt("final_score_a"));
            assertEquals(40, rs.getInt("final_score_b"));
        }
    }

    @Test
    void roundHistoryAllowsANullWinnerForADraw() throws SQLException {
        db.recordRoundHistory("FFA", null, 1000L, 2000L, 0, 0);
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT winner FROM round_history")) {
            assertTrue(rs.next());
            rs.getString("winner");
            assertTrue(rs.wasNull());
        }
    }
}
