package org.flintstqne.skirmish.MapLogic;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.flintstqne.skirmish.ConfigManager;
import org.flintstqne.skirmish.Skirmish;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Renders the arena.yml boundary as a visible wall (design doc §10's `/arena setboundary`,
 * previously stored but never rendered or enforced) plus a movement backstop.
 *
 * The wall is client-only, sent per player with {@link Player#sendBlockChange}, so it never
 * touches the real world. Only a window near each player is ever drawn - the box perimeter
 * at full height is tens of thousands of blocks, almost none of which anyone can see at once.
 * A player who approaches a corner sees two independent patches, one per nearby face, rather
 * than a merged diagonal corner - visually adequate, and skips a lot of projection math for it.
 *
 * The fake blocks are what actually stops most players (the client treats them as solid
 * terrain). The move-cancel is only a backstop for whoever clips through before the client
 * catches up, since the server has no real block there to collide with on its own.
 */
public final class BorderWallRenderer implements Listener {

    private enum Face { MIN_X, MAX_X, MIN_Z, MAX_Z }

    /** One rendered patch: contiguous along the wall's tangent axis, and vertically. */
    private record Window(int tangentMin, int tangentMax, int yMin, int yMax) {
    }

    private final Skirmish plugin;
    private final ConfigManager config;
    private final ArenaConfig arena;
    private final WorldManager worlds;
    private final Map<UUID, Map<Face, Window>> shown = new HashMap<>();

    private ArenaBoundary.Bounds bounds;
    private BukkitTask task;

    public BorderWallRenderer(Skirmish plugin, ConfigManager config, ArenaConfig arena, WorldManager worlds) {
        this.plugin = plugin;
        this.config = config;
        this.arena = arena;
        this.worlds = worlds;
    }

    /** Starts rendering against whatever boundary is configured for the current round world. */
    public void startForActiveRound() {
        stop();
        bounds = ArenaBoundary.resolve(arena, worlds);
        if (bounds == null || !config.isArenaBorderEnabled()) return;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick,
                0L, config.getBorderTickIntervalTicks());
    }

    /** Stops rendering and reverts every fake block currently shown to anyone. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (bounds != null) {
            shown.forEach((id, windows) -> {
                Player player = plugin.getServer().getPlayer(id);
                if (player != null) windows.forEach((face, window) -> revert(player, face, window));
            });
        }
        shown.clear();
        bounds = null;
    }

    private void tick() {
        if (bounds == null) return;
        for (Player player : bounds.world().getPlayers()) {
            update(player);
        }
    }

    private void update(Player player) {
        Map<Face, Window> previous = shown.computeIfAbsent(player.getUniqueId(), id -> new EnumMap<>(Face.class));
        Location loc = player.getLocation();
        int px = loc.getBlockX();
        int py = loc.getBlockY();
        int pz = loc.getBlockZ();
        int renderDistance = config.getBorderRenderDistance();

        for (Face face : Face.values()) {
            Window newWindow = distanceTo(face, px, pz) <= renderDistance ? windowFor(face, px, py, pz) : null;
            Window oldWindow = previous.get(face);
            if (Objects.equals(newWindow, oldWindow)) continue;

            if (oldWindow != null) revert(player, face, oldWindow);
            if (newWindow != null) {
                render(player, face, newWindow);
                previous.put(face, newWindow);
            } else {
                previous.remove(face);
            }
        }
    }

    private int distanceTo(Face face, int x, int z) {
        return switch (face) {
            case MIN_X -> x - bounds.minX();
            case MAX_X -> bounds.maxX() - x;
            case MIN_Z -> z - bounds.minZ();
            case MAX_Z -> bounds.maxZ() - z;
        };
    }

    private Window windowFor(Face face, int px, int py, int pz) {
        boolean xFacing = face == Face.MIN_X || face == Face.MAX_X;
        int tangent = xFacing ? pz : px;
        int tangentMin = xFacing ? bounds.minZ() : bounds.minX();
        int tangentMax = xFacing ? bounds.maxZ() : bounds.maxX();

        int[] tangentRange = ArenaBoundary.window(tangent, config.getBorderWallHalfWidth(), tangentMin, tangentMax);
        int[] yRange = ArenaBoundary.window(py, config.getBorderWallHalfHeight(), bounds.minY(), bounds.maxY());
        return new Window(tangentRange[0], tangentRange[1], yRange[0], yRange[1]);
    }

    private void render(Player player, Face face, Window window) {
        BlockData data = config.getBorderMaterial().createBlockData();
        forEachBlock(face, window, (x, y, z) -> player.sendBlockChange(new Location(bounds.world(), x, y, z), data));
    }

    /** Sends each block's real, current state back - this is client-only, nothing was ever placed. */
    private void revert(Player player, Face face, Window window) {
        World world = bounds.world();
        forEachBlock(face, window, (x, y, z) -> {
            Location loc = new Location(world, x, y, z);
            player.sendBlockChange(loc, loc.getBlock().getBlockData());
        });
    }

    private void forEachBlock(Face face, Window w, BlockAction action) {
        int plane = switch (face) {
            case MIN_X -> bounds.minX() - 1;
            case MAX_X -> bounds.maxX() + 1;
            case MIN_Z -> bounds.minZ() - 1;
            case MAX_Z -> bounds.maxZ() + 1;
        };
        boolean xFacing = face == Face.MIN_X || face == Face.MAX_X;
        for (int tangent = w.tangentMin(); tangent <= w.tangentMax(); tangent++) {
            for (int y = w.yMin(); y <= w.yMax(); y++) {
                action.run(xFacing ? plane : tangent, y, xFacing ? tangent : plane);
            }
        }
    }

    @FunctionalInterface
    private interface BlockAction {
        void run(int x, int y, int z);
    }

    /** Backstop: cancels movement well past the boundary, for whoever clips through the wall. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (bounds == null) return;
        Location to = event.getTo();
        if (to.getWorld() == null || !to.getWorld().equals(bounds.world())) return;

        int margin = config.getBorderEnforceMargin();
        if (to.getBlockX() < bounds.minX() - margin || to.getBlockX() > bounds.maxX() + margin
                || to.getBlockZ() < bounds.minZ() - margin || to.getBlockZ() > bounds.maxZ() + margin
                || to.getBlockY() < bounds.minY() - margin || to.getBlockY() > bounds.maxY() + margin) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        shown.remove(event.getPlayer().getUniqueId());
    }
}
