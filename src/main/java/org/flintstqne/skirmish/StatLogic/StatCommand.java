package org.flintstqne.skirmish.StatLogic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.flintstqne.skirmish.Utils.Branding;
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
                Branding.warning(sender, "Usage: /history [count]");
                return true;
            }
        }

        var rounds = stats.getRecentRounds(limit);
        sender.sendMessage(heading("Recent rounds"));
        if (rounds.isEmpty()) {
            Branding.send(sender, "No rounds recorded yet.");
            return true;
        }
        for (var round : rounds) {
            String when = TIMESTAMP.format(Instant.ofEpochMilli(round.startedAt()));
            String winner = round.winner() == null ? "Draw" : round.winner();
            sender.sendMessage(Component.text(when + "  " + round.gamemode() + "  "
                    + round.finalScoreA() + "-" + round.finalScoreB() + "  Winner: " + winner, Branding.HIGHLIGHT));
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
            Branding.error(sender, "Console must name a player: /stats <player>.");
            return true;
        }

        PlayerStats s = stats.getStats(target);
        sender.sendMessage(heading(s.name() + "'s stats"));
        sender.sendMessage(line("Kills", s.kills(), Branding.SUCCESS));
        sender.sendMessage(line("Deaths", s.deaths(), Branding.ERROR));
        sender.sendMessage(line("Knife kills", s.knifeKills(), Branding.WARNING));
        sender.sendMessage(line("Objective points", s.objectivePoints(), Branding.INFO));
        sender.sendMessage(line("Rounds played", s.roundsPlayed(), Branding.BODY));
        sender.sendMessage(line("Rounds won", s.roundsWon(), Branding.BODY));
        if (!s.winsByMode().isEmpty()) {
            String byMode = s.winsByMode().entrySet().stream()
                    .map(e -> e.getKey() + ": " + e.getValue())
                    .collect(Collectors.joining(", "));
            sender.sendMessage(Component.text("Wins by mode: " + byMode, Branding.BODY));
        }
        return true;
    }

    private boolean runLeaderboard(CommandSender sender, String[] args) {
        if (args.length < 1) {
            Branding.warning(sender, "/leaderboard <" + String.join("|", stats.getLeaderboardCategories()) + ">");
            return true;
        }

        var result = stats.getLeaderboard(args[0], 10);
        if (result == null) {
            Branding.error(sender, "Unknown category '" + args[0] + "'. Options: "
                    + String.join(", ", stats.getLeaderboardCategories()));
            return true;
        }

        sender.sendMessage(heading("Leaderboard: " + args[0]));
        if (result.isEmpty()) {
            Branding.send(sender, "No stats recorded yet.");
            return true;
        }
        int rank = 1;
        for (var row : result) {
            sender.sendMessage(Component.text(rank + ". " + row.name() + " — " + row.value(), Branding.HIGHLIGHT));
            rank++;
        }
        return true;
    }

    /** Branded header for a multi-line report — the prefix goes once per block, not on every
     * data line beneath it. */
    private Component heading(String title) {
        return Branding.prefix(Component.text(title, Branding.BRAND, TextDecoration.BOLD));
    }

    private Component line(String label, int value, NamedTextColor color) {
        return Component.text(label + ": ", Branding.BODY).append(Component.text(value, color));
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
