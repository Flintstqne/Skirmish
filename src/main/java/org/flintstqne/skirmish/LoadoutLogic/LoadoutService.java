package org.flintstqne.skirmish.LoadoutLogic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.flintstqne.skirmish.ConfigManager;
import org.flintstqne.skirmish.RoundLogic.GamemodeType;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-life point economy and the player's in-progress selection (design doc §7.4, §7.5).
 *
 * Balances live in memory and reset to {@code loadout.starting-points} on every respawn.
 * That reset IS the anti-snowball mechanism — there are deliberately no diminishing
 * returns, underdog bonuses or anti-farm rules here (§7.4).
 */
public final class LoadoutService implements Listener {

    private final ConfigManager config;
    private final LoadoutCatalog catalog;
    private final WeaponFactory weaponFactory;

    private final Map<UUID, Integer> points = new HashMap<>();
    private final Map<UUID, Map<LoadoutCatalog.Category, String>> selections = new HashMap<>();

    /** Set by RoundService once the round state machine exists; drives the §7.5.3 block. */
    private GamemodeType currentGamemode = GamemodeType.TDM;

    public LoadoutService(ConfigManager config, LoadoutCatalog catalog, WeaponFactory weaponFactory) {
        this.config = config;
        this.catalog = catalog;
        this.weaponFactory = weaponFactory;
    }

    public void setCurrentGamemode(GamemodeType gamemode) {
        this.currentGamemode = gamemode;
    }

    public GamemodeType getCurrentGamemode() {
        return currentGamemode;
    }

    public boolean isLoadoutsEnabled() {
        return config.isLoadoutsEnabled(currentGamemode);
    }

    public LoadoutCatalog getCatalog() {
        return catalog;
    }

    // ---- points -------------------------------------------------------------

    public int getPoints(Player player) {
        return points.getOrDefault(player.getUniqueId(), config.getStartingPoints());
    }

    public void addPoints(Player player, int amount) {
        points.put(player.getUniqueId(), getPoints(player) + amount);
    }

    /** Called on every death/respawn and at round start — the whole per-life reset (§7.4). */
    public void resetPoints(Player player) {
        points.put(player.getUniqueId(), config.getStartingPoints());
    }

    public void resetAll() {
        points.clear();
        selections.clear();
    }

    // ---- selection ----------------------------------------------------------

    public Map<LoadoutCatalog.Category, String> getSelection(Player player) {
        return selections.computeIfAbsent(player.getUniqueId(), id -> new EnumMap<>(LoadoutCatalog.Category.class));
    }

    public LoadoutCatalog.Entry getSelected(Player player, LoadoutCatalog.Category category) {
        return catalog.get(getSelection(player).get(category));
    }

    /** Points already committed to the current selection. */
    public int getSpent(Player player) {
        return catalog.totalCost(getSelection(player).values());
    }

    /** Points still free to spend this life. */
    public int getRemaining(Player player) {
        return getPoints(player) - getSpent(player);
    }

    /**
     * Whether the player could swap {@code entry} into its category right now.
     * The entry replaces whatever is already selected there, so only the difference matters.
     */
    public boolean canAfford(Player player, LoadoutCatalog.Entry entry) {
        if (entry == null) return false;
        LoadoutCatalog.Entry current = getSelected(player, entry.category());
        return canAfford(getPoints(player), getSpent(player),
                current == null ? 0 : current.cost(), entry.cost());
    }

    /**
     * Pure swap rule, split out so it can be tested without a running server:
     * the new pick replaces whatever is in that category, so only the price
     * difference has to fit in what's left.
     */
    public static boolean canAfford(int points, int spent, int currentCost, int newCost) {
        return newCost - currentCost <= points - spent;
    }

    /**
     * Equips immediately — no confirm step, matching the live-summary GUI footer (§7.5.3).
     *
     * @return false if the player can't afford it; the selection is left untouched.
     */
    public boolean select(Player player, LoadoutCatalog.Entry entry) {
        if (entry == null || !canAfford(player, entry)) return false;
        getSelection(player).put(entry.category(), entry.key());
        applyToInventory(player);
        return true;
    }

    /** Rebuilds the player's inventory from their current selection. */
    public void applyToInventory(Player player) {
        player.getInventory().clear();
        Map<LoadoutCatalog.Category, String> selection = getSelection(player);
        for (LoadoutCatalog.Category category : LoadoutCatalog.Category.values()) {
            LoadoutCatalog.Entry entry = catalog.get(selection.get(category));
            if (entry == null) continue;
            ItemStack stack = weaponFactory.create(entry);
            if (stack == null) continue;
            if (category == LoadoutCatalog.Category.ARMOR) {
                player.getInventory().setChestplate(stack);
            } else {
                player.getInventory().addItem(stack);
            }
        }
        player.updateInventory();
    }

    /**
     * Fills every unset category with its free tier, and downgrades anything the
     * player can no longer afford this life (§7.6) — never leaves someone spawning empty-handed.
     */
    public void applyDefaults(Player player) {
        Map<LoadoutCatalog.Category, String> selection = getSelection(player);

        // ponytail: an unaffordable selection drops wholesale to the free tier rather than
        // shedding items one at a time. Points reset to `starting-points` every life, so the
        // usual case is "all of it is unaffordable" anyway. Revisit if partial keeps matter.
        boolean downgraded = catalog.totalCost(selection.values()) > getPoints(player);

        for (LoadoutCatalog.Category category : LoadoutCatalog.Category.values()) {
            if (!downgraded && selection.get(category) != null) continue;
            LoadoutCatalog.Entry free = catalog.getFreeDefault(category);
            if (free != null) selection.put(category, free.key());
        }
        if (downgraded) {
            player.sendMessage(Component.text(
                    "Your loadout was too expensive this life — swapped to the free tier.",
                    NamedTextColor.YELLOW));
        }
        applyToInventory(player);
    }

    /** Full respawn treatment: reset the per-life balance, then re-gear. */
    public void onRespawn(Player player) {
        resetPoints(player);
        if (!isLoadoutsEnabled()) return;
        applyDefaults(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        points.remove(id);
        selections.remove(id);
    }
}
