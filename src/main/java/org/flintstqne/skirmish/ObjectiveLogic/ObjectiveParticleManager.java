package org.flintstqne.skirmish.ObjectiveLogic;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Particle rendering for capture-point objectives - KOTH's ring and Domination's planned
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

    /**
     * A real vanilla beacon light beam. Whether a beacon shows its beam is computed
     * server-side from the actual pyramid beneath it and pushed to clients as block-entity
     * data - a client-only faked block (no real pyramid behind it) never gets that "active"
     * state and never beams, which is why the first attempt at this (fake blocks via
     * {@code Player#sendBlockChange}) rendered the blocks but no beam. So this places REAL
     * blocks instead. Safe here specifically because Domination's capture points always sit
     * in the disposable per-round world (design doc §7.3) - never the persistent template -
     * so nothing durable is touched and there's nothing to clean up; the whole world is
     * deleted at round end along with everything placed in it.
     *
     * <p>The pyramid and beacon are buried one and two blocks below the point so the only
     * thing poking up at the objective's exact marker tile is the colored glass cap - the
     * capture radius is what matters for the objective, not the single center tile, so one
     * solid block there doesn't meaningfully obstruct play.
     */
    public static void placeBeacon(Location point) {
        World world = point.getWorld();
        if (world == null) return;
        int baseY = point.getBlockY() - 2;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.getBlockAt(point.getBlockX() + dx, baseY, point.getBlockZ() + dz)
                        .setType(Material.IRON_BLOCK, false);
            }
        }
        world.getBlockAt(point.getBlockX(), baseY + 1, point.getBlockZ())
                .setType(Material.BEACON, false);
    }

    /**
     * Fakes every real block above the beacon as air, client-only, so the beam renders
     * unobstructed all the way up (a real roof/floor overhead would otherwise cut the beam
     * short — beam length is a client-side render walk through whatever blocks the client
     * currently has loaded, real or faked, same trick {@link org.flintstqne.skirmish.MapLogic
     * .BorderWallRenderer} uses for the boundary wall, just inverted). The real blocks are
     * never touched, so players who try to walk or fall into that "open" column still collide
     * with actual solid terrain — this only clears the view, not the path.
     */
    public static void clearSkyAbove(Location point) {
        World world = point.getWorld();
        if (world == null) return;
        Material air = Material.AIR;
        var blockData = air.createBlockData();
        int x = point.getBlockX();
        int z = point.getBlockZ();
        int top = world.getMaxHeight() - 1;
        for (Player player : world.getPlayers()) {
            for (int y = point.getBlockY() + 1; y <= top; y++) {
                player.sendBlockChange(new Location(world, x, y, z), blockData);
            }
        }
    }

    /** Swaps the beacon's glass cap to reflect the current holder - called every objective
     * tick, same cadence as the ring, so the beam color tracks contest state live. */
    public static void setBeaconColor(Location point, Color color) {
        World world = point.getWorld();
        if (world == null) return;
        world.getBlockAt(point.getBlockX(), point.getBlockY(), point.getBlockZ())
                .setType(glassFor(color), false);
    }

    private static Material glassFor(Color color) {
        if (color.equals(Color.RED)) return Material.RED_STAINED_GLASS;
        if (color.equals(Color.BLUE)) return Material.BLUE_STAINED_GLASS;
        return Material.WHITE_STAINED_GLASS;
    }

    private static void spawn(World world, Particle particle, Location point, Color color) {
        if (particle == Particle.DUST) {
            world.spawnParticle(particle, point, 1, new Particle.DustOptions(color, 1.0f));
        } else {
            world.spawnParticle(particle, point, 1);
        }
    }
}
