package org.flintstqne.skirmish.MapLogic;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.flintstqne.skirmish.Skirmish;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Consumer;

/**
 * One throwaway world per round (design doc §7.3).
 *
 * The arena world named in arena.yml is a pristine <em>template</em> that is never played
 * in. Each round runs in a copy; at round end the copy is unloaded without saving and
 * deleted. Nothing is ever reverted because nothing durable is ever damaged.
 *
 * Crash recovery falls out of the naming scheme: round worlds are all prefixed
 * {@value #ROUND_PREFIX}, so any that exist at startup are leftovers from a run that died
 * and get swept. That also stops a stale folder from colliding with a fresh round.
 */
public final class WorldManager {

    private static final String ROUND_PREFIX = "skirmish_round_";

    /** Per-player junk that would only bloat every copy. */
    private static final Set<String> SKIPPED = Set.of(
            "session.lock", "uid.dat", "playerdata", "stats", "advancements");

    /** Delay before deleting the world players just left, so nobody is still inside it. */
    private static final long DISPOSE_DELAY_TICKS = 200L;

    private final Skirmish plugin;
    private final ArenaConfig arena;

    private World activeWorld;
    private int counter;

    public WorldManager(Skirmish plugin, ArenaConfig arena) {
        this.plugin = plugin;
        this.arena = arena;
    }

    public World getActiveWorld() {
        return activeWorld;
    }

    public boolean hasActiveWorld() {
        return activeWorld != null;
    }

    /**
     * Re-binds a stored arena.yml location onto the live round world. Coordinates are
     * map data; the world reference is only ever attached at read time, because the world
     * a location belongs to changes every round.
     */
    public Location toActiveWorld(Location stored) {
        if (stored == null) return null;
        World world = activeWorld;
        if (world == null) return stored;
        return new Location(world, stored.getX(), stored.getY(), stored.getZ(),
                stored.getYaw(), stored.getPitch());
    }

    /** Deletes leftover round worlds from a previous run. Call once, on enable. */
    public int sweepOrphanWorlds() {
        File container = Bukkit.getWorldContainer();
        File[] candidates = container.listFiles(file ->
                file.isDirectory() && file.getName().startsWith(ROUND_PREFIX));
        if (candidates == null) return 0;

        int swept = 0;
        for (File folder : candidates) {
            World loaded = Bukkit.getWorld(folder.getName());
            if (loaded != null) Bukkit.unloadWorld(loaded, false);
            if (deleteRecursively(folder.toPath())) swept++;
        }
        return swept;
    }

    /**
     * Copies the template off the main thread, then loads the copy on it.
     *
     * @param callback handed the new world, or null if the copy or load failed
     */
    public void prepareRoundWorld(Consumer<World> callback) {
        String templateName = arena.getWorldName();
        if (templateName == null || templateName.isBlank()) {
            plugin.getLogger().severe("No arena world set — run /arena setworld in the template world.");
            callback.accept(null);
            return;
        }

        File template = new File(Bukkit.getWorldContainer(), templateName);
        if (!template.isDirectory()) {
            plugin.getLogger().severe("Arena template world '" + templateName + "' is not on disk.");
            callback.accept(null);
            return;
        }

        // A loaded template keeps ticking — mobs walk between chunks, autosave fires — so
        // a mid-copy write races the copy and produces corrupt regionfiles and duplicate
        // entities in the result. World#save() alone doesn't stop that; only a full unload
        // (which blocks until every chunk is flushed and closed) does.
        boolean reloadTemplateAfter = unloadTemplate(templateName);

        String name = ROUND_PREFIX + (++counter);
        File destination = new File(Bukkit.getWorldContainer(), name);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean copied = copyTemplate(template.toPath(), destination.toPath());
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (reloadTemplateAfter) Bukkit.createWorld(new WorldCreator(templateName));
                if (!copied) {
                    deleteRecursively(destination.toPath());
                    callback.accept(null);
                    return;
                }
                callback.accept(loadAndConfigure(name));
            });
        });
    }

    /** @return true if the template was loaded (and should be reloaded once copying finishes). */
    private boolean unloadTemplate(String templateName) {
        World templateWorld = Bukkit.getWorld(templateName);
        if (templateWorld == null) return false;

        Location fallback = fallbackSpawn(templateWorld);
        for (Player player : templateWorld.getPlayers()) player.teleport(fallback);

        if (!Bukkit.unloadWorld(templateWorld, true)) {
            plugin.getLogger().warning("Could not unload arena template '" + templateName
                    + "' before copying it — the round world may come out corrupted.");
            return false;
        }
        return true;
    }

    private World loadAndConfigure(String name) {
        World world = Bukkit.createWorld(new WorldCreator(name));
        if (world == null) {
            plugin.getLogger().severe("Failed to load round world '" + name + "'.");
            deleteRecursively(new File(Bukkit.getWorldContainer(), name).toPath());
            return null;
        }

        // Never save: the copy is disposable, and skipping writes keeps unload cheap.
        world.setAutoSave(false);
        world.setKeepSpawnInMemory(false);
        world.setDifficulty(Difficulty.NORMAL);
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        world.setGameRule(GameRule.SHOW_DEATH_MESSAGES, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, false);
        world.setTime(6000);
        world.setClearWeatherDuration(Integer.MAX_VALUE);
        return world;
    }

    /**
     * Promotes {@code world} to the active round world and schedules the one it replaces
     * for deletion, giving players time to be moved out first.
     */
    public void setActiveWorld(World world) {
        World previous = activeWorld;
        activeWorld = world;
        if (previous == null || previous.equals(world)) return;

        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> unloadAndDelete(previous), DISPOSE_DELAY_TICKS);
    }

    /** Unloads without saving and deletes the folder. Anyone still inside is moved out first. */
    public void unloadAndDelete(World world) {
        if (world == null) return;
        if (!world.getName().startsWith(ROUND_PREFIX)) {
            plugin.getLogger().warning("Refusing to delete '" + world.getName() + "' — not a round world.");
            return;
        }

        Location fallback = fallbackSpawn(world);
        for (Player player : world.getPlayers()) player.teleport(fallback);

        File folder = world.getWorldFolder();
        if (!Bukkit.unloadWorld(world, false)) {
            plugin.getLogger().warning("Could not unload round world '" + world.getName() + "'.");
            return;
        }
        if (!deleteRecursively(folder.toPath())) {
            plugin.getLogger().warning("Could not fully delete '" + folder.getName()
                    + "' — it will be swept on next startup.");
        }
    }

    /** Somewhere safe that is definitely not the world being deleted. */
    private Location fallbackSpawn(World leaving) {
        if (activeWorld != null && !activeWorld.equals(leaving)) return activeWorld.getSpawnLocation();
        for (World world : Bukkit.getWorlds()) {
            if (!world.equals(leaving)) return world.getSpawnLocation();
        }
        return leaving.getSpawnLocation();
    }

    /** Disposes the active round world. Call on plugin disable. */
    public void shutdown() {
        World world = activeWorld;
        activeWorld = null;
        unloadAndDelete(world);
    }

    private boolean copyTemplate(Path source, Path destination) {
        try {
            long start = System.currentTimeMillis();
            WorldFiles.copy(source, destination, SKIPPED);
            plugin.getLogger().info("Cloned arena template ("
                    + WorldFiles.size(destination) / 1_048_576 + " MB) in "
                    + (System.currentTimeMillis() - start) + " ms.");
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Failed copying arena template: " + e.getMessage());
            return false;
        }
    }

    private boolean deleteRecursively(Path path) {
        try {
            WorldFiles.deleteRecursively(path);
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("Failed deleting '" + path.getFileName() + "': " + e.getMessage());
            return false;
        }
    }
}
