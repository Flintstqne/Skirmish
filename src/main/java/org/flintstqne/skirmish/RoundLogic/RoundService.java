package org.flintstqne.skirmish.RoundLogic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.flintstqne.skirmish.ConfigManager;
import org.flintstqne.skirmish.CombatLogic.DeathSpectatorService;
import org.flintstqne.skirmish.CombatLogic.SpawnProtectionManager;
import org.flintstqne.skirmish.LoadoutLogic.LoadoutPresetService;
import org.flintstqne.skirmish.LoadoutLogic.LoadoutService;
import org.flintstqne.skirmish.MapLogic.BorderWallRenderer;
import org.flintstqne.skirmish.MapLogic.WorldManager;
import org.flintstqne.skirmish.Skirmish;
import org.flintstqne.skirmish.TeamLogic.Team;
import org.flintstqne.skirmish.TeamLogic.TeamEnforcer;
import org.flintstqne.skirmish.TeamLogic.TeamService;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Round lifecycle and scoring (design doc §7.1). Owns the state machine
 * WAITING → ACTIVE → ENDING → WAITING and decides when a round is over:
 * either a team hits {@code <mode>.score-threshold} or the timer runs out.
 *
 * Which subsystems are live depends on the current {@link GamemodeType} — that
 * routing happens in {@link #applyGamemodeRules()}.
 */
public final class RoundService {

    /** Gamemodes with working logic. Extend as each milestone lands (design doc §14). */
    public static final List<GamemodeType> PLAYABLE = List.of(GamemodeType.TDM);

    private final Skirmish plugin;
    private final ConfigManager config;
    private final TeamService teams;
    private final LoadoutService loadouts;
    private final LoadoutPresetService loadoutPresets;
    private final SpawnProtectionManager spawnProtection;
    private final DeathSpectatorService spectators;
    private final WorldManager worlds;
    private final BorderWallRenderer borderWallRenderer;
    private final TeamEnforcer teamEnforcer;

    private EndRoundSequence endRoundSequence;

    private RoundState state = RoundState.WAITING;
    private GamemodeType gamemode = GamemodeType.TDM;
    private final Map<Team, Integer> teamScores = new EnumMap<>(Team.class);
    private long endsAtMillis;
    private BukkitTask timerTask;
    private boolean starting;

    public RoundService(Skirmish plugin, ConfigManager config, TeamService teams, LoadoutService loadouts,
                        LoadoutPresetService loadoutPresets, SpawnProtectionManager spawnProtection,
                        DeathSpectatorService spectators, WorldManager worlds,
                        BorderWallRenderer borderWallRenderer, TeamEnforcer teamEnforcer) {
        this.plugin = plugin;
        this.config = config;
        this.teams = teams;
        this.loadouts = loadouts;
        this.loadoutPresets = loadoutPresets;
        this.spawnProtection = spawnProtection;
        this.spectators = spectators;
        this.worlds = worlds;
        this.borderWallRenderer = borderWallRenderer;
        this.teamEnforcer = teamEnforcer;
    }

    public void setEndRoundSequence(EndRoundSequence endRoundSequence) {
        this.endRoundSequence = endRoundSequence;
    }

    public RoundState getState() { return state; }
    public GamemodeType getGamemode() { return gamemode; }
    public boolean isActive() { return state == RoundState.ACTIVE; }

    public int getScore(Team team) {
        return teamScores.getOrDefault(team, 0);
    }

    public int getSecondsRemaining() {
        if (state != RoundState.ACTIVE) return 0;
        return (int) Math.max(0, (endsAtMillis - System.currentTimeMillis()) / 1000);
    }

    // ---- lifecycle ----------------------------------------------------------

    /**
     * Clones a fresh arena world, then starts the round in it once it's loaded (§7.3).
     * The world copy runs off the main thread, so the round begins a moment later.
     */
    public void startRound(GamemodeType mode) {
        if (starting) return;
        if (state == RoundState.ACTIVE) endRound(null);
        starting = true;

        worlds.prepareRoundWorld(world -> {
            starting = false;
            if (world == null) {
                state = RoundState.WAITING;
                broadcast(Component.text("Could not prepare the arena — round cancelled. "
                        + "Check the console.", NamedTextColor.RED));
                return;
            }
            // Promoting the new world schedules the previous round's copy for deletion.
            worlds.setActiveWorld(world);
            beginRound(mode);
        });
    }

    private void beginRound(GamemodeType mode) {
        gamemode = mode;
        state = RoundState.ACTIVE;
        teamScores.clear();
        // Fresh teams every round (design doc §7.2 doesn't say teams persist across rounds,
        // and the arena itself is a fresh clone) — everyone gets prompted again below.
        teams.clearAll();
        endsAtMillis = System.currentTimeMillis() + config.getRoundDurationMinutes() * 60_000L;

        applyGamemodeRules();
        borderWallRenderer.startForActiveRound();

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            spectators.stop(player);
            preparePlayer(player);
            teamEnforcer.promptOrRestore(player);
        }

        broadcast(Component.text("Round started: " + mode.name() + " — first to "
                + config.getScoreThreshold(mode) + " wins.", NamedTextColor.GREEN));
        startTimer();
    }

    /** Teleport to spawn, reset the per-life economy, re-gear, grant spawn protection. */
    public void preparePlayer(Player player) {
        player.teleport(teams.getSpawn(player));
        player.setHealth(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
        player.setFoodLevel(20);
        loadoutPresets.onRespawn(player);
        spawnProtection.grantInvulnerability(player);
    }

    /** GamemodeType decides which rules are live (§7.1, §7.5.3, §7.7). */
    private void applyGamemodeRules() {
        loadouts.setCurrentGamemode(gamemode);
        boolean fixedSpawns = gamemode != GamemodeType.FFA && gamemode != GamemodeType.GUN_GAME;
        spawnProtection.setZonesEnabled(fixedSpawns);
    }

    private void startTimer() {
        stopTimer();
        timerTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (state != RoundState.ACTIVE) return;
            if (System.currentTimeMillis() >= endsAtMillis) endRound(null);
        }, 20L, 20L);
    }

    private void stopTimer() {
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }
    }

    // ---- scoring ------------------------------------------------------------

    /** A kill scores for the killer's team in team modes (§8.3). */
    public void recordKill(Player killer) {
        if (state != RoundState.ACTIVE) return;
        Team team = teams.getTeam(killer);
        if (team == null) return;
        teamScores.merge(team, 1, Integer::sum);
        checkThreshold(team);
    }

    private void checkThreshold(Team team) {
        if (!config.scoreThresholdEndsRound()) return;
        if (getScore(team) >= config.getScoreThreshold(gamemode)) endRound(team);
    }

    /**
     * Ends the round and hands off to the end-of-round sequence.
     *
     * @param winner the team that hit the threshold, or null to decide on score (timer expiry)
     */
    public void endRound(Team winner) {
        if (state != RoundState.ACTIVE) return;
        state = RoundState.ENDING;
        stopTimer();
        // Free-roam spectators get the whole arena, no lock (§7.8) — the wall is round-only.
        borderWallRenderer.stop();

        Team decided = winner != null ? winner : leader();
        if (endRoundSequence != null) {
            endRoundSequence.run(gamemode, decided, getScore(Team.RED), getScore(Team.BLUE));
        } else {
            state = RoundState.WAITING;
        }
    }

    /** Highest score, or null on a draw. */
    private Team leader() {
        int red = getScore(Team.RED);
        int blue = getScore(Team.BLUE);
        if (red == blue) return null;
        return red > blue ? Team.RED : Team.BLUE;
    }

    /** Called by EndRoundSequence once the vote resolves. */
    public void onSequenceFinished(GamemodeType next) {
        state = RoundState.WAITING;
        if (plugin.getServer().getOnlinePlayers().isEmpty()) return;
        startRound(next);
    }

    /** Disposes the round world on disable — no diff to replay, the copy just goes away (§7.3). */
    public void shutdown() {
        stopTimer();
        borderWallRenderer.stop();
        state = RoundState.WAITING;
        worlds.shutdown();
    }

    private void broadcast(Component message) {
        plugin.getServer().getOnlinePlayers().forEach(player -> player.sendMessage(message));
    }
}
