package org.flintstqne.skirmish;

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
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
