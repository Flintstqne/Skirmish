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
import java.util.List;
import java.util.Random;

/**
 * King of the Hill (design doc §8.1). One hill, chosen at random from arena.yml's hill
 * pool every round, ticks once a second: contest the radius, credit the uncontested
 * holder's team score, and redraw the particle ring via the shared
 * {@link ObjectiveParticleManager} (Domination's vertical beam goes through the same class —
 * see its javadoc).
 */
public final class HillObjective {

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

    private Location hill;
    private Team holder;
    private BukkitTask task;

    public HillObjective(Skirmish plugin, ConfigManager config, ArenaConfig arena, WorldManager worlds,
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

    /** Picks a random hill point and starts the per-second tick. No-ops if none are configured. */
    public void start() {
        stop();
        List<Location> pool = arena.getHillPoints();
        if (pool.isEmpty()) {
            plugin.getLogger().warning("KOTH round started with no hill points configured — "
                    + "run /arena add hillpoint in the template first.");
            return;
        }
        hill = worlds.toActiveWorld(pool.get(random.nextInt(pool.size())));
        holder = null;
        ui.start(hill, "Hill: Neutral");
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        ui.stop();
        hill = null;
        holder = null;
    }

    private void tick() {
        if (hill == null) return;

        List<Player> redPlayers = new ArrayList<>();
        List<Player> bluePlayers = new ArrayList<>();
        double radiusSq = (double) config.getKothCaptureRadius() * config.getKothCaptureRadius();
        for (Player player : hill.getWorld().getPlayers()) {
            if (spectators.isSpectating(player)) continue;
            if (hill.distanceSquared(player.getLocation()) > radiusSq) continue;
            Team team = teams.getTeam(player);
            if (team == Team.RED) redPlayers.add(player);
            else if (team == Team.BLUE) bluePlayers.add(player);
        }

        holder = resolveHolder(!redPlayers.isEmpty(), !bluePlayers.isEmpty());
        if (holder != null) {
            int points = config.getKothPointsPerSecond();
            rounds.addTeamScore(holder, points);
            // Ticks start as soon as the WAITING/warmup state begins (applyGamemodeRules runs
            // before the countdown) — without this guard, standing on the hill during warmup
            // would credit the persistent objective_points stat even though addTeamScore
            // above is a no-op for the same reason, silently disagreeing with round score.
            if (rounds.isActive()) {
                for (Player player : holder == Team.RED ? redPlayers : bluePlayers) {
                    stats.recordObjectivePoints(player, points);
                }
            }
        }

        drawRing();
        updateUi();
    }

    /**
     * Standard KOTH contest rule, pure so it's testable: present and uncontested holds it;
     * both present is contested (scores for neither); neither present is simply unheld.
     */
    static Team resolveHolder(boolean redPresent, boolean bluePresent) {
        if (redPresent == bluePresent) return null;
        return redPresent ? Team.RED : Team.BLUE;
    }

    private void updateUi() {
        String status = holder == null ? "Hill: Contested/Neutral" : "Hill: " + holder.getDisplayName() + " holds";
        int threshold = config.getScoreThreshold(GamemodeType.KOTH);
        int score = holder == null ? 0 : rounds.getScore(holder);
        ui.update(status, holder, threshold == 0 ? 0 : (double) score / threshold);
    }

    private void drawRing() {
        Particle particle = resolveParticle();
        Color color = holder == null ? Color.WHITE : holder == Team.RED ? Color.RED : Color.BLUE;
        ObjectiveParticleManager.drawRing(hill, config.getKothCaptureRadius(), particle, color, 24);
    }

    private Particle resolveParticle() {
        try {
            return Particle.valueOf(config.getKothParticleRing());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Unknown koth.particle-ring '" + config.getKothParticleRing()
                    + "' — falling back to DUST.");
            return Particle.DUST;
        }
    }
}
