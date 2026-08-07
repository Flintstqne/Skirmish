package org.flintstqne.skirmish.MapLogic;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Random-spawn selection for gamemodes with no fixed team spawns - FFA now, Gun Game later
 * (design doc §8.4/§8.5, both explicitly "random spawns"). Picks a pool point that clears
 * {@code min-spawn-distance-from-players} from everyone currently occupying the arena;
 * falls back to a random pick if nothing clears it rather than refusing to spawn anyone.
 */
public final class RandomSpawnSelector {

    private final ArenaConfig arena;
    private final WorldManager worlds;
    private final Random random = new Random();

    public RandomSpawnSelector(ArenaConfig arena, WorldManager worlds) {
        this.arena = arena;
        this.worlds = worlds;
    }

    /** @return null only if arena.yml has no FFA spawns configured at all. */
    public Location select(List<Location> occupied, int minDistanceBlocks) {
        List<Location> candidates = new ArrayList<>();
        for (Location loc : arena.getFfaSpawns()) {
            Location bound = worlds.toActiveWorld(loc);
            if (bound != null) candidates.add(bound);
        }
        if (candidates.isEmpty()) return null;

        Collections.shuffle(candidates, random);
        double[][] occupiedPoints = toPoints(occupied);

        for (Location candidate : candidates) {
            if (isFarEnough(toPoint(candidate), occupiedPoints, minDistanceBlocks)) return candidate;
        }
        // Nobody clears the bar (few spawns, many players) - spawn somewhere over refusing to.
        return candidates.get(0);
    }

    private double[] toPoint(Location loc) {
        return new double[] {loc.getX(), loc.getY(), loc.getZ()};
    }

    private double[][] toPoints(List<Location> locations) {
        double[][] points = new double[locations.size()][];
        for (int i = 0; i < locations.size(); i++) points[i] = toPoint(locations.get(i));
        return points;
    }

    /** Pure distance check, split out so it's testable without a live World. */
    static boolean isFarEnough(double[] candidate, double[][] occupied, double minDistanceBlocks) {
        double minSq = minDistanceBlocks * minDistanceBlocks;
        for (double[] point : occupied) {
            double dx = candidate[0] - point[0];
            double dy = candidate[1] - point[1];
            double dz = candidate[2] - point[2];
            if (dx * dx + dy * dy + dz * dz < minSq) return false;
        }
        return true;
    }
}
