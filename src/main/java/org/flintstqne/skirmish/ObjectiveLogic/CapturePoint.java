package org.flintstqne.skirmish.ObjectiveLogic;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.flintstqne.skirmish.CombatLogic.DeathSpectatorService;
import org.flintstqne.skirmish.TeamLogic.Team;
import org.flintstqne.skirmish.TeamLogic.TeamService;

import java.util.ArrayList;
import java.util.List;

/**
 * One Domination zone (design doc §8.2) — contestable independently of every other zone,
 * using the exact same present/uncontested rule as KOTH's hill
 * ({@link HillObjective#resolveHolder}); reused directly rather than duplicated or shared
 * through a common base class.
 */
public final class CapturePoint {

    private final String name;
    private final Location location;
    private Team holder;
    private List<Player> holderPlayers = List.of();

    public CapturePoint(String name, Location location) {
        this.name = name;
        this.location = location;
    }

    public String getName() {
        return name;
    }

    public Location getLocation() {
        return location;
    }

    /** Players currently standing in the zone for whichever team holds it — empty if unheld/contested. */
    public List<Player> getHolderPlayers() {
        return holderPlayers;
    }

    /** Re-evaluates who holds this zone right now and returns it (null if unheld/contested). */
    public Team tick(TeamService teams, DeathSpectatorService spectators, double radiusSq) {
        List<Player> redPlayers = new ArrayList<>();
        List<Player> bluePlayers = new ArrayList<>();
        for (Player player : location.getWorld().getPlayers()) {
            if (spectators.isSpectating(player)) continue;
            if (location.distanceSquared(player.getLocation()) > radiusSq) continue;
            Team team = teams.getTeam(player);
            if (team == Team.RED) redPlayers.add(player);
            else if (team == Team.BLUE) bluePlayers.add(player);
        }
        holder = HillObjective.resolveHolder(!redPlayers.isEmpty(), !bluePlayers.isEmpty());
        holderPlayers = holder == null ? List.of() : holder == Team.RED ? redPlayers : bluePlayers;
        return holder;
    }
}
