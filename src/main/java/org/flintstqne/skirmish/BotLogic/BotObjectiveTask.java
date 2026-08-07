package org.flintstqne.skirmish.BotLogic;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.flintstqne.skirmish.ObjectiveLogic.CapturePoint;
import org.flintstqne.skirmish.ObjectiveLogic.DominationObjective;
import org.flintstqne.skirmish.ObjectiveLogic.HillObjective;
import org.flintstqne.skirmish.RoundLogic.GamemodeType;
import org.flintstqne.skirmish.RoundLogic.RoundService;
import org.flintstqne.skirmish.Skirmish;
import org.flintstqne.skirmish.TeamLogic.Team;
import org.flintstqne.skirmish.TeamLogic.TeamService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Points bots at "try to capture the objective" without reimplementing capture logic - the
 * actual capture math already lives server-side in {@link HillObjective}/{@link DominationObjective}
 * and sees bots exactly like real players once they're standing in the zone. This task only
 * periodically whispers a bot a {@code goto <x> <y> <z>}; the bot's own combat AI takes over
 * (and interrupts movement) the moment it spots an enemy - matching the "shoot on sight,
 * capture otherwise" scope, not a full hold-and-defend behavior.
 *
 * <p>KOTH/Domination share one destination for every bot (the live objective). TDM has no
 * objective at all, but still needs this: team spawns can easily be farther apart than a bot's
 * targeting range, so without something pulling it toward the fight, a bot just stands at its
 * own spawn forever - confirmed live, blue and red bots spawned ~74 blocks apart, well past the
 * default 64-block range, and never acquired a target the whole session. For TDM, each bot gets
 * its own destination: the opposing team's spawn.
 */
public final class BotObjectiveTask {

    private static final long PERIOD_TICKS = 140L; // ~7s

    private final Skirmish plugin;
    private final BotService bots;
    private final RoundService rounds;
    private final HillObjective hill;
    private final DominationObjective domination;
    private final TeamService teams;
    /** Bot name -> assigned capture point, so a bot commits to holding one point instead of
     * getting reassigned to a fresh random one on every ~7s tick (confirmed live: bots thrashed
     * between points constantly and never actually held/captured any of them). */
    private final Map<String, CapturePoint> dominationAssignments = new HashMap<>();

    private BukkitTask task;

    public BotObjectiveTask(Skirmish plugin, BotService bots, RoundService rounds,
                            HillObjective hill, DominationObjective domination, TeamService teams) {
        this.plugin = plugin;
        this.bots = bots;
        this.rounds = rounds;
        this.hill = hill;
        this.domination = domination;
        this.teams = teams;
    }

    public void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, PERIOD_TICKS, PERIOD_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        if (bots.getAll().isEmpty() || !rounds.isActive()) return;

        bots.syncAllies();

        GamemodeType mode = rounds.getGamemode();
        Location sharedObjective = resolveSharedObjective(mode);

        for (BotService.BotRecord bot : bots.getAll()) {
            if (bot.status() != BotService.Status.LIVE) continue;

            Location destination = sharedObjective != null ? sharedObjective
                    : mode == GamemodeType.DOMINATION ? resolveDominationTarget(bot)
                    : resolvePerBotFallback(mode, bot);
            if (destination == null) continue;

            String goto_ = "goto " + destination.getBlockX() + " " + destination.getBlockY() + " " + destination.getBlockZ();
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "msg " + bot.name() + " " + goto_);
        }
    }

    /** KOTH - every bot heads to the same live hill. Domination and TDM resolve per-bot instead
     * (a single shared point doesn't fit multiple capture zones, and TDM has no point at all). */
    private Location resolveSharedObjective(GamemodeType mode) {
        return mode == GamemodeType.KOTH ? hill.getHill() : null;
    }

    /** Domination - each bot commits to one capture point (a stable pick, not re-rolled every
     * tick) so it actually has time to stand in the zone and contest it, rather than being
     * yanked to a different point before the capture math ever credits it. Reassigned only if
     * the round's zone set has changed under it (e.g. a new round started). */
    private Location resolveDominationTarget(BotService.BotRecord bot) {
        List<CapturePoint> points = domination.getPoints();
        if (points.isEmpty()) {
            dominationAssignments.remove(bot.name());
            return null;
        }

        CapturePoint assigned = dominationAssignments.get(bot.name());
        if (assigned == null || !points.contains(assigned)) {
            assigned = points.get(Math.floorMod(bot.name().hashCode(), points.size()));
            dominationAssignments.put(bot.name(), assigned);
        }
        return assigned.getLocation();
    }

    /** TDM - no shared objective, so send each bot toward the nearest live enemy instead of a
     * fixed point; falls back to the opposing spawn only when no enemy is currently reachable
     * (e.g. round just started and nobody's in range yet). */
    private Location resolvePerBotFallback(GamemodeType mode, BotService.BotRecord bot) {
        if (mode != GamemodeType.TDM || bot.team() == null) return null;
        Player self = Bukkit.getPlayer(bot.name());
        if (self == null) return null;
        Location nearestEnemy = nearestEnemyLocation(self, bot.team());
        return nearestEnemy != null ? nearestEnemy : teams.getSpawn(bot.team().opposite());
    }

    private Location nearestEnemyLocation(Player self, Team myTeam) {
        Location best = null;
        double bestDistanceSquared = Double.MAX_VALUE;
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(self) || teams.getTeam(other) != myTeam.opposite()) continue;
            if (!other.getWorld().equals(self.getWorld())) continue;
            double distanceSquared = other.getLocation().distanceSquared(self.getLocation());
            if (distanceSquared < bestDistanceSquared) {
                bestDistanceSquared = distanceSquared;
                best = other.getLocation();
            }
        }
        return best;
    }
}
