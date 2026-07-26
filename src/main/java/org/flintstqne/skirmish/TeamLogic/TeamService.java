package org.flintstqne.skirmish.TeamLogic;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.flintstqne.skirmish.ConfigManager;
import org.flintstqne.skirmish.MapLogic.ArenaConfig;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Team assignment, imbalance locking and swap incentive (design doc §7.2).
 * Assignments are in-memory only — teams are round-scoped, nothing here touches data.db.
 */
public final class TeamService implements Listener {

    private final ConfigManager config;
    private final ArenaConfig arena;
    private final Map<UUID, Team> assignments = new HashMap<>();
    private final Random random = new Random();

    public TeamService(ConfigManager config, ArenaConfig arena) {
        this.config = config;
        this.arena = arena;
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
     * Voluntary switch to the short team for the swap incentive bonus (§7.2).
     * ponytail: bonus points aren't granted yet — the per-life economy lands in
     * LoadoutService (milestone 3); award {@code config.getSwapIncentivePoints()} there.
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

    /** Random spawn for the player's team; falls back to the arena world spawn. */
    public Location getSpawn(Player player) {
        Team team = getTeam(player);
        if (team == null) return player.getWorld().getSpawnLocation();
        List<Location> spawns = arena.getTeamSpawns(team.name());
        if (spawns.isEmpty()) return player.getWorld().getSpawnLocation();
        return spawns.get(random.nextInt(spawns.size()));
    }

    /** All configured team spawns — used for the spawn-protection zones (§7.7). */
    public List<Location> getAllTeamSpawns() {
        List<Location> all = new java.util.ArrayList<>();
        for (Team t : Team.values()) all.addAll(arena.getTeamSpawns(t.name()));
        return all;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        assignments.remove(event.getPlayer().getUniqueId());
    }
}
