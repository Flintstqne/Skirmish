package org.flintstqne.skirmish.RoundLogic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.flintstqne.skirmish.ConfigManager;
import org.flintstqne.skirmish.CombatLogic.DeathSpectatorService;
import org.flintstqne.skirmish.CombatLogic.SpawnProtectionManager;
import org.flintstqne.skirmish.GunGameLogic.GunGameService;
import org.flintstqne.skirmish.LoadoutLogic.LoadoutPresetService;
import org.flintstqne.skirmish.LoadoutLogic.LoadoutService;
import org.flintstqne.skirmish.MapLogic.BorderWallRenderer;
import org.flintstqne.skirmish.MapLogic.RandomSpawnSelector;
import org.flintstqne.skirmish.MapLogic.WorldManager;
import org.flintstqne.skirmish.ObjectiveLogic.DominationObjective;
import org.flintstqne.skirmish.ObjectiveLogic.HillObjective;
import org.flintstqne.skirmish.Skirmish;
import org.flintstqne.skirmish.StatLogic.StatService;
import org.flintstqne.skirmish.TeamLogic.Team;
import org.flintstqne.skirmish.Utils.Branding;
import org.flintstqne.skirmish.TeamLogic.TeamEnforcer;
import org.flintstqne.skirmish.TeamLogic.TeamService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    public static final List<GamemodeType> PLAYABLE = List.of(GamemodeType.TDM, GamemodeType.KOTH,
            GamemodeType.DOMINATION, GamemodeType.FFA, GamemodeType.GUN_GAME);

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
    private final RandomSpawnSelector randomSpawns;
    private final GunGameService gunGame;
    private final StatService stats;

    private EndRoundSequence endRoundSequence;
    private HillObjective hillObjective;
    private DominationObjective dominationObjective;

    private RoundState state = RoundState.WAITING;
    private GamemodeType gamemode = GamemodeType.TDM;
    private final Map<Team, Integer> teamScores = new EnumMap<>(Team.class);
    private final Map<UUID, Integer> playerScores = new HashMap<>();
    /** Kills this round, regardless of gamemode/team — the MVP/recap metric (§11 item 7),
     * kept separate from teamScores/playerScores since those mean different things per mode
     * (KOTH/Domination mix in hold-ticks; Gun Game's win condition isn't kills at all). */
    private final Map<UUID, Integer> roundKills = new HashMap<>();
    private boolean firstBloodClaimed;
    private long endsAtMillis;
    private long startedAtMillis;
    private BukkitTask timerTask;
    private BukkitTask warmupTask;
    private boolean starting;

    public RoundService(Skirmish plugin, ConfigManager config, TeamService teams, LoadoutService loadouts,
                        LoadoutPresetService loadoutPresets, SpawnProtectionManager spawnProtection,
                        DeathSpectatorService spectators, WorldManager worlds,
                        BorderWallRenderer borderWallRenderer, TeamEnforcer teamEnforcer,
                        RandomSpawnSelector randomSpawns, GunGameService gunGame, StatService stats) {
        this.plugin = plugin;
        this.config = config;
        this.teams = teams;
        this.loadouts = loadouts;
        this.loadoutPresets = loadoutPresets;
        this.spawnProtection = spawnProtection;
        this.spectators = spectators;
        this.worlds = worlds;
        this.gunGame = gunGame;
        this.stats = stats;
        this.borderWallRenderer = borderWallRenderer;
        this.teamEnforcer = teamEnforcer;
        this.randomSpawns = randomSpawns;
    }

    public void setEndRoundSequence(EndRoundSequence endRoundSequence) {
        this.endRoundSequence = endRoundSequence;
    }

    /** Set post-construction — HillObjective needs a RoundService reference to score hold-ticks. */
    public void setHillObjective(HillObjective hillObjective) {
        this.hillObjective = hillObjective;
    }

    /** Set post-construction, same reason as {@link #setHillObjective}. */
    public void setDominationObjective(DominationObjective dominationObjective) {
        this.dominationObjective = dominationObjective;
    }

    public RoundState getState() { return state; }
    public GamemodeType getGamemode() { return gamemode; }
    public boolean isActive() { return state == RoundState.ACTIVE; }

    public int getScore(Team team) {
        return teamScores.getOrDefault(team, 0);
    }

    /** Individual score for teamless gamemodes (FFA, §8.4). */
    public int getPlayerScore(Player player) {
        return playerScores.getOrDefault(player.getUniqueId(), 0);
    }

    /** Raw kill count this round, independent of team/win-condition — see {@link #roundKills}. */
    public int getRoundKills(Player player) {
        return roundKills.getOrDefault(player.getUniqueId(), 0);
    }

    /** Called by every kill path (TDM/KOTH/Domination/FFA via CombatListener, Gun Game via
     * GunGameListener) purely for the end-of-round MVP recap — not a win condition. */
    public void recordKillForMvp(Player killer) {
        if (!isActive()) return;
        roundKills.merge(killer.getUniqueId(), 1, Integer::sum);
    }

    /** Passthrough so the kill listeners (which already hold a RoundService reference, not a
     * DeathSpectatorService one) can feed the follow-killer spectator mode. */
    public void recordKiller(Player victim, Player killer) {
        spectators.recordKiller(victim, killer);
    }

    /** True the first time it's called each round — the caller broadcasts "First Blood" only
     * then. Not a getter: calling it a second time deliberately can't re-trigger the message. */
    public boolean claimFirstBlood() {
        if (firstBloodClaimed) return false;
        firstBloodClaimed = true;
        return true;
    }

    /** Most kills this round, or null if nobody's killed anyone or there's a tie (§11 item 7). */
    public Player getMvp() {
        UUID id = pickLeader(roundKills);
        return id == null ? null : plugin.getServer().getPlayer(id);
    }

    public int getSecondsRemaining() {
        if (state != RoundState.ACTIVE) return 0;
        return (int) Math.max(0, (endsAtMillis - System.currentTimeMillis()) / 1000);
    }

    // ---- lifecycle ----------------------------------------------------------

    /**
     * Clones a fresh arena world, then starts the round in it once it's loaded (§7.3). Waits
     * for {@code round.min-players-to-start} rather than cloning a world for too few players —
     * every auto-trigger (boot, vote-sequence finish, a join resuming an idle round) re-checks
     * this on its own next call, so the round starts the moment enough players are online.
     */
    public void startRound(GamemodeType mode) {
        if (plugin.getServer().getOnlinePlayers().size() < config.getMinPlayersToStart()) return;
        forceStart(mode);
    }

    /** Admin override (/round start) — bypasses the player-count gate {@link #startRound} respects. */
    public void forceStart(GamemodeType mode) {
        if (starting) return;
        cancelWarmup();
        if (state == RoundState.ACTIVE) endRoundNow();
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

    /**
     * Places and gears everyone, then holds in {@link RoundState#WAITING} for
     * {@code round.warmup-seconds} (design doc §11 item 1) before the round actually goes
     * live — a visible countdown, not the instantaneous pass-through this state used to be.
     */
    private void beginRound(GamemodeType mode) {
        gamemode = mode;
        state = RoundState.WAITING;
        teamScores.clear();
        playerScores.clear();
        roundKills.clear();
        firstBloodClaimed = false;
        gunGame.resetAll();
        // Fresh teams every round (design doc §7.2 doesn't say teams persist across rounds,
        // and the arena itself is a fresh clone) — everyone gets prompted again below.
        teams.clearAll();

        applyGamemodeRules();
        borderWallRenderer.startForActiveRound();

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            spectators.stop(player);
            preparePlayer(player);
            stats.recordRoundPlayed(player);
            // Team modes additionally need the forced-selection GUI; preparePlayer already
            // spawned everyone correctly regardless of gamemode.
            if (mode.usesTeams()) teamEnforcer.promptOrRestore(player);
        }

        int warmup = config.getWarmupSeconds();
        if (warmup <= 0) {
            activateRound();
        } else {
            broadcast(Component.text(mode.name() + " starting in " + warmup + "s — get ready.", NamedTextColor.YELLOW));
            scheduleWarmupTick(warmup);
        }
    }

    private void scheduleWarmupTick(int secondsLeft) {
        warmupTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (isFinalWarmupTick(secondsLeft)) {
                activateRound();
                return;
            }
            Title tick = Title.title(Component.empty(),
                    Component.text("Starting in " + (secondsLeft - 1) + "s", NamedTextColor.YELLOW),
                    Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO));
            plugin.getServer().getOnlinePlayers().forEach(player -> player.showTitle(tick));
            scheduleWarmupTick(secondsLeft - 1);
        }, 20L);
    }

    /** Pure, so it's testable without a scheduler: the tick where the countdown has nothing
     * left to show and the round should go straight to {@link #activateRound()}. */
    static boolean isFinalWarmupTick(int secondsLeft) {
        return secondsLeft <= 1;
    }

    private void cancelWarmup() {
        if (warmupTask != null) {
            warmupTask.cancel();
            warmupTask = null;
        }
    }

    /** Whether the pre-round countdown is currently ticking (admin diagnostics). */
    public boolean isWarmupActive() {
        return warmupTask != null;
    }

    /**
     * True when no round is running and nothing is about to start one — the gap left by
     * {@link #onSequenceFinished} skipping a restart when the server was empty at vote-end.
     * Distinct from an ordinary {@link RoundState#WAITING} mid-warmup, which has a live
     * {@link #warmupTask}.
     */
    public boolean isIdle() {
        return state == RoundState.WAITING && warmupTask == null && !starting;
    }

    /** Warmup's over — starts the real round timer and scoring. */
    private void activateRound() {
        warmupTask = null;
        state = RoundState.ACTIVE;
        startedAtMillis = System.currentTimeMillis();
        endsAtMillis = startedAtMillis + config.getRoundDurationMinutes() * 60_000L;

        // A fresh grace period exactly as combat opens, on top of whatever preparePlayer
        // already granted at placement — covers a warmup longer than the configured
        // invulnerability-seconds without needing a separate "is warmup over" damage gate.
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            spawnProtection.grantInvulnerability(player);
        }

        broadcast(Component.text("FIGHT! " + gamemode.name() + " — first to "
                + config.getScoreThreshold(gamemode) + " wins.", NamedTextColor.GREEN));
        startTimer();
    }

    /** Teleport to spawn, reset the per-life economy, re-gear, grant spawn protection. */
    public void preparePlayer(Player player) {
        player.teleport(resolveSpawn(player));
        player.setHealth(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
        player.setFoodLevel(20);
        // Gun Game has no loadout shop (loadouts-enabled: false) — its ladder tier is the
        // gear, not a preset or purchase, so it re-equips through GunGameService instead.
        if (gamemode == GamemodeType.GUN_GAME) {
            gunGame.equip(player);
        } else {
            loadoutPresets.onRespawn(player);
        }
        spawnProtection.grantInvulnerability(player);
    }

    /** Team spawn for team modes; a random FFA spawn respecting min-distance otherwise (§8.4). */
    private Location resolveSpawn(Player player) {
        if (gamemode.usesTeams()) return teams.getSpawn(player);

        Location spawn = randomSpawns.select(occupiedLocations(player), config.getFfaMinSpawnDistance());
        if (spawn != null) return spawn;
        return worlds.hasActiveWorld() ? worlds.getActiveWorld().getSpawnLocation() : player.getWorld().getSpawnLocation();
    }

    /** Everyone actively playing right now, for FFA spawn spacing — not the player being placed. */
    private List<Location> occupiedLocations(Player excluding) {
        List<Location> occupied = new ArrayList<>();
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (other.equals(excluding) || spectators.isSpectating(other)) continue;
            occupied.add(other.getLocation());
        }
        return occupied;
    }

    /** GamemodeType decides which rules are live (§7.1, §7.5.3, §7.7, §8.1). */
    private void applyGamemodeRules() {
        loadouts.setCurrentGamemode(gamemode);
        boolean fixedSpawns = gamemode != GamemodeType.FFA && gamemode != GamemodeType.GUN_GAME;
        spawnProtection.setZonesEnabled(fixedSpawns);

        if (hillObjective != null) {
            if (gamemode == GamemodeType.KOTH) {
                hillObjective.start();
            } else {
                hillObjective.stop();
            }
        }
        if (dominationObjective != null) {
            if (gamemode == GamemodeType.DOMINATION) {
                dominationObjective.start();
            } else {
                dominationObjective.stop();
            }
        }
    }

    private void startTimer() {
        stopTimer();
        timerTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (state != RoundState.ACTIVE) return;
            if (System.currentTimeMillis() >= endsAtMillis) endRoundNow();
        }, 20L, 20L);
    }

    private void stopTimer() {
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }
    }

    // ---- scoring ------------------------------------------------------------

    /** A kill scores for the killer's team in team modes, or the killer individually in FFA (§8.3/§8.4). */
    public void recordKill(Player killer) {
        if (gamemode.usesTeams()) {
            addTeamScore(teams.getTeam(killer), 1);
        } else {
            addPlayerScore(killer, 1);
        }
    }

    /**
     * Adds to a team's round score from any source — kills (§8.3), KOTH hold-ticks
     * ({@link HillObjective}, §8.1), or Domination zone-ticks ({@link DominationObjective},
     * §8.2) all feed the same score and the same threshold check.
     */
    public void addTeamScore(Team team, int amount) {
        if (state != RoundState.ACTIVE || team == null) return;
        teamScores.merge(team, amount, Integer::sum);
        if (!config.scoreThresholdEndsRound()) return;
        if (getScore(team) >= config.getScoreThreshold(gamemode)) endRound(team);
    }

    /** Individual equivalent of {@link #addTeamScore} for teamless gamemodes (FFA, §8.4). */
    public void addPlayerScore(Player player, int amount) {
        if (state != RoundState.ACTIVE || player == null) return;
        playerScores.merge(player.getUniqueId(), amount, Integer::sum);
        if (!config.scoreThresholdEndsRound()) return;
        if (getPlayerScore(player) >= config.getScoreThreshold(gamemode)) endRoundForPlayer(player);
    }

    /**
     * Ends the round with no specific winner in mind (timer expiry, or an admin forcing it) —
     * routes to whichever of {@link #endRound}/{@link #endRoundForPlayer} the current
     * gamemode actually uses, so callers don't have to know which one applies.
     */
    public void endRoundNow() {
        if (gamemode.usesTeams()) endRound(null); else endRoundForPlayer(null);
    }

    /**
     * @param winner the team that hit the threshold, or null to decide on score (timer expiry)
     */
    public void endRound(Team winner) {
        if (!beginEnding()) return;
        Team decided = winner != null ? winner : teamLeader();
        recordStatsForRoundEnd(decided, null);
        if (endRoundSequence != null) {
            endRoundSequence.run(gamemode, decided, null, getScore(Team.RED), getScore(Team.BLUE));
        } else {
            state = RoundState.WAITING;
        }
    }

    /**
     * Ends an individual-mode (FFA) round and hands off to the end-of-round sequence.
     *
     * @param winner the player that hit the threshold, or null to decide on score (timer expiry)
     */
    public void endRoundForPlayer(Player winner) {
        if (!beginEnding()) return;
        Player decided = winner != null ? winner : playerLeader();
        recordStatsForRoundEnd(null, decided);
        if (endRoundSequence != null) {
            endRoundSequence.run(gamemode, null, decided, 0, 0);
        } else {
            state = RoundState.WAITING;
        }
    }

    /** Round history row, plus a rounds-won credit for the winning team's members or player. */
    private void recordStatsForRoundEnd(Team teamWinner, Player playerWinner) {
        stats.recordRoundHistory(gamemode, teamWinner, playerWinner, startedAtMillis,
                getScore(Team.RED), getScore(Team.BLUE));
        if (teamWinner != null) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (teams.getTeam(player) == teamWinner) stats.recordRoundWon(player, gamemode);
            }
        } else if (playerWinner != null) {
            stats.recordRoundWon(playerWinner, gamemode);
        }
    }

    /** Common teardown shared by both end-round paths. @return false if there was no round to end. */
    private boolean beginEnding() {
        if (state != RoundState.ACTIVE) return false;
        state = RoundState.ENDING;
        stopTimer();
        cancelWarmup();
        // Free-roam spectators get the whole arena, no lock (§7.8) — the wall is round-only.
        borderWallRenderer.stop();
        if (hillObjective != null) hillObjective.stop();
        if (dominationObjective != null) dominationObjective.stop();
        return true;
    }

    /** Highest team score, or null on a draw. */
    private Team teamLeader() {
        int red = getScore(Team.RED);
        int blue = getScore(Team.BLUE);
        if (red == blue) return null;
        return red > blue ? Team.RED : Team.BLUE;
    }

    /** Highest individual score, or null if nobody's scored, there's a tie, or they've left. */
    private Player playerLeader() {
        UUID id = pickLeader(playerScores);
        return id == null ? null : plugin.getServer().getPlayer(id);
    }

    /**
     * Pure highest-value pick from a score map, split out so it's testable without Bukkit:
     * null on an empty map or an outright tie (draw) — a later equal score breaks a lead,
     * it doesn't win one back.
     */
    static UUID pickLeader(Map<UUID, Integer> scores) {
        UUID bestId = null;
        int bestScore = -1;
        boolean tie = false;
        for (Map.Entry<UUID, Integer> entry : scores.entrySet()) {
            if (entry.getValue() > bestScore) {
                bestScore = entry.getValue();
                bestId = entry.getKey();
                tie = false;
            } else if (entry.getValue() == bestScore) {
                tie = true;
            }
        }
        return (bestId == null || tie) ? null : bestId;
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
        cancelWarmup();
        borderWallRenderer.stop();
        if (hillObjective != null) hillObjective.stop();
        if (dominationObjective != null) dominationObjective.stop();
        state = RoundState.WAITING;
        worlds.shutdown();
    }

    private void broadcast(Component message) {
        Component branded = Branding.prefix(message);
        plugin.getServer().getOnlinePlayers().forEach(player -> player.sendMessage(branded));
    }
}
