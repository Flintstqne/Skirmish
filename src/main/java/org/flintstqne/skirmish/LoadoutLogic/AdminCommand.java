package org.flintstqne.skirmish.LoadoutLogic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
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
 * /admin points — debug/testing aid for the per-life point economy (design doc §10).
 * Targets any online player by name, not just the sender — testing a loadout's afford-gate
 * usually means checking someone else's balance, not your own.
 */
public final class AdminCommand implements CommandExecutor, TabCompleter {

    private static final String USAGE = "/admin points <set|get> <player> [amount]";

    private final LoadoutService loadouts;

    public AdminCommand(LoadoutService loadouts) {
        this.loadouts = loadouts;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (args.length < 2 || !args[0].equalsIgnoreCase("points")) {
            usage(sender);
            return true;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "set" -> {
                if (args.length < 4) {
                    usage(sender);
                    return true;
                }
                Player target = resolvePlayer(sender, args[2]);
                if (target == null) return true;

                int amount;
                try {
                    amount = Integer.parseInt(args[3]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("Amount must be a whole number.", NamedTextColor.RED));
                    return true;
                }
                if (amount < 0) {
                    sender.sendMessage(Component.text("Amount can't be negative.", NamedTextColor.RED));
                    return true;
                }
                loadouts.setPoints(target, amount);
                sender.sendMessage(Component.text(
                        "Set " + target.getName() + "'s points to " + amount + ".", NamedTextColor.GREEN));
            }
            case "get" -> {
                if (args.length < 3) {
                    usage(sender);
                    return true;
                }
                Player target = resolvePlayer(sender, args[2]);
                if (target == null) return true;
                sender.sendMessage(Component.text(
                        target.getName() + " has " + loadouts.getPoints(target) + " points this life.",
                        NamedTextColor.AQUA));
            }
            default -> usage(sender);
        }
        return true;
    }

    private Player resolvePlayer(CommandSender sender, String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            sender.sendMessage(Component.text("No online player named '" + name + "'.", NamedTextColor.RED));
        }
        return player;
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(Component.text(USAGE, NamedTextColor.YELLOW));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, String[] args) {
        List<String> options = switch (args.length) {
            case 1 -> List.of("points");
            case 2 -> List.of("set", "get");
            case 3 -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            default -> List.<String>of();
        };
        String current = args.length == 0 ? "" : args[args.length - 1];
        return options.stream()
                .filter(option -> StringUtil.startsWithIgnoreCase(option, current))
                .collect(Collectors.toList());
    }
}
