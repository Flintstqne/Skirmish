package org.flintstqne.skirmish.StatLogic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/** /stats [player], /leaderboard <category>, and /history (design doc §7.10, §10). */
public final class StatCommand implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final StatService stats;

    public StatCommand(StatService stats) {
        this.stats = stats;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        return switch (label.toLowerCase()) {
            case "stats" -> runStats(sender, args);
            case "leaderboard" -> runLeaderboard(sender, args);
            case "history" -> runHistory(sender, args);
            default -> false;
        };
    }

    private boolean runHistory(CommandSender sender, String[] args) {
        int limit = 10;
        if (args.length >= 1) {
            try {
                limit = Math.max(1, Math.min(25, Integer.parseInt(args[0])));
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("Usage: /history [count]", NamedTextColor.YELLOW));
                return true;
            }
        }

        var rounds = stats.getRecentRounds(limit);
        sender.sendMessage(Component.text("--- Recent rounds ---", NamedTextColor.GOLD));
        if (rounds.isEmpty()) {
            sender.sendMessage(Component.text("No rounds recorded yet.", NamedTextColor.GRAY));
            return true;
        }
        for (var round : rounds) {
            String when = TIMESTAMP.format(Instant.ofEpochMilli(round.startedAt()));
            String winner = round.winner() == null ? "Draw" : round.winner();
            sender.sendMessage(Component.text(when + "  " + round.gamemode() + "  "
                    + round.finalScoreA() + "-" + round.finalScoreB() + "  Winner: " + winner, NamedTextColor.WHITE));
        }
        return true;
    }

    private boolean runStats(CommandSender sender, String[] args) {
        OfflinePlayer target;
        if (args.length >= 1) {
            target = Bukkit.getOfflinePlayer(args[0]);
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(Component.text("Console must name a player: /stats <player>.", NamedTextColor.RED));
            return true;
        }

        PlayerStats s = stats.getStats(target);
        sender.sendMessage(Component.text("--- " + s.name() + "'s stats ---", NamedTextColor.GOLD));
        sender.sendMessage(line("Kills", s.kills(), NamedTextColor.GREEN));
        sender.sendMessage(line("Deaths", s.deaths(), NamedTextColor.RED));
        sender.sendMessage(line("Knife kills", s.knifeKills(), NamedTextColor.YELLOW));
        sender.sendMessage(line("Objective points", s.objectivePoints(), NamedTextColor.AQUA));
        sender.sendMessage(line("Rounds played", s.roundsPlayed(), NamedTextColor.GRAY));
        sender.sendMessage(line("Rounds won", s.roundsWon(), NamedTextColor.GRAY));
        if (!s.winsByMode().isEmpty()) {
            String byMode = s.winsByMode().entrySet().stream()
                    .map(e -> e.getKey() + ": " + e.getValue())
                    .collect(Collectors.joining(", "));
            sender.sendMessage(Component.text("Wins by mode: " + byMode, NamedTextColor.GRAY));
        }
        return true;
    }

    private boolean runLeaderboard(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(Component.text("/leaderboard <" + String.join("|", stats.getLeaderboardCategories())
                    + ">", NamedTextColor.YELLOW));
            return true;
        }

        var result = stats.getLeaderboard(args[0], 10);
        if (result == null) {
            sender.sendMessage(Component.text("Unknown category '" + args[0] + "'. Options: "
                    + String.join(", ", stats.getLeaderboardCategories()), NamedTextColor.RED));
            return true;
        }

        sender.sendMessage(Component.text("--- Leaderboard: " + args[0] + " ---", NamedTextColor.GOLD));
        if (result.isEmpty()) {
            sender.sendMessage(Component.text("No stats recorded yet.", NamedTextColor.GRAY));
            return true;
        }
        int rank = 1;
        for (var row : result) {
            sender.sendMessage(Component.text(rank + ". " + row.name() + " — " + row.value(), NamedTextColor.WHITE));
            rank++;
        }
        return true;
    }

    private Component line(String label, int value, NamedTextColor color) {
        return Component.text(label + ": ", NamedTextColor.GRAY).append(Component.text(value, color));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, String[] args) {
        if (args.length != 1) return List.of();
        String current = args[0];

        List<String> options = switch (alias.toLowerCase()) {
            case "stats" -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            case "leaderboard" -> stats.getLeaderboardCategories().stream().toList();
            default -> List.of();
        };
        return options.stream()
                .filter(option -> StringUtil.startsWithIgnoreCase(option, current))
                .collect(Collectors.toList());
    }
}
