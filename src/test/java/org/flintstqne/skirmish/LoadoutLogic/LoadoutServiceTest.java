package org.flintstqne.skirmish.LoadoutLogic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link LoadoutService.ArmorSlot} - the one piece of purchase logic that's pure and doesn't
 * touch a {@link org.bukkit.entity.Player}, so it's the only part directly unit-testable
 * without a running server (design doc pattern: extract the pure rule, don't reach for mocks).
 * The rest of the purchase/ownership/replay behavior (capped-by-key, unlimited potions,
 * armor-slot replace, preset replay) is exercised live - see CLAUDE.md.
 */
class LoadoutServiceTest {

    @Test
    void recognizesEachArmorSlotByMaterialSuffix() {
        assertEquals(LoadoutService.ArmorSlot.HELMET, LoadoutService.ArmorSlot.of("DIAMOND_HELMET"));
        assertEquals(LoadoutService.ArmorSlot.CHESTPLATE, LoadoutService.ArmorSlot.of("NETHERITE_CHESTPLATE"));
        assertEquals(LoadoutService.ArmorSlot.LEGGINGS, LoadoutService.ArmorSlot.of("IRON_LEGGINGS"));
        assertEquals(LoadoutService.ArmorSlot.BOOTS, LoadoutService.ArmorSlot.of("LEATHER_BOOTS"));
    }

    @Test
    void nonArmorMaterialsHaveNoSlot() {
        assertNull(LoadoutService.ArmorSlot.of("SPLASH_POTION"));
        assertNull(LoadoutService.ArmorSlot.of("AIR"));
    }

    @Test
    void slotLookupIsCaseInsensitive() {
        assertEquals(LoadoutService.ArmorSlot.HELMET, LoadoutService.ArmorSlot.of("diamond_helmet"));
    }

    @Test
    void entryWithNoItemHasNoSlotEvenIfArmorCategory() {
        LoadoutCatalog.Entry weaponLikeArmorEntry = new LoadoutCatalog.Entry(
                LoadoutCatalog.Category.ARMOR, "odd", "Odd", 0, "SOME_WEAPON", null, 1, java.util.Map.of(), null);
        assertNull(LoadoutService.ArmorSlot.of(weaponLikeArmorEntry));
    }

    @Test
    void nonArmorCategoryEntryHasNoSlotEvenWithArmorLikeMaterial() {
        LoadoutCatalog.Entry entry = new LoadoutCatalog.Entry(
                LoadoutCatalog.Category.TOOL, "odd", "Odd", 0, null, "DIAMOND_HELMET", 1, java.util.Map.of(), null);
        assertNull(LoadoutService.ArmorSlot.of(entry));
    }
}
