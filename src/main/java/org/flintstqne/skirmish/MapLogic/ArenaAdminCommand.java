package org.flintstqne.skirmish.MapLogic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * /arena — in-game map-data setup, writes straight to arena.yml (design doc §10).
 *
 * Verb-first, consistent shape: {@code /arena <set|add> <noun> [args]} — "set" replaces a
 * single value (spawn, boundary corner, world), "add" appends to a pool (ffa spawn, hill
 * point, capture point). Every level tab-completes.
 */
public final class ArenaAdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> VERBS = List.of("set", "add");
    private static final List<String> SET_NOUNS = List.of("spawn", "boundary", "world");
    private static final List<String> ADD_NOUNS = List.of("ffaspawn", "hillpoint", "capturepoint");
    private static final List<String> TEAMS = List.of("red", "blue");
    private static final List<String> CORNERS = List.of("corner1", "corner2");

    private static final String USAGE = """
            /arena set spawn <red|blue>
            /arena set boundary <corner1|corner2>
            /arena set world
            /arena add ffaspawn
            /arena add hillpoint
            /arena add capturepoint <name>""";

    private final ArenaConfig arena;

    public ArenaAdminCommand(ArenaConfig arena) {
        this.arena = arena;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Run this in-game — it uses your location.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            usage(player);
            return true;
        }

        Location loc = player.getLocation();
        String verb = args[0].toLowerCase(Locale.ROOT);
        String noun = args[1].toLowerCase(Locale.ROOT);

        boolean handled = switch (verb) {
            case "set" -> handleSet(player, loc, noun, args);
            case "add" -> handleAdd(player, loc, noun, args);
            default -> false;
        };

        if (!handled) {
            usage(player);
            return true;
        }
        arena.save();
        return true;
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
                ok(player, "Set boundary corner" + corner + " here (X/Z only — "
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

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, String[] args) {
        String verb = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";

        List<String> options = switch (args.length) {
            case 1 -> VERBS;
            case 2 -> switch (verb) {
                case "set" -> SET_NOUNS;
                case "add" -> ADD_NOUNS;
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
        p.sendMessage(Component.text(USAGE, NamedTextColor.YELLOW));
    }

    private void ok(Player p, String msg) {
        p.sendMessage(Component.text("[Skirmish] " + msg, NamedTextColor.GREEN));
    }

    private void error(Player p, String msg) {
        p.sendMessage(Component.text("[Skirmish] " + msg, NamedTextColor.RED));
    }
}
