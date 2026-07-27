package org.flintstqne.skirmish;

import org.flintstqne.skirmish.LoadoutLogic.LoadoutCatalog;
import org.flintstqne.skirmish.LoadoutLogic.LoadoutPreset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Schema from design doc §5.1 — all of it lives in one data.db owned by DatabaseManager. */
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
                    INSERT INTO loadout_presets (preset_id, player_uuid, name, slot_index, created_at)
                    VALUES (1, 'u1', 'Rusher', 0, 0)""");
            st.executeUpdate("INSERT INTO active_loadout (player_uuid, preset_id) VALUES ('u1', 1)");
            st.executeUpdate("DELETE FROM loadout_presets WHERE preset_id = 1");
        }
        // FK is ON DELETE SET NULL, and PRAGMA foreign_keys is enabled — a deleted preset
        // must not leave a dangling active_loadout pointer.
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT preset_id FROM active_loadout WHERE player_uuid = 'u1'")) {
            assertTrue(rs.next());
            rs.getInt("preset_id");
            assertTrue(rs.wasNull());
        }
    }

    // ---- loadout presets, via the real API (design doc §7.6) ------------------

    private Map<LoadoutCatalog.Category, String> selection(String primary, String armor) {
        Map<LoadoutCatalog.Category, String> selection = new EnumMap<>(LoadoutCatalog.Category.class);
        if (primary != null) selection.put(LoadoutCatalog.Category.PRIMARY, primary);
        if (armor != null) selection.put(LoadoutCatalog.Category.ARMOR, armor);
        return selection;
    }

    @Test
    void createdPresetRoundTripsExactlyWhatWasStored() throws SQLException {
        UUID player = UUID.randomUUID();
        int id = db.createPreset(player, "Rusher Kit", 0, selection("ak47", "vest_heavy"));

        LoadoutPreset preset = db.getPreset(id);
        assertEquals("Rusher Kit", preset.name());
        assertEquals(0, preset.slotIndex());
        assertEquals("ak47", preset.selection().get(LoadoutCatalog.Category.PRIMARY));
        assertEquals("vest_heavy", preset.selection().get(LoadoutCatalog.Category.ARMOR));
    }

    @Test
    void unselectedCategoriesAreAbsentNotNullKeys() throws SQLException {
        UUID player = UUID.randomUUID();
        int id = db.createPreset(player, "Rusher Kit", 0, selection("ak47", null));

        LoadoutPreset preset = db.getPreset(id);
        assertTrue(preset.selection().containsKey(LoadoutCatalog.Category.PRIMARY));
        assertTrue(preset.selection().get(LoadoutCatalog.Category.SECONDARY) == null
                && !preset.selection().containsKey(LoadoutCatalog.Category.SECONDARY));
    }

    @Test
    void listPresetsIsScopedToOnePlayerAndOrderedBySlot() throws SQLException {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        db.createPreset(a, "A-Second", 1, selection("m4a1", null));
        db.createPreset(a, "A-First", 0, selection("ak47", null));
        db.createPreset(b, "B-Only", 0, selection("uzi", null));

        List<LoadoutPreset> aPresets = db.listPresets(a);
        assertEquals(List.of("A-First", "A-Second"), aPresets.stream().map(LoadoutPreset::name).toList());
        assertEquals(1, db.listPresets(b).size());
    }

    @Test
    void countPresetsMatchesListSize() throws SQLException {
        UUID player = UUID.randomUUID();
        assertEquals(0, db.countPresets(player));
        db.createPreset(player, "One", 0, selection("ak47", null));
        db.createPreset(player, "Two", 1, selection("m4a1", null));
        assertEquals(2, db.countPresets(player));
    }

    @Test
    void renameChangesNameButNothingElse() throws SQLException {
        UUID player = UUID.randomUUID();
        int id = db.createPreset(player, "Old Name", 0, selection("ak47", null));
        db.renamePreset(id, "New Name");

        LoadoutPreset preset = db.getPreset(id);
        assertEquals("New Name", preset.name());
        assertEquals("ak47", preset.selection().get(LoadoutCatalog.Category.PRIMARY));
    }

    @Test
    void deletingAPresetRemovesItFromListAndGet() throws SQLException {
        UUID player = UUID.randomUUID();
        int id = db.createPreset(player, "Gone Soon", 0, selection("ak47", null));
        db.deletePreset(id);

        assertNull(db.getPreset(id));
        assertTrue(db.listPresets(player).isEmpty());
    }

    @Test
    void activePresetDefaultsToNullAndCanBeSetAndCleared() throws SQLException {
        UUID player = UUID.randomUUID();
        int id = db.createPreset(player, "Main", 0, selection("ak47", null));

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
        int id = db.createPreset(player, "Main", 0, selection("ak47", null));
        db.setActivePreset(player, id);

        db.deletePreset(id);

        assertNull(db.getActivePreset(player));
    }
}
