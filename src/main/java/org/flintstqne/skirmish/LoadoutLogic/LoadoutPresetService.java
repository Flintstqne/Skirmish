package org.flintstqne.skirmish.LoadoutLogic;

import org.bukkit.entity.Player;
import org.flintstqne.skirmish.ConfigManager;
import org.flintstqne.skirmish.DatabaseManager;
import org.flintstqne.skirmish.Utils.Branding;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Loadout presets — "My Loadouts" (design doc §7.6, §9.3), the persistent counterpart to
 * {@link LoadoutService}'s per-life economy. This is the only class that touches the
 * {@code loadout_presets}/{@code active_loadout} tables (no separate `LoadoutPresetDb`,
 * per design doc §5) — everything goes through the shared {@link DatabaseManager}.
 */
public final class LoadoutPresetService {

    private final DatabaseManager db;
    private final LoadoutService loadouts;
    private final ConfigManager config;
    private final Logger logger;

    public LoadoutPresetService(DatabaseManager db, LoadoutService loadouts, ConfigManager config, Logger logger) {
        this.db = db;
        this.loadouts = loadouts;
        this.config = config;
        this.logger = logger;
    }

    public List<LoadoutPreset> list(Player player) {
        try {
            return db.listPresets(player.getUniqueId());
        } catch (SQLException e) {
            logFailure("list presets for " + player.getName(), e);
            return List.of();
        }
    }

    public boolean canSaveMore(Player player) {
        try {
            return db.countPresets(player.getUniqueId()) < config.getMaxSavedPresets();
        } catch (SQLException e) {
            logFailure("count presets for " + player.getName(), e);
            return false;
        }
    }

    /**
     * Saves the player's current in-progress selection as a new named preset.
     *
     * Only categories affordable at {@code loadout.starting-points} are kept — a preset
     * applies at the start of a life, when the balance has just reset to that value, so a
     * paid item earned and bought mid-life (e.g. after a kill) could never actually equip
     * from a preset anyway. Rather than silently downgrading it every single respawn, it's
     * excluded from the save entirely; paid gear stays a live, one-life purchase through the
     * builder GUI.
     *
     * @return null if saving failed or {@code kits.max-saved-presets} was already reached
     */
    public LoadoutPreset save(Player player, String name) {
        Map<LoadoutCatalog.Category, String> current = loadouts.getSelection(player);

        Map<LoadoutCatalog.Category, Integer> costs = new EnumMap<>(LoadoutCatalog.Category.class);
        for (Map.Entry<LoadoutCatalog.Category, String> entry : current.entrySet()) {
            LoadoutCatalog.Entry catalogEntry = loadouts.getCatalog().get(entry.getValue());
            if (catalogEntry != null) costs.put(entry.getKey(), catalogEntry.cost());
        }
        Set<LoadoutCatalog.Category> keepable = LoadoutService.affordableCategories(config.getStartingPoints(), costs);

        Map<LoadoutCatalog.Category, String> savable = new EnumMap<>(LoadoutCatalog.Category.class);
        List<String> excluded = new ArrayList<>();
        for (Map.Entry<LoadoutCatalog.Category, String> entry : current.entrySet()) {
            if (keepable.contains(entry.getKey())) {
                savable.put(entry.getKey(), entry.getValue());
                continue;
            }
            LoadoutCatalog.Entry catalogEntry = loadouts.getCatalog().get(entry.getValue());
            excluded.add(catalogEntry != null ? catalogEntry.name() : entry.getValue());
        }

        try {
            int count = db.countPresets(player.getUniqueId());
            if (count >= config.getMaxSavedPresets()) return null;
            int id = db.createPreset(player.getUniqueId(), name, count, savable);
            if (!excluded.isEmpty()) {
                Branding.warning(player,
                        "Not saved (paid gear doesn't carry into a new life): " + String.join(", ", excluded));
            }
            return new LoadoutPreset(id, name, count, Map.copyOf(savable));
        } catch (SQLException e) {
            logFailure("save preset for " + player.getName(), e);
            return null;
        }
    }

    public void delete(int presetId) {
        try {
            db.deletePreset(presetId);
        } catch (SQLException e) {
            logFailure("delete preset " + presetId, e);
        }
    }

    public void rename(int presetId, String newName) {
        try {
            db.renamePreset(presetId, newName);
        } catch (SQLException e) {
            logFailure("rename preset " + presetId, e);
        }
    }

    public void setActive(Player player, int presetId) {
        try {
            db.setActivePreset(player.getUniqueId(), presetId);
        } catch (SQLException e) {
            logFailure("set active preset for " + player.getName(), e);
        }
    }

    public LoadoutPreset getActive(Player player) {
        try {
            return db.getActivePreset(player.getUniqueId());
        } catch (SQLException e) {
            logFailure("read active preset for " + player.getName(), e);
            return null;
        }
    }

    /**
     * Full respawn handling (design doc §7.6): reset the per-life balance, then either
     * auto-equip the active preset (downgrading only what this life can't afford) or fall
     * back to {@link LoadoutService#applyDefaults} if there isn't one.
     */
    public void onRespawn(Player player) {
        loadouts.resetPoints(player);
        if (!loadouts.isLoadoutsEnabled()) return;

        LoadoutPreset active = getActive(player);
        if (active != null) {
            loadouts.applyPreset(player, active.selection());
        } else {
            loadouts.applyDefaults(player);
        }
    }

    private void logFailure(String action, SQLException e) {
        logger.warning("Loadout preset DB failure (" + action + "): " + e.getMessage());
    }
}
