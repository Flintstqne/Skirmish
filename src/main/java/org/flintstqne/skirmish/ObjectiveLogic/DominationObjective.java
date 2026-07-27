package org.flintstqne.skirmish.ObjectiveLogic;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.flintstqne.skirmish.CombatLogic.DeathSpectatorService;
import org.flintstqne.skirmish.ConfigManager;
import org.flintstqne.skirmish.MapLogic.ArenaConfig;
import org.flintstqne.skirmish.MapLogic.WorldManager;
import org.flintstqne.skirmish.RoundLogic.GamemodeType;
import org.flintstqne.skirmish.RoundLogic.RoundService;
import org.flintstqne.skirmish.Skirmish;
import org.flintstqne.skirmish.StatLogic.StatService;
import org.flintstqne.skirmish.TeamLogic.Team;
import org.flintstqne.skirmish.TeamLogic.TeamService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Domination (design doc §8.2). {@code capture-point-count} zones are picked each round from
 * arena.yml's named capture-point pool (a superset, for round-to-round variety); each is
 * independently contestable via {@link CapturePoint}, reusing KOTH's exact contest rule
 * rather than a shared base class (see {@link CapturePoint#tick}).
 *
 * Scoring is {@code zones_controlled × points-per-tick-per-zone}, evaluated every
 * {@code tick-interval-seconds} — genuinely different from KOTH's flat per-second-per-hill,
 * since every zone has to be summed together each tick rather than resolved independently.
 */
public final class DominationObjective {

    private final Skirmish plugin;
    private final ConfigManager config;
    private final ArenaConfig arena;
    private final WorldManager worlds;
    private final TeamService teams;
    private final DeathSpectatorService spectators;
    private final RoundService rounds;
    private final ObjectiveUIManager ui;
    private final StatService stats;
    private final Random random = new Random();

    private List<CapturePoint> points = List.of();
    private BukkitTask task;

    public DominationObjective(Skirmish plugin, ConfigManager config, ArenaConfig arena, WorldManager worlds,
                               TeamService teams, DeathSpectatorService spectators, RoundService rounds,
                               ObjectiveUIManager ui, StatService stats) {
        this.plugin = plugin;
        this.config = config;
        this.arena = arena;
        this.worlds = worlds;
        this.teams = teams;
        this.spectators = spectators;
        this.rounds = rounds;
        this.ui = ui;
        this.stats = stats;
    }

    public List<CapturePoint> getPoints() {
        return points;
    }

    /** Picks {@code capture-point-count} zones from the pool and starts the tick loop. */
    public void start() {
        stop();
        Map<String, Location> pool = arena.getCapturePoints();
        if (pool.isEmpty()) {
            plugin.getLogger().warning("Domination round started with no capture points configured — "
                    + "run /arena add capturepoint <name> in the template first.");
            return;
        }

        List<Map.Entry<String, Location>> entries = new ArrayList<>(pool.entrySet());
        Collections.shuffle(entries, random);
        int count = Math.min(config.getDominationPointCount(), entries.size());

        List<CapturePoint> chosen = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Location bound = worlds.toActiveWorld(entries.get(i).getValue());
            if (bound != null) chosen.add(new CapturePoint(entries.get(i).getKey(), bound));
        }
        points = List.copyOf(chosen);
        if (points.isEmpty()) return;

        // ponytail: compass anchors on the first chosen zone — a single compass can't usefully
        // point at "whichever of N zones is nearest" without per-player dynamic tracking, which
        // isn't worth building until someone actually asks for it.
        ui.start(points.get(0).getLocation(), "Domination: 0 – 0 zones");

        int intervalTicks = Math.max(1, config.getDominationTickIntervalSeconds() * 20);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, intervalTicks, intervalTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        ui.stop();
        points = List.of();
    }

    private void tick() {
        if (points.isEmpty()) return;

        double radiusSq = (double) config.getDominationCaptureRadius() * config.getDominationCaptureRadius();
        int perZone = config.getDominationPointsPerTickPerZone();
        int redZones = 0;
        int blueZones = 0;
        for (CapturePoint point : points) {
            Team holder = point.tick(teams, spectators, radiusSq);
            if (holder == Team.RED) redZones++;
            else if (holder == Team.BLUE) blueZones++;
            drawBeam(point, holder);
            // Same warmup-window guard as HillObjective — ticks run through WAITING/warmup
            // too, and addTeamScore below already no-ops then; the stat credit must match.
            if (rounds.isActive()) {
                for (Player player : point.getHolderPlayers()) {
                    stats.recordObjectivePoints(player, perZone);
                }
            }
        }

        if (redZones > 0) rounds.addTeamScore(Team.RED, redZones * perZone);
        if (blueZones > 0) rounds.addTeamScore(Team.BLUE, blueZones * perZone);

        updateUi(redZones, blueZones);
    }

    private void drawBeam(CapturePoint point, Team holder) {
        Particle particle = resolveParticle();
        Color color = holder == null ? Color.WHITE : holder == Team.RED ? Color.RED : Color.BLUE;
        ObjectiveParticleManager.drawBeam(point.getLocation(), config.getDominationBeamHeight(), particle, color, 2);
    }

    private void updateUi(int redZones, int blueZones) {
        String status = "Domination: Red " + redZones + " – Blue " + blueZones + " zones";
        Team leader = redZones == blueZones ? null : redZones > blueZones ? Team.RED : Team.BLUE;
        int threshold = config.getScoreThreshold(GamemodeType.DOMINATION);
        int score = leader == null ? 0 : rounds.getScore(leader);
        ui.update(status, leader, threshold == 0 ? 0 : (double) score / threshold);
    }

    private Particle resolveParticle() {
        try {
            return Particle.valueOf(config.getDominationParticleBeam());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Unknown domination.particle-beam '" + config.getDominationParticleBeam()
                    + "' — falling back to DUST.");
            return Particle.DUST;
        }
    }
}
