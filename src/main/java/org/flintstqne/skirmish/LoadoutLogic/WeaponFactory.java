package org.flintstqne.skirmish.LoadoutLogic;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import me.deecaad.weaponmechanics.WeaponMechanicsAPI;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;
import org.flintstqne.skirmish.Skirmish;

import java.util.Locale;
import java.util.Map;

/**
 * Resolves a catalog entry to a real ItemStack (design doc §7.5.2).
 * Weapons come from WeaponMechanics; everything else is a vanilla item.
 *
 * Verified against WeaponMechanics 4.1.0 and 4.3.1 - {@code generateWeapon(String)}
 * takes the weapon title (the top-level key of a WM weapon .yml) and returns null
 * for an unknown title.
 */
public final class WeaponFactory {

    /** Every item created from a catalog entry carries its key in this PDC tag - lets
     * {@link LoadoutService} read a player's live inventory back into catalog keys (for spend
     * accounting, ownership checks, and saving a preset's exact slot layout) instead of
     * maintaining a separate ledger that could drift out of sync with what's actually equipped. */
    private final NamespacedKey loadoutKeyTag;

    private final Skirmish plugin;

    public WeaponFactory(Skirmish plugin) {
        this.plugin = plugin;
        this.loadoutKeyTag = new NamespacedKey(plugin, "loadout-key");
    }

    /** @return null if the entry can't be built - callers fall back to the free tier. */
    public ItemStack create(LoadoutCatalog.Entry entry) {
        if (entry == null) return null;
        ItemStack stack = entry.isWeapon() ? createWeapon(entry) : createVanilla(entry);
        if (stack == null) return null;
        stack.setAmount(entry.amount());
        stack.editMeta(meta -> meta.getPersistentDataContainer()
                .set(loadoutKeyTag, PersistentDataType.STRING, entry.key()));
        return stack;
    }

    /** The catalog key this stack was created from, or null if it wasn't (or isn't ours). */
    public String getLoadoutKey(ItemStack stack) {
        if (stack == null) return null;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(loadoutKeyTag, PersistentDataType.STRING);
    }

    private ItemStack createWeapon(LoadoutCatalog.Entry entry) {
        ItemStack stack = WeaponMechanicsAPI.generateWeapon(entry.wmWeapon());
        if (stack == null) {
            plugin.getLogger().warning("WeaponMechanics has no weapon titled '" + entry.wmWeapon()
                    + "' (loadout entry '" + entry.key() + "') - check loadout-catalog.yml against your WM config.");
        }
        return stack;
    }

    /**
     * Generates a weapon directly by WM title, bypassing the loadout catalog - used by Gun
     * Game, which has no shop and no per-item cost (design doc §8.5).
     */
    public ItemStack createByTitle(String wmWeaponTitle) {
        ItemStack stack = WeaponMechanicsAPI.generateWeapon(wmWeaponTitle);
        if (stack == null) {
            plugin.getLogger().warning("WeaponMechanics has no weapon titled '" + wmWeaponTitle + "'.");
        }
        return stack;
    }

    private ItemStack createVanilla(LoadoutCatalog.Entry entry) {
        Material material = Material.matchMaterial(entry.item());
        if (material == null) {
            plugin.getLogger().warning("Unknown material '" + entry.item()
                    + "' in loadout entry '" + entry.key() + "'.");
            return null;
        }
        if (material == Material.AIR) return null;

        ItemStack stack = new ItemStack(material);
        for (Map.Entry<String, Integer> enchant : entry.enchants().entrySet()) {
            Enchantment enchantment = resolveEnchantment(enchant.getKey());
            if (enchantment == null) {
                plugin.getLogger().warning("Unknown enchantment '" + enchant.getKey()
                        + "' in loadout entry '" + entry.key() + "'.");
                continue;
            }
            stack.addUnsafeEnchantment(enchantment, enchant.getValue());
        }

        if (entry.potionType() != null) applyPotionType(stack, entry);
        return stack;
    }

    /** Without this, a POTION/SPLASH_POTION/LINGERING_POTION material defaults to a plain
     * Water Bottle - no effect at all - which is why "Speed Potion"/"Splash Healing" entries
     * did nothing before {@code potion-type} existed in the catalog. */
    private void applyPotionType(ItemStack stack, LoadoutCatalog.Entry entry) {
        PotionType type;
        try {
            type = PotionType.valueOf(entry.potionType().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Unknown potion-type '" + entry.potionType()
                    + "' in loadout entry '" + entry.key() + "'.");
            return;
        }
        if (!(stack.getItemMeta() instanceof PotionMeta)) {
            plugin.getLogger().warning("Loadout entry '" + entry.key()
                    + "' has a potion-type but its item isn't a potion material.");
            return;
        }
        stack.editMeta(PotionMeta.class, meta -> meta.setBasePotionType(type));
    }

    private Enchantment resolveEnchantment(String name) {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
                .get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
    }

    /** True when the stack is the WM weapon with this title - used for Gun Game tier checks. */
    public boolean isWeapon(ItemStack stack, String weaponTitle) {
        if (stack == null || weaponTitle == null) return false;
        return weaponTitle.equals(WeaponMechanicsAPI.getWeaponTitle(stack));
    }
}
