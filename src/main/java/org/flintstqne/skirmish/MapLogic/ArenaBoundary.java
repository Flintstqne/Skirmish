package org.flintstqne.skirmish.MapLogic;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * Resolves arena.yml's two boundary corners into an axis-aligned box on the live round
 * world. Coordinates are map data (template-bound, like team spawns); the world is only
 * ever attached at read time via {@link WorldManager} (design doc §7.3).
 *
 * The box's vertical extent always spans the world's full build height, regardless of the
 * Y a corner was set at - an admin standing anywhere when running `/arena set boundary`
 * shouldn't cap where the wall reaches. Only X/Z come from the two corners.
 */
public final class ArenaBoundary {

    /** Inclusive block-coordinate box. min/max are ordered regardless of corner order. */
    public record Bounds(World world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
    }

    private ArenaBoundary() {
    }

    /** @return null if no boundary is set yet, or there's no active round world to bind it to. */
    public static Bounds resolve(ArenaConfig arena, WorldManager worlds) {
        Location c1 = arena.getBoundaryCorner(1);
        Location c2 = arena.getBoundaryCorner(2);
        if (c1 == null || c2 == null || !worlds.hasActiveWorld()) return null;

        World world = worlds.getActiveWorld();
        int[] x = axis(c1.getX(), c2.getX());
        int[] z = axis(c1.getZ(), c2.getZ());
        return new Bounds(world, x[0], x[1], world.getMinHeight(), world.getMaxHeight() - 1, z[0], z[1]);
    }

    /** Pure min/max-as-block-coordinates, split out so it's testable without Bukkit. */
    static int[] axis(double a, double b) {
        return new int[] {(int) Math.floor(Math.min(a, b)), (int) Math.floor(Math.max(a, b))};
    }

    /**
     * Clamps a {@code [center - half, center + half]} window to {@code [axisMin, axisMax]}.
     * Used for both the tangent (along-the-wall) and vertical extent of a rendered patch.
     */
    static int[] window(int center, int half, int axisMin, int axisMax) {
        return new int[] {Math.max(axisMin, center - half), Math.min(axisMax, center + half)};
    }
}
