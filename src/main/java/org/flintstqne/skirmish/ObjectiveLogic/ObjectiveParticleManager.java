package org.flintstqne.skirmish.ObjectiveLogic;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;

/**
 * Particle rendering for capture-point objectives — KOTH's ring and Domination's planned
 * vertical beam both go through here (design doc §8.1/§8.2 explicitly call for shared
 * rendering code, not one particle method per gamemode). Each mode still owns its own
 * particle type and color choice; this class only knows how to draw a shape.
 */
public final class ObjectiveParticleManager {

    private ObjectiveParticleManager() {
    }

    /** Circular ring at {@code radius} around {@code center}, in its horizontal plane. */
    public static void drawRing(Location center, int radius, Particle particle, Color color, int points) {
        World world = center.getWorld();
        if (world == null) return;
        for (double[] offset : ringOffsets(radius, points)) {
            spawn(world, particle, center.clone().add(offset[0], 0.2, offset[1]), color);
        }
    }

    /** Vertical column of particles rising {@code height} blocks from {@code base}. */
    public static void drawBeam(Location base, int height, Particle particle, Color color, int density) {
        World world = base.getWorld();
        if (world == null) return;
        for (double y : beamHeights(height, density)) {
            spawn(world, particle, base.clone().add(0, y, 0), color);
        }
    }

    /** Pure ring geometry (x/z offsets from center), split out so it's testable without a World. */
    static double[][] ringOffsets(int radius, int points) {
        double[][] offsets = new double[points][2];
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            offsets[i][0] = radius * Math.cos(angle);
            offsets[i][1] = radius * Math.sin(angle);
        }
        return offsets;
    }

    /** Pure beam geometry (y offsets from base), split out so it's testable without a World. */
    static double[] beamHeights(int height, int density) {
        int steps = Math.max(1, height * density);
        double[] heights = new double[steps];
        for (int i = 0; i < steps; i++) {
            heights[i] = (double) i / density;
        }
        return heights;
    }

    private static void spawn(World world, Particle particle, Location point, Color color) {
        if (particle == Particle.DUST) {
            world.spawnParticle(particle, point, 1, new Particle.DustOptions(color, 1.0f));
        } else {
            world.spawnParticle(particle, point, 1);
        }
    }
}
