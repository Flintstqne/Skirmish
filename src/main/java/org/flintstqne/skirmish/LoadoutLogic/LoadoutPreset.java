package org.flintstqne.skirmish.LoadoutLogic;

import java.util.Map;

/**
 * A saved loadout preset (design doc §5.1, §7.6). {@code selection} maps category to
 * catalog key — never a serialized item — so a catalog rebalance can't orphan this.
 */
public record LoadoutPreset(int id, String name, int slotIndex, Map<LoadoutCatalog.Category, String> selection) {
}
