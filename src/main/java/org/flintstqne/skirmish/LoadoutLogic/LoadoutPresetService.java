package org.flintstqne.skirmish.LoadoutLogic;

import org.bukkit.entity.Player;
import org.flintstqne.skirmish.ConfigManager;
import org.flintstqne.skirmish.DatabaseManager;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loadout presets - "My Loadouts" (design doc §7.6, §9.3), the persistent counterpart to
 * {@link LoadoutService}'s round-scoped economy. This is the only class that touches the
 * {@code loadout_presets}/{@code active_loadout} tables (no separate `LoadoutPresetDb`,
 * per design doc §5) - everything goes through the shared {@link DatabaseManager}.
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
     * Saves an exact snapshot of the player's current inventory - every item in its current
     * slot, armor included - as a new named preset. Applying it later (design doc §7.6,
     * {@link LoadoutService#applyPreset}) restores that same layout, so nothing needs to be
     * reorganized by hand.
     *
     * No affordability filtering happens at save time anymore: now that Merit is round-scoped
     * rather than per-life (it survives death and only resets next round), a preset saved
     * early - while still holding an expensive item bought with a big accumulated balance -
     * should be free to include it. {@link LoadoutService#applyPreset} already does the real
     * affordability check live, against whatever the balance actually is at each respawn, and
     * skips what doesn't fit; that's the only gate that still makes sense here.
     *
     * @return null if saving failed or {@code kits.max-saved-presets} was already reached
     */
    public LoadoutPreset save(Player player, String name) {
        Map<Integer, String> layout = loadouts.captureLayout(player);
        try {
            int count = db.countPresets(player.getUniqueId());
            if (count >= config.getMaxSavedPresets()) return null;
            int id = db.createPreset(player.getUniqueId(), name, count, layout);
            return new LoadoutPreset(id, name, count, layout);
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
     * Full respawn handling (design doc §7.6, revised): Merit itself is round-scoped now, not
     * per-life (see {@link LoadoutService#resetAll}) - a respawn just re-gears the player,
     * either restoring the active preset's exact layout (skipping only what the current
     * round-long balance can't afford) or falling back to {@link LoadoutService#applyDefaults}
     * if there isn't one.
     */
    public void onRespawn(Player player) {
        if (!loadouts.isLoadoutsEnabled()) return;

        LoadoutPreset active = getActive(player);
        if (active != null) {
            loadouts.applyPreset(player, active.layout());
        } else {
            loadouts.applyDefaults(player);
        }
    }

    private void logFailure(String action, SQLException e) {
        logger.warning("Loadout preset DB failure (" + action + "): " + e.getMessage());
    }
}
