package org.flintstqne.skirmish.LoadoutLogic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Catalog parsing (design doc §7.5.1) — keys are what presets store, so bad data must not silently resolve. */
class LoadoutCatalogTest {

    @TempDir
    Path tempDir;

    private LoadoutCatalog catalog;

    @BeforeEach
    void setUp() {
        Logger quiet = Logger.getLogger("catalog-test");
        quiet.setLevel(Level.OFF);
        catalog = new LoadoutCatalog(quiet);
    }

    private void load(String yaml) throws IOException {
        File file = new File(tempDir.toFile(), "loadout-catalog.yml");
        Files.writeString(file.toPath(), yaml);
        catalog.load(file);
    }

    @Test
    void parsesWeaponAndVanillaEntries() throws IOException {
        load("""
                categories:
                  primary:
                    - {key: ak47, name: "AK-47", wm-weapon: "AK_47", cost: 0}
                  armor:
                    - {key: vest, name: "Vest", item: IRON_CHESTPLATE, cost: 150, enchants: {PROTECTION: 2}}
                """);

        LoadoutCatalog.Entry weapon = catalog.get("ak47");
        assertNotNull(weapon);
        assertTrue(weapon.isWeapon());
        assertTrue(weapon.isFree());
        assertEquals("AK_47", weapon.wmWeapon());
        assertEquals(1, weapon.amount(), "amount defaults to 1");

        LoadoutCatalog.Entry armor = catalog.get("vest");
        assertNotNull(armor);
        assertFalse(armor.isWeapon());
        assertEquals(150, armor.cost());
        assertEquals(2, armor.enchants().get("PROTECTION"));
        assertEquals(LoadoutCatalog.Category.ARMOR, armor.category());
    }

    @Test
    void skipsEntriesThatCannotResolveToAnItem() throws IOException {
        load("""
                categories:
                  primary:
                    - {key: ok, name: "Fine", wm-weapon: "AK_47", cost: 0}
                    - {key: nameless, wm-weapon: "M4A1", cost: 10}
                    - {name: "Keyless", wm-weapon: "AUG", cost: 10}
                    - {key: empty, name: "Neither", cost: 10}
                """);

        assertEquals(List.of("ok"), catalog.get(LoadoutCatalog.Category.PRIMARY).stream()
                .map(LoadoutCatalog.Entry::key).toList());
        assertNull(catalog.get("nameless"));
        assertNull(catalog.get("empty"));
    }

    @Test
    void keepsTheFirstOfADuplicatedKey() throws IOException {
        load("""
                categories:
                  primary:
                    - {key: dupe, name: "First", wm-weapon: "AK_47", cost: 0}
                  secondary:
                    - {key: dupe, name: "Second", wm-weapon: "Uzi", cost: 90}
                """);

        assertEquals("First", catalog.get("dupe").name());
        assertTrue(catalog.get(LoadoutCatalog.Category.SECONDARY).isEmpty());
    }

    @Test
    void freeDefaultIsTheFirstZeroCostTierInTheCategory() throws IOException {
        load("""
                categories:
                  primary:
                    - {key: pricey, name: "Pricey", wm-weapon: "AX_50", cost: 350}
                    - {key: free_one, name: "Free One", wm-weapon: "AK_47", cost: 0}
                    - {key: free_two, name: "Free Two", wm-weapon: "M4A1", cost: 0}
                """);

        assertEquals("free_one", catalog.getFreeDefault(LoadoutCatalog.Category.PRIMARY).key());
        assertNull(catalog.getFreeDefault(LoadoutCatalog.Category.TOOL), "no entries means no free default");
    }

    @Test
    void totalCostIgnoresKeysThatNoLongerExist() throws IOException {
        load("""
                categories:
                  primary:
                    - {key: ak47, name: "AK-47", wm-weapon: "AK_47", cost: 0}
                  armor:
                    - {key: vest, name: "Vest", item: IRON_CHESTPLATE, cost: 150}
                """);

        // A preset saved before a rebalance can point at a key that was since removed —
        // it must not blow up or inflate the bill (§5.1).
        assertEquals(150, catalog.totalCost(List.of("ak47", "vest", "deleted_key")));
        assertEquals(0, catalog.totalCost(List.of()));
    }

    @Test
    void missingFileYieldsAnEmptyCatalogRatherThanThrowing() {
        catalog.load(new File(tempDir.toFile(), "does-not-exist.yml"));
        assertEquals(0, catalog.size());
        assertTrue(catalog.get(LoadoutCatalog.Category.PRIMARY).isEmpty());
    }
}
