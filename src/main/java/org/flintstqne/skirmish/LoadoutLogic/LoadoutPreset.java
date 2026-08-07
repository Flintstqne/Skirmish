package org.flintstqne.skirmish.LoadoutLogic;

import java.util.Map;

/**
 * A saved loadout preset (design doc §5.1, §7.6) - an exact snapshot of inventory slot
 * layout, not just which items were owned, so applying it never requires the player to
 * reorganize anything. {@code layout} maps a slot to a catalog key, never a serialized item,
 * so a catalog rebalance can't orphan this.
 *
 * <p>Slot numbering: 0-35 is {@link org.bukkit.inventory.PlayerInventory#getStorageContents()}'s
 * own indexing (hotbar 0-8, main storage 9-35); armor doesn't live in that range, so it's
 * addressed with negative codes instead - see {@link LoadoutService#ARMOR_SLOT_CODES}.
 */
public record LoadoutPreset(int id, String name, int slotIndex, Map<Integer, String> layout) {
}
