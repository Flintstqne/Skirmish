package org.flintstqne.skirmish.MapLogic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.flintstqne.skirmish.Utils.Branding;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * /arena - in-game map-data setup, writes straight to arena.yml (design doc §10).
 *
 * Verb-first, consistent shape: {@code /arena <set|add|get> <noun> [args]} - "set" replaces a
 * single value (spawn, boundary corner, world), "add" appends to a pool (ffa spawn, hill
 * point, capture point), "get" lists what's currently saved for a pool. Every level
 * tab-completes.
 *
 * {@code /arena tp} is the one noun-less exception: it teleports the admin straight into the
 * template world. Map setup only means anything if it's done there - a round world is a
 * throwaway clone deleted at round end (§7.3), so anything set while standing in one reads
 * back as "unknown world" the moment that round world is gone. This exists specifically so
 * nobody has to remember that and improvise a vanilla {@code /execute in <world> run tp} to
 * get back to solid ground.
 */
public final class ArenaAdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> VERBS = List.of("set", "add", "get", "tp");
    private static final List<String> SET_NOUNS = List.of("spawn", "boundary", "world");
    private static final List<String> ADD_NOUNS = List.of("ffaspawn", "hillpoint", "capturepoint");
    private static final List<String> GET_NOUNS = List.of("hillpoint", "capturepoint");
    private static final List<String> TEAMS = List.of("red", "blue");
    private static final List<String> CORNERS = List.of("corner1", "corner2");

    private static final String USAGE = """
            /arena set spawn <red|blue>
            /arena set boundary <corner1|corner2>
            /arena set world
            /arena add ffaspawn
            /arena add hillpoint
            /arena add capturepoint <name>
            /arena get hillpoint
            /arena get capturepoint
            /arena tp""";

    private final ArenaConfig arena;

    public ArenaAdminCommand(ArenaConfig arena) {
        this.arena = arena;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            Branding.error(sender, "Run this in-game - it uses your location.");
            return true;
        }
        if (args.length < 1) {
            usage(player);
            return true;
        }
        String verb = args[0].toLowerCase(Locale.ROOT);
        if (verb.equals("tp")) {
            handleTp(player);
            return true;
        }
        if (args.length < 2) {
            usage(player);
            return true;
        }

        Location loc = player.getLocation();
        String noun = args[1].toLowerCase(Locale.ROOT);

        boolean writesArenaData = (verb.equals("set") || verb.equals("add")) && !noun.equals("world");
        if (writesArenaData) {
            if (!isInTemplateWorld(player)) {
                error(player, "You're not standing in the arena template - run /arena tp first. "
                        + "Setting map data in a round world corrupts arena.yml the moment that world is disposed.");
                return true;
            }
            // Re-read from disk before mutating anything. ArenaConfig keeps one YamlConfiguration
            // for the plugin's whole lifetime, and every previous /arena set/add cached a live
            // Location bound to whatever World instance was current at the time. The first
            // Domination round ever run swaps the template for a new World object (see
            // WorldManager#unloadTemplate - unloaded and reloaded to copy it safely), so every
            // Location set before that round permanently references a now-dead World. Reloading
            // here discards that stale in-memory state - everything comes back as plain
            // deserialized data pointing at whatever World is actually live right now - so this
            // command's save() only ever serializes fresh references, not whatever bad state a
            // round transition left lying around from hours ago.
            arena.load();
        }

        boolean handled = switch (verb) {
            case "set" -> handleSet(player, loc, noun, args);
            case "add" -> handleAdd(player, loc, noun, args);
            case "get" -> handleGet(player, noun);
            default -> false;
        };

        if (!handled) {
            usage(player);
            return true;
        }
        if (!verb.equals("get")) arena.save();
        return true;
    }

    private boolean isInTemplateWorld(Player player) {
        String worldName = arena.getWorldName();
        return worldName != null && !worldName.isBlank() && player.getWorld().getName().equals(worldName);
    }

    private void handleTp(Player player) {
        String worldName = arena.getWorldName();
        if (worldName == null || worldName.isBlank()) {
            error(player, "No arena world configured yet - stand somewhere sane and run /arena set world.");
            return;
        }
        World templateWorld = Bukkit.getWorld(worldName);
        if (templateWorld == null) {
            error(player, "Arena template '" + worldName + "' isn't loaded right now.");
            return;
        }
        player.teleport(templateWorld.getSpawnLocation());
        ok(player, "Teleported to the arena template ('" + worldName
                + "') - anything you set here actually persists.");
    }

    private boolean handleSet(Player player, Location loc, String noun, String[] args) {
        return switch (noun) {
            case "spawn" -> {
                if (args.length < 3) yield false;
                String team = args[2].toLowerCase(Locale.ROOT);
                if (!TEAMS.contains(team)) {
                    error(player, "Team must be red or blue.");
                    yield true;
                }
                arena.setTeamSpawn(team, loc);
                ok(player, "Set " + team + " spawn here.");
                yield true;
            }
            case "boundary" -> {
                if (args.length < 3) yield false;
                String cornerArg = args[2].toLowerCase(Locale.ROOT);
                int corner = switch (cornerArg) {
                    case "corner1", "1" -> 1;
                    case "corner2", "2" -> 2;
                    default -> 0;
                };
                if (corner == 0) {
                    error(player, "Corner must be corner1 or corner2.");
                    yield true;
                }
                arena.setBoundaryCorner(corner, loc);
                ok(player, "Set boundary corner" + corner + " here (X/Z only - "
                        + "the wall always spans the full build height).");
                yield true;
            }
            case "world" -> {
                arena.setWorldName(player.getWorld().getName());
                ok(player, "Arena world set to '" + player.getWorld().getName() + "'. Restart to load it on enable.");
                yield true;
            }
            default -> false;
        };
    }

    private boolean handleAdd(Player player, Location loc, String noun, String[] args) {
        return switch (noun) {
            case "ffaspawn" -> {
                arena.addFfaSpawn(loc);
                ok(player, "Added FFA spawn (" + arena.getFfaSpawns().size() + " total).");
                yield true;
            }
            case "hillpoint" -> {
                arena.addHillPoint(loc);
                ok(player, "Added KOTH hill point (" + arena.getHillPoints().size() + " total).");
                yield true;
            }
            case "capturepoint" -> {
                if (args.length < 3) yield false;
                arena.setCapturePoint(args[2], loc);
                ok(player, "Set capture point '" + args[2] + "' here.");
                yield true;
            }
            default -> false;
        };
    }

    private boolean handleGet(Player player, String noun) {
        return switch (noun) {
            case "hillpoint" -> {
                List<Location> points = arena.getHillPoints();
                player.sendMessage(heading("KOTH hill points (" + points.size() + ")"));
                if (points.isEmpty()) player.sendMessage(Branding.message("None saved.", Branding.MUTED));
                for (int i = 0; i < points.size(); i++) {
                    player.sendMessage(Branding.message((i + 1) + ": " + coords(points.get(i)), Branding.HIGHLIGHT));
                }
                yield true;
            }
            case "capturepoint" -> {
                Map<String, Location> points = arena.getCapturePoints();
                player.sendMessage(heading("Domination capture points (" + points.size() + ")"));
                if (points.isEmpty()) player.sendMessage(Branding.message("None saved.", Branding.MUTED));
                points.forEach((name, loc) ->
                        player.sendMessage(Branding.message(name + ": " + coords(loc), Branding.HIGHLIGHT)));
                yield true;
            }
            default -> false;
        };
    }

    private Component heading(String title) {
        return Branding.prefix(Component.text(title, Branding.BRAND, TextDecoration.BOLD));
    }

    private String coords(Location loc) {
        return loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, String[] args) {
        String verb = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";

        List<String> options = switch (args.length) {
            case 1 -> VERBS;
            case 2 -> switch (verb) {
                case "set" -> SET_NOUNS;
                case "add" -> ADD_NOUNS;
                case "get" -> GET_NOUNS;
                default -> List.of();
            };
            case 3 -> thirdArgOptions(verb, args[1].toLowerCase(Locale.ROOT));
            default -> List.of();
        };

        String current = args.length == 0 ? "" : args[args.length - 1];
        return options.stream()
                .filter(option -> StringUtil.startsWithIgnoreCase(option, current))
                .collect(Collectors.toList());
    }

    private List<String> thirdArgOptions(String verb, String noun) {
        if (!verb.equals("set")) return List.of();
        return switch (noun) {
            case "spawn" -> TEAMS;
            case "boundary" -> CORNERS;
            default -> List.of();
        };
    }

    private void usage(Player p) {
        Branding.warning(p, USAGE);
    }

    private void ok(Player p, String msg) {
        Branding.success(p, msg);
    }

    private void error(Player p, String msg) {
        Branding.error(p, msg);
    }
}
