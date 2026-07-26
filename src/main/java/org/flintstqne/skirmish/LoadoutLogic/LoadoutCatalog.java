package org.flintstqne.skirmish.LoadoutLogic;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * loadout-catalog.yml — the single source of truth for what a preset key resolves to
 * (design doc §7.5.1). Presets store {@link Entry#key()}, never an ItemStack.
 */
public final class LoadoutCatalog {

    /** Fixed category order — also the tab order in the builder GUI (§9.2). */
    public enum Category {
        PRIMARY, SECONDARY, ARMOR, POTION, TOOL;

        public String configName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public String displayName() {
            return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One shop entry. Exactly one of {@code wmWeapon} / {@code item} is set —
     * WM owns weapons, vanilla Materials cover armor/potions/tools.
     */
    public record Entry(Category category, String key, String name, int cost,
                        String wmWeapon, String item, int amount, Map<String, Integer> enchants) {

        public boolean isWeapon() {
            return wmWeapon != null;
        }

        public boolean isFree() {
            return cost <= 0;
        }
    }

    private final Logger logger;
    private final Map<Category, List<Entry>> byCategory = new LinkedHashMap<>();
    private final Map<String, Entry> byKey = new LinkedHashMap<>();

    public LoadoutCatalog(Logger logger) {
        this.logger = logger;
    }

    /** Parses the catalog file. The caller is responsible for it existing on disk. */
    public void load(File file) {
        byCategory.clear();
        byKey.clear();

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        for (Category category : Category.values()) {
            List<Entry> entries = new ArrayList<>();
            List<Map<?, ?>> raw = yaml.getMapList("categories." + category.configName());
            for (Map<?, ?> map : raw) {
                Entry entry = parse(category, map);
                if (entry == null) continue;
                if (byKey.putIfAbsent(entry.key(), entry) != null) {
                    logger.warning("Duplicate loadout key '" + entry.key() + "' — ignoring the later one.");
                    continue;
                }
                entries.add(entry);
            }
            if (entries.isEmpty()) {
                logger.warning("Loadout category '" + category.configName() + "' has no entries.");
            }
            byCategory.put(category, entries);
        }
    }

    private Entry parse(Category category, Map<?, ?> map) {
        Object key = map.get("key");
        Object name = map.get("name");
        if (key == null || name == null) {
            logger.warning("Loadout entry in '" + category.configName() + "' is missing key or name — skipped.");
            return null;
        }
        Object wmWeapon = map.get("wm-weapon");
        Object item = map.get("item");
        if (wmWeapon == null && item == null) {
            logger.warning("Loadout entry '" + key + "' has neither wm-weapon nor item — skipped.");
            return null;
        }

        Map<String, Integer> enchants = new LinkedHashMap<>();
        if (map.get("enchants") instanceof Map<?, ?> rawEnchants) {
            rawEnchants.forEach((k, v) -> {
                if (v instanceof Number level) enchants.put(String.valueOf(k), level.intValue());
            });
        }

        int cost = map.get("cost") instanceof Number n ? n.intValue() : 0;
        int amount = map.get("amount") instanceof Number n ? Math.max(1, n.intValue()) : 1;

        return new Entry(category, String.valueOf(key), String.valueOf(name), cost,
                wmWeapon == null ? null : String.valueOf(wmWeapon),
                item == null ? null : String.valueOf(item),
                amount, Map.copyOf(enchants));
    }

    public List<Entry> get(Category category) {
        return byCategory.getOrDefault(category, List.of());
    }

    /** @return null when a preset references a key that no longer exists in the catalog. */
    public Entry get(String key) {
        return key == null ? null : byKey.get(key);
    }

    /** Fallback when a player can't afford their preset's pick for a category (§7.6). */
    public Entry getFreeDefault(Category category) {
        for (Entry entry : get(category)) {
            if (entry.isFree()) return entry;
        }
        return null;
    }

    /** Total per-life cost of a set of keys, ignoring keys that no longer resolve. */
    public int totalCost(Iterable<String> keys) {
        int total = 0;
        for (String key : keys) {
            Entry entry = get(key);
            if (entry != null) total += entry.cost();
        }
        return total;
    }

    /** Used by the builder GUI to decide how many rows of tiers it needs to render. */
    public int size() {
        return byKey.size();
    }
}
