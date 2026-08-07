package org.flintstqne.skirmish.MapLogic;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.flintstqne.skirmish.Skirmish;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Map data (arena.yml): world, boundary, spawns, hill/capture point pools.
 * Written by /arena admin commands, not hand-edited - kept separate from
 * config.yml tuning on purpose (design doc §6).
 *
 * Locations use Bukkit's built-in ConfigurationSerializable form rather than the
 * hand-rolled {x, y, z, yaw} maps sketched in the design doc - same data, no
 * parse/serialize code to maintain.
 */
public final class ArenaConfig {

    private final Skirmish plugin;
    private final File file;
    private YamlConfiguration yaml;

    public ArenaConfig(Skirmish plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "arena.yml");
    }

    public void load() {
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        yaml = YamlConfiguration.loadConfiguration(file);
    }

    /**
     * Reads just {@code world-name} without touching anything else in the file.
     *
     * Must be called - and the world it names loaded - before {@link #load()}: parsing the
     * full YAML eagerly deserializes every stored {@link Location}, including the team
     * spawns bound to this same world, and that silently fails ("unknown world") if the
     * world isn't loaded yet.
     */
    public String peekWorldName() {
        if (!file.isFile()) return null;
        try {
            for (String line : Files.readAllLines(file.toPath())) {
                String trimmed = line.strip();
                if (trimmed.startsWith("world-name:")) {
                    return trimmed.substring("world-name:".length()).strip().replaceAll("^[\"']|[\"']$", "");
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed reading arena.yml for world-name: " + e.getMessage());
        }
        return null;
    }

    public void save() {
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed saving arena.yml: " + e.getMessage());
        }
    }

    public String getWorldName() {
        return yaml.getString("world-name");
    }

    public void setWorldName(String name) {
        yaml.set("world-name", name);
    }

    // boundary - two corners; min/max derived when something needs a containment check.
    public Location getBoundaryCorner(int corner) {
        return yaml.getLocation("boundary.corner" + corner);
    }

    public void setBoundaryCorner(int corner, Location loc) {
        yaml.set("boundary.corner" + corner, loc);
    }

    // team spawns
    public List<Location> getTeamSpawns(String team) {
        return locationList("team-spawns." + team.toLowerCase(Locale.ROOT));
    }

    /** Replaces the team's spawn list with a single point - matches /arena setspawn. */
    public void setTeamSpawn(String team, Location loc) {
        yaml.set("team-spawns." + team.toLowerCase(Locale.ROOT), List.of(loc));
    }

    // ffa spawns
    public List<Location> getFfaSpawns() {
        return locationList("ffa-spawns");
    }

    public void addFfaSpawn(Location loc) {
        append("ffa-spawns", loc);
    }

    // koth hill pool
    public List<Location> getHillPoints() {
        return locationList("hill-points");
    }

    public void addHillPoint(Location loc) {
        append("hill-points", loc);
    }

    // domination capture point pool (named)
    public Map<String, Location> getCapturePoints() {
        Map<String, Location> points = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("capture-points");
        if (section == null) return points;
        for (String name : section.getKeys(false)) {
            Location loc = section.getLocation(name);
            if (loc != null) points.put(name, loc);
        }
        return points;
    }

    public void setCapturePoint(String name, Location loc) {
        yaml.set("capture-points." + name, loc);
    }

    private List<Location> locationList(String path) {
        List<Location> out = new ArrayList<>();
        for (Object o : yaml.getList(path, List.of())) {
            if (o instanceof Location loc) out.add(loc);
        }
        return out;
    }

    private void append(String path, Location loc) {
        List<Location> list = locationList(path);
        list.add(loc);
        yaml.set(path, list);
    }
}
