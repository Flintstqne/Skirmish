package org.flintstqne.skirmish.ObjectiveLogic;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.flintstqne.skirmish.CombatLogic.DeathSpectatorService;
import org.flintstqne.skirmish.TeamLogic.Team;
import org.flintstqne.skirmish.TeamLogic.TeamService;

import java.util.ArrayList;
import java.util.List;

/**
 * One Domination zone (design doc §8.2) - contestable independently of every other zone.
 *
 * <p>Capture is progress-based, not instant-hold: a lone team's presence moves a
 * {@code -maxSeconds..+maxSeconds} progress meter toward its side over real seconds of
 * uncontested presence; both teams present (contested) or the zone sitting empty both freeze
 * the meter where it is rather than resetting it. A team only "owns" the zone - and only then
 * does it score every tick, occupied or not - once the meter reaches its extreme. This means
 * an enemy has to first walk the meter back through neutral (eroding the old owner's hold)
 * before they can push it the rest of the way to their own side; simply standing there once
 * doesn't instantly flip control the way KOTH's {@link HillObjective#resolveHolder} does for
 * its single, relocating hill.
 */
public final class CapturePoint {

    private final String name;
    private final Location location;
    private double progressSeconds;
    private Team owner;
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

    /** Players of the owning team currently standing in the zone - empty if unowned, or if
     * the zone is owned but nobody happens to be there right now. */
    public List<Player> getHolderPlayers() {
        return holderPlayers;
    }

    /**
     * Re-evaluates capture progress and returns the current owner (null if neutral or
     * mid-capture). {@code rateSeconds} is how much the meter moves this tick when exactly one
     * team is present uncontested - pass the objective's real tick interval so progress tracks
     * wall-clock seconds regardless of how coarse the tick loop is; {@code maxSeconds} is
     * {@code domination.capture-time-seconds}, the meter's cap in either direction.
     */
    public Team tick(TeamService teams, DeathSpectatorService spectators, double radiusSq,
                     double rateSeconds, double maxSeconds) {
        List<Player> redPlayers = new ArrayList<>();
        List<Player> bluePlayers = new ArrayList<>();
        for (Player player : location.getWorld().getPlayers()) {
            if (spectators.isSpectating(player)) continue;
            if (location.distanceSquared(player.getLocation()) > radiusSq) continue;
            Team team = teams.getTeam(player);
            if (team == Team.RED) redPlayers.add(player);
            else if (team == Team.BLUE) bluePlayers.add(player);
        }

        boolean redOnly = !redPlayers.isEmpty() && bluePlayers.isEmpty();
        boolean blueOnly = !bluePlayers.isEmpty() && redPlayers.isEmpty();
        if (redOnly) progressSeconds = Math.min(maxSeconds, progressSeconds + rateSeconds);
        else if (blueOnly) progressSeconds = Math.max(-maxSeconds, progressSeconds - rateSeconds);

        owner = progressSeconds >= maxSeconds ? Team.RED : progressSeconds <= -maxSeconds ? Team.BLUE : null;
        holderPlayers = owner == null ? List.of() : owner == Team.RED ? redPlayers : bluePlayers;
        return owner;
    }
}
