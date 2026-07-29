package org.flintstqne.skirmish.LoadoutLogic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.flintstqne.skirmish.ConfigManager;
import org.flintstqne.skirmish.RoundLogic.RoundService;
import org.flintstqne.skirmish.StatLogic.StatService;
import org.flintstqne.skirmish.Utils.Branding;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * /admin — debug/admin tools (design doc §10): the per-life point economy, a config reload,
 * round-state diagnostics, and a lifetime-stats reset. Targets any online player by name for
 * the point commands, not just the sender — testing a loadout's afford-gate usually means
 * checking someone else's balance, not your own.
 */
public final class AdminCommand implements CommandExecutor, TabCompleter {

    private static final String USAGE = "/admin <points <set|get> <player> [amount]"
            + "|stats reset <player|all>|reload|diagnostics>";

    private final LoadoutService loadouts;
    private final ConfigManager config;
    private final RoundService rounds;
    private final StatService stats;

    public AdminCommand(LoadoutService loadouts, ConfigManager config, RoundService rounds, StatService stats) {
        this.loadouts = loadouts;
        this.config = config;
        this.rounds = rounds;
        this.stats = stats;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                config.reload();
                Branding.success(sender, "config.yml reloaded.");
            }
            case "diagnostics" -> runDiagnostics(sender);
            case "stats" -> runStatsReset(sender, args);
            case "points" -> runPoints(sender, args);
            default -> usage(sender);
        }
        return true;
    }

    private void runDiagnostics(CommandSender sender) {
        sender.sendMessage(heading("Round diagnostics"));
        sender.sendMessage(Component.text("Gamemode: " + rounds.getGamemode(), Branding.HIGHLIGHT));
        sender.sendMessage(Component.text("State: " + rounds.getState(), Branding.HIGHLIGHT));
        sender.sendMessage(Component.text("Warmup active: " + rounds.isWarmupActive(), Branding.HIGHLIGHT));
        sender.sendMessage(Component.text("Seconds remaining: " + rounds.getSecondsRemaining(), Branding.HIGHLIGHT));
        sender.sendMessage(Component.text("Playable gamemodes: " + RoundService.PLAYABLE, Branding.HIGHLIGHT));
    }

    private void runStatsReset(CommandSender sender, String[] args) {
        if (args.length < 3 || !args[1].equalsIgnoreCase("reset")) {
            Branding.warning(sender, "Usage: /admin stats reset <player|all>");
            return;
        }
        if (args[2].equalsIgnoreCase("all")) {
            stats.resetAllStats();
            Branding.error(sender, "Reset lifetime stats for every player.");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        stats.resetStats(target);
        Branding.error(sender, "Reset lifetime stats for " + target.getName() + ".");
    }

    private boolean runPoints(CommandSender sender, String[] args) {
        if (args.length < 2) {
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
                    Branding.error(sender, "Amount must be a whole number.");
                    return true;
                }
                if (amount < 0) {
                    Branding.error(sender, "Amount can't be negative.");
                    return true;
                }
                loadouts.setPoints(target, amount);
                Branding.success(sender, "Set " + target.getName() + "'s points to " + amount + ".");
            }
            case "get" -> {
                if (args.length < 3) {
                    usage(sender);
                    return true;
                }
                Player target = resolvePlayer(sender, args[2]);
                if (target == null) return true;
                Branding.info(sender, target.getName() + " has " + loadouts.getPoints(target) + " points this life.");
            }
            default -> usage(sender);
        }
        return true;
    }

    private Player resolvePlayer(CommandSender sender, String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            Branding.error(sender, "No online player named '" + name + "'.");
        }
        return player;
    }

    private void usage(CommandSender sender) {
        Branding.warning(sender, USAGE);
    }

    private Component heading(String title) {
        return Branding.prefix(Component.text(title, Branding.BRAND, TextDecoration.BOLD));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, String[] args) {
        List<String> options = switch (args.length) {
            case 1 -> List.of("points", "stats", "reload", "diagnostics");
            case 2 -> switch (args[0].toLowerCase(Locale.ROOT)) {
                case "points" -> List.of("set", "get");
                case "stats" -> List.of("reset");
                default -> List.<String>of();
            };
            case 3 -> {
                List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
                if (!args[0].equalsIgnoreCase("stats")) yield names;
                List<String> withAll = new java.util.ArrayList<>(names);
                withAll.add("all");
                yield withAll;
            }
            default -> List.<String>of();
        };
        String current = args.length == 0 ? "" : args[args.length - 1];
        return options.stream()
                .filter(option -> StringUtil.startsWithIgnoreCase(option, current))
                .collect(Collectors.toList());
    }
}
