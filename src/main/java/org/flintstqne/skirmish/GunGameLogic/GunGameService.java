package org.flintstqne.skirmish.GunGameLogic;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.flintstqne.skirmish.ConfigManager;
import org.flintstqne.skirmish.LoadoutLogic.WeaponFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gun Game's weapon ladder (design doc §8.5) — per-player tier index, ephemeral and
 * round-scoped like everything else in this gamemode. No loadout shop, no cost, no catalog:
 * each tier is generated directly from {@code gungame.weapon-ladder} by WM title.
 */
public final class GunGameService {

    private final ConfigManager config;
    private final WeaponFactory weapons;
    private final Map<UUID, Integer> tiers = new HashMap<>();

    public GunGameService(ConfigManager config, WeaponFactory weapons) {
        this.config = config;
        this.weapons = weapons;
    }

    /** Everyone back to tier 0 — called once at round start, not on every respawn. */
    public void resetAll() {
        tiers.clear();
    }

    public int getTier(Player player) {
        return tiers.getOrDefault(player.getUniqueId(), 0);
    }

    public boolean isFinalTier(Player player) {
        return isFinalTier(getTier(player), config.getWeaponLadder().size());
    }

    /** Already at tier 0 — a knife death here has no lower tier to demote into. */
    public boolean isFloorTier(Player player) {
        return getTier(player) == 0;
    }

    /** Advances one tier and re-equips immediately — a no-op if already at the final tier. */
    public void promote(Player player) {
        int ladderSize = config.getWeaponLadder().size();
        if (ladderSize == 0) return;
        int next = nextTierAfterPromotion(getTier(player), ladderSize);
        if (next == getTier(player)) return; // already at the top — nothing to promote to
        tiers.put(player.getUniqueId(), next);
        equip(player);
    }

    /**
     * Drops one tier. Doesn't re-equip — the victim is dead; their new tier takes effect at
     * their next respawn via {@link #equip}, same as any other tier read.
     */
    public void demote(Player player) {
        tiers.put(player.getUniqueId(), nextTierAfterDemotion(getTier(player)));
    }

    /**
     * Pure ladder arithmetic, split out so the off-by-one-prone parts are testable without
     * Bukkit: promotion clamps at the top tier, demotion clamps at the bottom, and "final
     * tier" needs an empty-ladder guard so a misconfigured server doesn't divide by nothing.
     */
    static int nextTierAfterPromotion(int current, int ladderSize) {
        return Math.min(current + 1, ladderSize - 1);
    }

    static int nextTierAfterDemotion(int current) {
        return Math.max(0, current - 1);
    }

    static boolean isFinalTier(int tier, int ladderSize) {
        return ladderSize > 0 && tier >= ladderSize - 1;
    }

    /**
     * Gives the player their current tier's weapon, plus a knife — unless they're already
     * on the knife tier, which is the ladder's last entry and never gets a duplicate (§8.5).
     */
    public void equip(Player player) {
        List<String> ladder = config.getWeaponLadder();
        if (ladder.isEmpty()) return;

        player.getInventory().clear();
        player.getInventory().setChestplate(null); // same Inventory#clear() gotcha as LoadoutService

        int tier = getTier(player);
        ItemStack weapon = weapons.createByTitle(ladder.get(tier));
        if (weapon != null) player.getInventory().addItem(weapon);

        boolean isFinal = tier == ladder.size() - 1;
        if (!isFinal) {
            ItemStack knife = weapons.createByTitle(ladder.get(ladder.size() - 1));
            if (knife != null) player.getInventory().addItem(knife);
        }
        player.updateInventory();
    }
}
