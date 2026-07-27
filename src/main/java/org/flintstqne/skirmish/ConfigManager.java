package org.flintstqne.skirmish;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.flintstqne.skirmish.RoundLogic.GamemodeType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Typed access to config.yml (tuning). Map data lives in
 * {@link org.flintstqne.skirmish.MapLogic.ArenaConfig} (arena.yml) — the two-tier
 * split from the design doc §6 is deliberate; don't merge them.
 */
public final class ConfigManager {

    private final Skirmish plugin;

    public ConfigManager(Skirmish plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
        validate();
    }

    public void reload() {
        plugin.reloadConfig();
        validate();
    }

    /**
     * Logs every out-of-range value in one pass at startup/reload, rather than a round
     * discovering them one at a time (e.g. a negative timer silently producing a round that
     * never ends). Doesn't block startup — every getter already has a sane hard-coded default
     * via {@code getInt}/{@code getBoolean}, so a bad value just gets flagged, not fatal.
     */
    private void validate() {
        List<String> problems = new ArrayList<>();
        if (getRoundDurationMinutes() <= 0) problems.add("round.duration-minutes must be positive");
        if (getWarmupSeconds() < 0) problems.add("round.warmup-seconds can't be negative");
        if (getImbalanceLockRatio() < 1.0) problems.add("team.imbalance-lock-ratio should be >= 1.0");
        if (getInvulnerabilitySeconds() < 0) problems.add("spawn-protection.invulnerability-seconds can't be negative");
        if (getSpawnZoneRadius() < 0) problems.add("spawn-protection.zone-radius-blocks can't be negative");
        if (getKillPoints() < 0) problems.add("points.kill can't be negative");
        if (getStartingPoints() < 0) problems.add("loadout.starting-points can't be negative");
        if (getSpectatorLockRadius() < 0) problems.add("death.spectator-lock-radius-blocks can't be negative");
        if (getRespawnSeconds() < 0) problems.add("death.respawn-seconds can't be negative");
        if (getMaxSavedPresets() <= 0) problems.add("kits.max-saved-presets must be positive");
        for (GamemodeType mode : GamemodeType.values()) {
            if (mode != GamemodeType.GUN_GAME && getScoreThreshold(mode) <= 0) {
                problems.add(mode.name().toLowerCase(Locale.ROOT) + ".score-threshold must be positive");
            }
        }
        if (getKothCaptureRadius() <= 0) problems.add("koth.capture-radius-blocks must be positive");
        if (getDominationPointCount() <= 0) problems.add("domination.capture-point-count must be positive");
        if (getDominationCaptureRadius() <= 0) problems.add("domination.capture-radius-blocks must be positive");
        if (getFfaMinSpawnDistance() < 0) problems.add("ffa.min-spawn-distance-from-players can't be negative");
        if (getWeaponLadder().isEmpty()) problems.add("gungame.weapon-ladder is empty — Gun Game can't start");
        if (getBorderWallHalfWidth() <= 0) problems.add("arena-border.wall-half-width must be positive");
        if (getBorderWallHalfHeight() <= 0) problems.add("arena-border.wall-half-height must be positive");

        if (!problems.isEmpty()) {
            plugin.getLogger().warning("config.yml has " + problems.size()
                    + " anomal" + (problems.size() == 1 ? "y" : "ies") + ":");
            for (String problem : problems) plugin.getLogger().warning(" - " + problem);
        }
    }

    private FileConfiguration c() {
        return plugin.getConfig();
    }

    // round
    public int getRoundDurationMinutes() { return c().getInt("round.duration-minutes", 30); }
    public boolean scoreThresholdEndsRound() { return c().getBoolean("round.score-threshold-ends-round", true); }
    public int getWarmupSeconds() { return c().getInt("round.warmup-seconds", 10); }

    // team
    public double getImbalanceLockRatio() { return c().getDouble("team.imbalance-lock-ratio", 1.5); }
    public int getSwapIncentivePoints() { return c().getInt("team.swap-incentive-points", 50); }

    // spawn protection
    public int getInvulnerabilitySeconds() { return c().getInt("spawn-protection.invulnerability-seconds", 10); }
    public int getSpawnZoneRadius() { return c().getInt("spawn-protection.zone-radius-blocks", 32); }
    public boolean isSpawnZoneNoDamage() { return c().getBoolean("spawn-protection.zone-no-damage", true); }
    public boolean isSpawnZoneNoBreak() { return c().getBoolean("spawn-protection.zone-no-break", true); }
    public boolean isSpawnZoneNoBuild() { return c().getBoolean("spawn-protection.zone-no-build", true); }

    // points
    public int getKillPoints() { return c().getInt("points.kill", 10); }
    public int getStartingPoints() { return c().getInt("loadout.starting-points", 0); }

    // death / respawn
    public int getSpectatorLockRadius() { return c().getInt("death.spectator-lock-radius-blocks", 50); }
    public int getRespawnSeconds() { return c().getInt("death.respawn-seconds", 10); }
    public boolean isDeathRecapEnabled() { return c().getBoolean("death.show-death-recap", true); }

    // kill feed
    public boolean isKillFeedEnabled() { return c().getBoolean("kill-feed.enabled", true); }

    // killstreaks
    public boolean isKillstreaksEnabled() { return c().getBoolean("killstreaks.enabled", true); }
    public boolean isKillstreakBonusPointsEnabled() { return c().getBoolean("killstreaks.bonus-points-enabled", false); }
    public int getKillstreakBonusPointsPerThreshold() { return c().getInt("killstreaks.bonus-points-per-threshold", 5); }

    /** Streak length → callout name, ordered ascending. Empty if the section is missing/malformed. */
    public Map<Integer, String> getKillstreakThresholds() {
        Map<Integer, String> thresholds = new TreeMap<>();
        ConfigurationSection section = c().getConfigurationSection("killstreaks.thresholds");
        if (section == null) return thresholds;
        for (String key : section.getKeys(false)) {
            try {
                thresholds.put(Integer.parseInt(key), section.getString(key));
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("killstreaks.thresholds key '" + key + "' isn't a number — skipped.");
            }
        }
        return thresholds;
    }

    // scoreboard
    public boolean isScoreboardEnabled() { return c().getBoolean("scoreboard.enabled", true); }
    public int getScoreboardUpdateIntervalTicks() { return c().getInt("scoreboard.update-interval-ticks", 20); }

    // end of round
    public boolean isFreeRoamSpectator() { return c().getBoolean("end-round.free-roam-spectator", true); }
    public int getWinnerAnnouncementSeconds() { return c().getInt("end-round.winner-announcement-seconds", 5); }
    public int getNextRoundCountdownSeconds() { return c().getInt("end-round.next-round-countdown-seconds", 15); }

    // presets
    public int getMaxSavedPresets() { return c().getInt("kits.max-saved-presets", 5); }

    /** Gamemodes eligible for the end-of-round vote. Unknown names are logged and skipped. */
    public List<GamemodeType> getVoteableGamemodes() {
        List<GamemodeType> modes = new ArrayList<>();
        for (String raw : c().getStringList("vote.enabled-gamemodes")) {
            try {
                modes.add(GamemodeType.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Unknown gamemode in vote.enabled-gamemodes: " + raw);
            }
        }
        return modes;
    }

    /** Whether the loadout shop/presets apply in this gamemode (design doc §7.5.3). */
    public boolean isLoadoutsEnabled(GamemodeType mode) {
        return c().getBoolean("gamemode." + mode.name().toLowerCase(Locale.ROOT) + ".loadouts-enabled", true);
    }

    /** Team/player score that ends the round, per gamemode. */
    public int getScoreThreshold(GamemodeType mode) {
        return switch (mode) {
            case KOTH -> c().getInt("koth.score-threshold", 300);
            case DOMINATION -> c().getInt("domination.score-threshold", 300);
            case TDM -> c().getInt("tdm.score-threshold", 75);
            case FFA -> c().getInt("ffa.score-threshold", 30);
            // GUN_GAME ends on final knife kill, not a score threshold (§8.5).
            case GUN_GAME -> Integer.MAX_VALUE;
        };
    }

    // koth
    public int getKothCaptureRadius() { return c().getInt("koth.capture-radius-blocks", 8); }
    public int getKothPointsPerSecond() { return c().getInt("koth.points-per-second", 1); }
    public String getKothParticleRing() { return c().getString("koth.particle-ring", "DUST"); }

    // domination
    public int getDominationPointCount() { return c().getInt("domination.capture-point-count", 3); }
    public int getDominationCaptureRadius() { return c().getInt("domination.capture-radius-blocks", 6); }
    public int getDominationPointsPerTickPerZone() { return c().getInt("domination.points-per-tick-per-zone", 1); }
    public int getDominationTickIntervalSeconds() { return c().getInt("domination.tick-interval-seconds", 1); }
    public String getDominationParticleBeam() { return c().getString("domination.particle-beam", "DUST"); }
    public int getDominationBeamHeight() { return c().getInt("domination.beam-height", 20); }

    // ffa
    public int getFfaMinSpawnDistance() { return c().getInt("ffa.min-spawn-distance-from-players", 15); }

    // gun game
    public List<String> getWeaponLadder() { return c().getStringList("gungame.weapon-ladder"); }
    public boolean isDemoteOnKnifeDeath() { return c().getBoolean("gungame.demote-on-knife-death", true); }
    public boolean isFinalKnifeKillInstantWin() { return c().getBoolean("gungame.final-knife-kill-wins-instantly", true); }

    // arena border — visible + enforced boundary rendered from arena.yml's boundary corners
    public boolean isArenaBorderEnabled() { return c().getBoolean("arena-border.enabled", true); }

    public Material getBorderMaterial() {
        Material material = Material.matchMaterial(c().getString("arena-border.material", "RED_STAINED_GLASS"));
        return material != null ? material : Material.RED_STAINED_GLASS;
    }

    public int getBorderRenderDistance() { return c().getInt("arena-border.render-distance", 24); }
    public int getBorderWallHalfWidth() { return c().getInt("arena-border.wall-half-width", 10); }
    public int getBorderWallHalfHeight() { return c().getInt("arena-border.wall-half-height", 6); }
    public int getBorderEnforceMargin() { return c().getInt("arena-border.enforce-margin", 2); }
    public int getBorderTickIntervalTicks() { return c().getInt("arena-border.tick-interval-ticks", 10); }
}
