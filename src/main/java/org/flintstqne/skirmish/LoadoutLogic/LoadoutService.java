package org.flintstqne.skirmish.LoadoutLogic;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.flintstqne.skirmish.ConfigManager;
import org.flintstqne.skirmish.RoundLogic.GamemodeType;
import org.flintstqne.skirmish.Utils.Branding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Round-scoped Merit economy and inventory shop (design doc §7.4, §7.5, revised), bedwars-shop
 * style: buying an item adds it to inventory rather than replacing a per-category pick. Guns
 * and tools are capped at one of each specific catalog entry owned at a time (re-buying one
 * you already own is blocked); potions are unlimited; armor is a special case - one piece per
 * body slot, auto-equipped, and buying a new piece for a slot you've already filled replaces it.
 *
 * There is deliberately no separate "what did I buy" ledger - every generated item is tagged
 * with its catalog key ({@link WeaponFactory#getLoadoutKey}), and spend/ownership/preset-saving
 * all read that live off the player's actual inventory. This is what lets a saved preset
 * remember the player's exact slot layout (including armor) rather than just which items they
 * owned: there's nothing else to save.
 *
 * Balances live in memory and reset to {@code loadout.starting-merit} once per round (see
 * {@link #resetAll}) - not on every death. Merit deliberately survives dying, so a player can
 * bank it across several lives toward one expensive purchase instead of it evaporating the
 * moment they respawn.
 */
public final class LoadoutService implements Listener {

    /** Result of a purchase attempt - lets the GUI show a specific reason instead of a flat
     * "can't afford it" for every rejection. */
    public enum PurchaseResult { SUCCESS, ALREADY_OWNED, CANT_AFFORD, INVENTORY_FULL }

    /**
     * Which body slot an ARMOR entry occupies, inferred from its material name rather than a
     * separate catalog field. Also carries the slot's negative layout code - armor doesn't
     * live in {@link PlayerInventory#getStorageContents()}'s 0-35 range, so a saved preset
     * addresses it separately (see {@link LoadoutPreset}).
     */
    enum ArmorSlot {
        HELMET(-1) {
            ItemStack get(PlayerInventory inv) { return inv.getHelmet(); }
            void set(PlayerInventory inv, ItemStack stack) { inv.setHelmet(stack); }
        },
        CHESTPLATE(-2) {
            ItemStack get(PlayerInventory inv) { return inv.getChestplate(); }
            void set(PlayerInventory inv, ItemStack stack) { inv.setChestplate(stack); }
        },
        LEGGINGS(-3) {
            ItemStack get(PlayerInventory inv) { return inv.getLeggings(); }
            void set(PlayerInventory inv, ItemStack stack) { inv.setLeggings(stack); }
        },
        BOOTS(-4) {
            ItemStack get(PlayerInventory inv) { return inv.getBoots(); }
            void set(PlayerInventory inv, ItemStack stack) { inv.setBoots(stack); }
        };

        final int code;

        ArmorSlot(int code) { this.code = code; }

        abstract ItemStack get(PlayerInventory inv);
        abstract void set(PlayerInventory inv, ItemStack stack);

        static ArmorSlot of(LoadoutCatalog.Entry entry) {
            if (entry == null || entry.category() != LoadoutCatalog.Category.ARMOR || entry.item() == null) {
                return null;
            }
            return of(entry.item());
        }

        static ArmorSlot of(String materialName) {
            String upper = materialName.toUpperCase(Locale.ROOT);
            if (upper.endsWith("_HELMET")) return HELMET;
            if (upper.endsWith("_CHESTPLATE")) return CHESTPLATE;
            if (upper.endsWith("_LEGGINGS")) return LEGGINGS;
            if (upper.endsWith("_BOOTS")) return BOOTS;
            return null;
        }

        static ArmorSlot ofCode(int code) {
            for (ArmorSlot slot : values()) if (slot.code == code) return slot;
            return null;
        }
    }

    private final ConfigManager config;
    private final LoadoutCatalog catalog;
    private final WeaponFactory weaponFactory;

    private final Map<UUID, Integer> merit = new HashMap<>();

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

    public boolean isLoadoutsEnabled() {
        return config.isLoadoutsEnabled(currentGamemode);
    }

    public LoadoutCatalog getCatalog() {
        return catalog;
    }

    /** Builds the real item an entry resolves to (WM gun, enchanted tool, colored potion, ...) -
     * used for the shop GUI icon so it matches what buying it actually gives, instead of a
     * generic placeholder. Null if the entry can't currently resolve to an item. */
    public ItemStack previewItem(LoadoutCatalog.Entry entry) {
        return weaponFactory.create(entry);
    }

    // ---- merit -------------------------------------------------------------

    public int getMerit(Player player) {
        return merit.getOrDefault(player.getUniqueId(), config.getStartingMerit());
    }

    public void addMerit(Player player, int amount) {
        merit.put(player.getUniqueId(), getMerit(player) + amount);
    }

    /** Admin override (design doc §10) - sets the current balance to an arbitrary value. */
    public void setMerit(Player player, int amount) {
        merit.put(player.getUniqueId(), amount);
    }

    /** Called once at the start of every round (design doc §7.4, revised) - Merit is now
     * round-scoped rather than per-life: it survives death so a player can save it up across
     * several lives toward one expensive purchase, and only resets when a new round begins.
     * Clearing the whole map (not just online players) means a mid-round joiner also starts
     * fresh, since {@link #getMerit} falls back to {@code loadout.starting-merit} for anyone
     * with no entry. */
    public void resetAll() {
        merit.clear();
    }

    // ---- reading the live inventory -------------------------------------------

    /** Every non-empty slot (storage 0-35, plus the four armor pieces under their negative
     * {@link ArmorSlot} codes) that currently holds one of our tagged items. */
    private Map<Integer, ItemStack> liveSlots(Player player) {
        Map<Integer, ItemStack> slots = new LinkedHashMap<>();
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int i = 0; i < storage.length; i++) {
            if (storage[i] != null && !storage[i].getType().isAir()) slots.put(i, storage[i]);
        }
        for (ArmorSlot armorSlot : ArmorSlot.values()) {
            ItemStack stack = armorSlot.get(player.getInventory());
            if (stack != null && !stack.getType().isAir()) slots.put(armorSlot.code, stack);
        }
        return slots;
    }

    private LoadoutCatalog.Entry resolveEntry(ItemStack stack) {
        String key = weaponFactory.getLoadoutKey(stack);
        return key == null ? null : catalog.get(key);
    }

    /** How many purchases a stack represents - usually 1, except a catalog entry with
     * {@code amount > 1} (e.g. a grenade purchase granting a stack of 2). */
    private int purchaseCount(ItemStack stack, LoadoutCatalog.Entry entry) {
        return stack.getAmount() / Math.max(1, entry.amount());
    }

    /** Merit already committed to everything currently in the player's inventory. */
    public int getSpent(Player player) {
        int spent = 0;
        for (ItemStack stack : liveSlots(player).values()) {
            LoadoutCatalog.Entry entry = resolveEntry(stack);
            if (entry != null) spent += entry.cost() * purchaseCount(stack, entry);
        }
        return spent;
    }

    /** Merit still free to spend this life. */
    public int getRemaining(Player player) {
        return getMerit(player) - getSpent(player);
    }

    /** Whether a capped entry (gun/tool/armor) is currently owned - always false for
     * uncapped (potion) entries, since there's never anything to "own" there. */
    public boolean isOwned(Player player, LoadoutCatalog.Entry entry) {
        if (entry == null || entry.category() == LoadoutCatalog.Category.POTION) return false;
        for (ItemStack stack : liveSlots(player).values()) {
            if (entry.key().equals(weaponFactory.getLoadoutKey(stack))) return true;
        }
        return false;
    }

    /** How many of this entry the player currently has - 0 or 1 except potions. */
    public int getOwnedCount(Player player, LoadoutCatalog.Entry entry) {
        if (entry == null) return 0;
        int count = 0;
        for (ItemStack stack : liveSlots(player).values()) {
            if (entry.key().equals(weaponFactory.getLoadoutKey(stack))) count += purchaseCount(stack, entry);
        }
        return count;
    }

    /** Every entry currently owned, one list element per unit (a potion bought 3 times
     * appears 3 times) - for GUI summaries, which group repeats back into "name x3" display. */
    public List<LoadoutCatalog.Entry> getOwnedEntries(Player player) {
        List<LoadoutCatalog.Entry> owned = new ArrayList<>();
        for (ItemStack stack : liveSlots(player).values()) {
            LoadoutCatalog.Entry entry = resolveEntry(stack);
            if (entry == null) continue;
            for (int i = 0, n = purchaseCount(stack, entry); i < n; i++) owned.add(entry);
        }
        return owned;
    }

    public boolean canAfford(Player player, LoadoutCatalog.Entry entry) {
        return entry != null && !isOwned(player, entry) && entry.cost() <= getRemaining(player);
    }

    // ---- purchasing -----------------------------------------------------------

    /**
     * Buys {@code entry} - adds it to inventory (or, for armor, replaces whatever currently
     * occupies its slot and auto-equips). No confirm step, matching the live-summary GUI
     * footer (§7.5.3). Only touches the one slot involved, so the rest of the player's
     * inventory layout is never disturbed.
     */
    public PurchaseResult purchase(Player player, LoadoutCatalog.Entry entry) {
        if (entry == null) return PurchaseResult.CANT_AFFORD;

        ArmorSlot armorSlot = ArmorSlot.of(entry);
        if (armorSlot != null) {
            String currentKey = weaponFactory.getLoadoutKey(armorSlot.get(player.getInventory()));
            if (entry.key().equals(currentKey)) return PurchaseResult.ALREADY_OWNED;
            if (entry.cost() > getRemaining(player)) return PurchaseResult.CANT_AFFORD;
            armorSlot.set(player.getInventory(), weaponFactory.create(entry));
            player.updateInventory();
            return PurchaseResult.SUCCESS;
        }

        boolean capped = entry.category() != LoadoutCatalog.Category.POTION;
        if (capped && isOwned(player, entry)) return PurchaseResult.ALREADY_OWNED;
        if (entry.cost() > getRemaining(player)) return PurchaseResult.CANT_AFFORD;

        ItemStack stack = weaponFactory.create(entry);
        if (stack == null) return PurchaseResult.CANT_AFFORD;
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        player.updateInventory();
        return leftover.isEmpty() ? PurchaseResult.SUCCESS : PurchaseResult.INVENTORY_FULL;
    }

    // ---- full-inventory layout: defaults, presets ------------------------------

    /** Wipes storage and armor, then places one item per (slot, catalog key) pair. */
    private void applyLayout(Player player, Map<Integer, String> layout) {
        player.getInventory().clear();
        for (ArmorSlot armorSlot : ArmorSlot.values()) armorSlot.set(player.getInventory(), null);

        for (Map.Entry<Integer, String> placed : layout.entrySet()) {
            LoadoutCatalog.Entry entry = catalog.get(placed.getValue());
            if (entry == null) continue;
            ItemStack stack = weaponFactory.create(entry);
            if (stack == null) continue;

            ArmorSlot armorSlot = ArmorSlot.ofCode(placed.getKey());
            if (armorSlot != null) armorSlot.set(player.getInventory(), stack);
            else player.getInventory().setItem(placed.getKey(), stack);
        }
        player.updateInventory();
    }

    /** No preset active - the free baseline kit: one free gun per PRIMARY/SECONDARY (if the
     * catalog has one), the full free TOOL set, and the full free ARMOR set (§7.6). */
    public void applyDefaults(Player player) {
        Map<Integer, String> layout = new LinkedHashMap<>();
        int slot = 0;
        for (LoadoutCatalog.Category category : new LoadoutCatalog.Category[] {
                LoadoutCatalog.Category.PRIMARY, LoadoutCatalog.Category.SECONDARY}) {
            LoadoutCatalog.Entry free = catalog.getFreeDefault(category);
            if (free != null) layout.put(slot++, free.key());
        }
        for (LoadoutCatalog.Entry tool : catalog.getFreeToolSet()) {
            layout.put(slot++, tool.key());
        }
        for (LoadoutCatalog.Entry piece : catalog.getFreeArmorSet()) {
            ArmorSlot armorSlot = ArmorSlot.of(piece);
            if (armorSlot != null) layout.put(armorSlot.code, piece.key());
        }
        applyLayout(player, layout);
    }

    /**
     * Applies a saved preset for this life: replays its exact slot layout, greedily keeping
     * whatever fits within a fresh balance (armor slots first, then storage slots in order)
     * and silently dropping the rest - unlike {@link #applyDefaults}, a preset is a named,
     * stable loadout that should mostly survive respawn to respawn, so one skipped item
     * shouldn't take the rest of it down (§7.6).
     *
     * <p>{@link LoadoutPresetService#save} already keeps anything unaffordable at
     * {@code loadout.starting-merit} out of a preset in the first place - this replay is the
     * defensive fallback for a preset saved under different config, or a catalog rebalance
     * after the fact.
     */
    public void applyPreset(Player player, Map<Integer, String> presetLayout) {
        int budget = getMerit(player);
        Map<Integer, String> finalLayout = new LinkedHashMap<>();
        boolean anySkipped = false;

        for (int slot : orderForAffordability(presetLayout.keySet())) {
            LoadoutCatalog.Entry entry = catalog.get(presetLayout.get(slot));
            if (entry == null) continue;
            if (entry.cost() > budget) {
                anySkipped = true;
                continue;
            }
            finalLayout.put(slot, entry.key());
            budget -= entry.cost();
        }

        applyLayout(player, finalLayout);
        if (anySkipped) {
            Branding.warning(player, "Part of your loadout preset was too expensive this life and was skipped.");
        }
    }

    /** Armor slots (cheap, usually free) before storage slots, storage in slot order - a
     * simple, deterministic walk for the greedy affordability check above and the equivalent
     * one {@link LoadoutPresetService#save} does at save time. */
    static List<Integer> orderForAffordability(Set<Integer> slots) {
        List<Integer> ordered = new ArrayList<>(slots);
        ordered.sort(Comparator.<Integer>comparingInt(slot -> slot < 0 ? 0 : 1).thenComparingInt(slot -> slot));
        return ordered;
    }

    /** An exact snapshot of the player's current inventory, ready to hand to
     * {@link LoadoutPresetService#save} - this is what makes a preset remember armor and
     * slot layout instead of just which items were owned. */
    public Map<Integer, String> captureLayout(Player player) {
        Map<Integer, String> layout = new LinkedHashMap<>();
        for (Map.Entry<Integer, ItemStack> slotted : liveSlots(player).entrySet()) {
            String key = weaponFactory.getLoadoutKey(slotted.getValue());
            if (key != null) layout.put(slotted.getKey(), key);
        }
        return layout;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        merit.remove(event.getPlayer().getUniqueId());
    }
}
