package org.flintstqne.skirmish.MapLogic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/** /arena — in-game map-data setup, writes straight to arena.yml (design doc §10). */
public final class ArenaAdminCommand implements CommandExecutor {

    private static final String USAGE = """
            /arena setspawn <red|blue>
            /arena addffaspawn
            /arena addhillpoint
            /arena addcapturepoint <name>
            /arena setboundary <corner1|corner2>
            /arena setworld""";

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
        if (args.length == 0) {
            usage(player);
            return true;
        }

        Location loc = player.getLocation();
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "setspawn" -> {
                if (args.length < 2) { usage(player); return true; }
                String team = args[1].toLowerCase(Locale.ROOT);
                if (!team.equals("red") && !team.equals("blue")) {
                    error(player, "Team must be red or blue.");
                    return true;
                }
                arena.setTeamSpawn(team, loc);
                ok(player, "Set " + team + " spawn here.");
            }
            case "addffaspawn" -> {
                arena.addFfaSpawn(loc);
                ok(player, "Added FFA spawn (" + arena.getFfaSpawns().size() + " total).");
            }
            case "addhillpoint" -> {
                arena.addHillPoint(loc);
                ok(player, "Added KOTH hill point (" + arena.getHillPoints().size() + " total).");
            }
            case "addcapturepoint" -> {
                if (args.length < 2) { usage(player); return true; }
                arena.setCapturePoint(args[1], loc);
                ok(player, "Set capture point '" + args[1] + "' here.");
            }
            case "setboundary" -> {
                if (args.length < 2) { usage(player); return true; }
                int corner = switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "corner1", "1" -> 1;
                    case "corner2", "2" -> 2;
                    default -> 0;
                };
                if (corner == 0) {
                    error(player, "Corner must be corner1 or corner2.");
                    return true;
                }
                arena.setBoundaryCorner(corner, loc);
                ok(player, "Set boundary corner" + corner + " here.");
            }
            case "setworld" -> {
                arena.setWorldName(player.getWorld().getName());
                ok(player, "Arena world set to '" + player.getWorld().getName() + "'. Restart to load it on enable.");
            }
            default -> {
                usage(player);
                return true;
            }
        }
        arena.save();
        return true;
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
