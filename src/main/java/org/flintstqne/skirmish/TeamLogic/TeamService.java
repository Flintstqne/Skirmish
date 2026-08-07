package org.flintstqne.skirmish.TeamLogic;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.flintstqne.skirmish.ConfigManager;
import org.flintstqne.skirmish.MapLogic.ArenaConfig;
import org.flintstqne.skirmish.MapLogic.WorldManager;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Team assignment, imbalance locking and swap incentive (design doc §7.2).
 * Assignments are in-memory only - teams are round-scoped, nothing here touches data.db.
 *
 * Assignments deliberately survive a disconnect - a player who reconnects mid-round keeps
 * their team (see {@link org.flintstqne.skirmish.TeamLogic.TeamEnforcer}) - and are only
 * ever wiped in bulk by {@link #clearAll()} at the start of the next round.
 */
public final class TeamService {

    private final ConfigManager config;
    private final ArenaConfig arena;
    private final WorldManager worlds;
    private final Map<UUID, Team> assignments = new HashMap<>();
    private final Random random = new Random();

    public TeamService(ConfigManager config, ArenaConfig arena, WorldManager worlds) {
        this.config = config;
        this.arena = arena;
        this.worlds = worlds;
    }

    public Team getTeam(Player player) {
        return assignments.get(player.getUniqueId());
    }

    public boolean isSameTeam(Player a, Player b) {
        Team ta = getTeam(a);
        return ta != null && ta == getTeam(b);
    }

    public int count(Team team) {
        int n = 0;
        for (Team t : assignments.values()) if (t == team) n++;
        return n;
    }

    public Map<Team, Integer> counts() {
        Map<Team, Integer> map = new EnumMap<>(Team.class);
        for (Team t : Team.values()) map.put(t, count(t));
        return map;
    }

    /**
     * Whether joining {@code team} is currently blocked for being the fuller side.
     * Re-evaluated on every call, so the lock lifts on its own once the gap closes.
     */
    public boolean isLocked(Team team) {
        return isLocked(count(team), count(team.opposite()), config.getImbalanceLockRatio());
    }

    /** Pure lock rule, split out so it can be tested without a running server. */
    public static boolean isLocked(int mine, int theirs, double ratio) {
        if (mine <= theirs) return false;
        if (theirs == 0) return true;
        return (double) mine / theirs >= ratio;
    }

    /** The under-populated team while an imbalance lock is active, else null. */
    public Team getShortTeam() {
        for (Team t : Team.values()) if (isLocked(t)) return t.opposite();
        return null;
    }

    /** @return false if the team is locked; the player keeps whatever team they had. */
    public boolean join(Player player, Team team) {
        if (team == getTeam(player)) return true;
        if (isLocked(team)) return false;
        assignments.put(player.getUniqueId(), team);
        return true;
    }

    /**
     * Voluntary switch to the short team. Just the assignment - the swap incentive bonus
     * (§7.2, {@code config.getSwapIncentiveMerit()}) is awarded by the caller
     * ({@link TeamSelectGui}) on a successful swap, since that's where the amount is
     * already read to render the GUI button.
     */
    public boolean swapToShortTeam(Player player) {
        Team target = getShortTeam();
        if (target == null || getTeam(player) != target.opposite()) return false;
        assignments.put(player.getUniqueId(), target);
        return true;
    }

    public void clearAll() {
        assignments.clear();
    }

    /**
     * Random spawn for the player's team, re-bound onto the live round world - arena.yml
     * coordinates belong to the template, which is never played in (§7.3).
     */
    public Location getSpawn(Player player) {
        Team team = getTeam(player);
        List<Location> spawns = team == null ? List.of() : arena.getTeamSpawns(team.name());
        if (spawns.isEmpty()) return defaultSpawn(player);
        Location spawn = worlds.toActiveWorld(spawns.get(random.nextInt(spawns.size())));
        return spawn == null ? defaultSpawn(player) : spawn;
    }

    /**
     * A random spawn for an arbitrary team, not tied to a specific player - null if none are
     * configured. Used by {@link org.flintstqne.skirmish.BotLogic.BotObjectiveTask} to point
     * bots at the opposing team's spawn in gamemodes with no capture objective (TDM), since
     * team spawns can easily be farther apart than a bot's targeting range.
     */
    public Location getSpawn(Team team) {
        List<Location> spawns = arena.getTeamSpawns(team.name());
        if (spawns.isEmpty()) return null;
        return worlds.toActiveWorld(spawns.get(random.nextInt(spawns.size())));
    }

    private Location defaultSpawn(Player player) {
        return worlds.hasActiveWorld()
                ? worlds.getActiveWorld().getSpawnLocation()
                : player.getWorld().getSpawnLocation();
    }

    /** All configured team spawns, on the live round world - used by spawn zones (§7.7). */
    public List<Location> getAllTeamSpawns() {
        List<Location> all = new java.util.ArrayList<>();
        for (Team t : Team.values()) {
            for (Location spawn : arena.getTeamSpawns(t.name())) {
                Location bound = worlds.toActiveWorld(spawn);
                if (bound != null) all.add(bound);
            }
        }
        return all;
    }
}
