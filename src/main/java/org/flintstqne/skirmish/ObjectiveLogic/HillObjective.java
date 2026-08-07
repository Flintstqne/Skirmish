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
import org.flintstqne.skirmish.Utils.Branding;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * King of the Hill (design doc §8.1). One hill, chosen at random from arena.yml's hill pool,
 * ticks once a second: contest the radius, credit the uncontested holder's team score, redraw
 * the particle ring via the shared {@link ObjectiveParticleManager} (Domination's vertical beam
 * goes through the same class - see its javadoc), and relocate to a different random point
 * every {@code koth.hill-rotation-seconds} regardless of hold state - this is a hardpoint-style
 * rotation, not a "hold long enough and it moves on" reward, so camping a held hill doesn't
 * delay the map from opening up.
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
    /** The pool entry {@link #hill} was bound from, kept unbound (template-space) so a
     * relocation can compare coordinates against it without an active-world mismatch. */
    private Location currentPoolEntry;
    private Team holder;
    /** True only when both teams are present and neither holds it - distinct from nobody
     * being there at all, which {@code holder == null} alone can't tell apart. */
    private boolean contested;
    private int secondsSinceRelocation;
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
            plugin.getLogger().warning("KOTH round started with no hill points configured - "
                    + "run /arena add hillpoint in the template first.");
            return;
        }
        currentPoolEntry = pickExcluding(pool, null);
        hill = worlds.toActiveWorld(currentPoolEntry);
        holder = null;
        contested = false;
        secondsSinceRelocation = 0;
        ui.start(hill, "Hill: Neutral");
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    /** Current hill location, or null when KOTH isn't active - used by BotLogic to send bots there. */
    public Location getHill() {
        return hill;
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        ui.stop();
        hill = null;
        currentPoolEntry = null;
        holder = null;
        contested = false;
        secondsSinceRelocation = 0;
    }

    private void tick() {
        if (hill == null) return;

        secondsSinceRelocation++;
        if (secondsSinceRelocation >= config.getKothHillRotationSeconds()) {
            relocate();
            return;
        }

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

        boolean redPresent = !redPlayers.isEmpty();
        boolean bluePresent = !bluePlayers.isEmpty();
        holder = resolveHolder(redPresent, bluePresent);
        contested = redPresent && bluePresent;
        if (holder != null) {
            int points = config.getKothPointsPerSecond();
            rounds.addTeamScore(holder, points);
            // Ticks start as soon as the WAITING/warmup state begins (applyGamemodeRules runs
            // before the countdown) - without this guard, standing on the hill during warmup
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

    /** Jumps to a different random hill point regardless of whether the current one was held -
     * a hardpoint-style rotation meant to keep players moving across the whole map, not a
     * capture reward. Resets the contest state; the new spot starts Neutral. */
    private void relocate() {
        List<Location> pool = arena.getHillPoints();
        if (pool.isEmpty()) return; // configured mid-round then cleared - just hold position

        currentPoolEntry = pickExcluding(pool, currentPoolEntry);
        hill = worlds.toActiveWorld(currentPoolEntry);
        holder = null;
        contested = false;
        secondsSinceRelocation = 0;

        ui.retarget(hill);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.sendMessage(Branding.message("The hill has moved!", Branding.WARNING));
        }
        drawRing();
        updateUi();
    }

    /** A random pool entry, excluding one matching {@code exclude}'s coordinates when the pool
     * has more than one choice - so a rotation always actually moves rather than sometimes
     * re-picking the same spot. Pool entries are freshly deserialized each call, so exclusion
     * compares coordinates rather than relying on object identity. */
    private Location pickExcluding(List<Location> pool, Location exclude) {
        List<Location> candidates = candidatesExcluding(pool, exclude);
        return candidates.get(random.nextInt(candidates.size()));
    }

    /**
     * The pure filtering rule behind {@link #pickExcluding}, split out so it's testable without
     * a running server: drop anything matching {@code exclude}'s coordinates, but only if that
     * leaves at least one option - a single-point pool (or an exclude that matches everything)
     * falls back to the full pool rather than rotating into an empty candidate list.
     */
    static List<Location> candidatesExcluding(List<Location> pool, Location exclude) {
        if (exclude == null || pool.size() <= 1) return pool;
        List<Location> filtered = new ArrayList<>();
        for (Location candidate : pool) {
            if (!sameCoordinates(candidate, exclude)) filtered.add(candidate);
        }
        return filtered.isEmpty() ? pool : filtered;
    }

    private static boolean sameCoordinates(Location a, Location b) {
        return a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
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
        String status = holder != null ? "Hill: " + holder.getDisplayName() + " holds"
                : contested ? "Hill: Contested"
                : "Hill: Neutral";
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
                    + "' - falling back to DUST.");
            return Particle.DUST;
        }
    }
}
