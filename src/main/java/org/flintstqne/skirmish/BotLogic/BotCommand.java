package org.flintstqne.skirmish.BotLogic;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.flintstqne.skirmish.Utils.Branding;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** /bots spawn|despawn|list - admin control over the SkirmishAI bot army. */
public final class BotCommand implements CommandExecutor, TabCompleter {

    private final BotService bots;

    public BotCommand(BotService bots) {
        this.bots = bots;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (args.length == 0) {
            Branding.warning(sender, "/bots spawn <count> | /bots despawn <name|all> | /bots list");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "spawn" -> {
                if (args.length < 2) {
                    Branding.warning(sender, "/bots spawn <count>");
                    return true;
                }
                int count;
                try {
                    count = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    Branding.error(sender, "\"" + args[1] + "\" isn't a number.");
                    return true;
                }
                if (count < 1) {
                    Branding.error(sender, "Count must be at least 1.");
                    return true;
                }
                bots.spawnBots(sender, count);
            }
            case "despawn" -> {
                if (args.length < 2) {
                    Branding.warning(sender, "/bots despawn <name|all>");
                    return true;
                }
                if (args[1].equalsIgnoreCase("all")) {
                    int n = bots.despawnAll(sender);
                    Branding.success(sender, "Despawned " + n + " bot(s).");
                } else if (!bots.despawnBot(sender, args[1])) {
                    Branding.error(sender, "No tracked bot named \"" + args[1] + "\".");
                }
            }
            case "list" -> {
                if (bots.getAll().isEmpty()) {
                    Branding.info(sender, "No bots active.");
                    return true;
                }
                for (BotService.BotRecord bot : bots.getAll()) {
                    String team = bot.team() == null ? "no team" : bot.team().getDisplayName();
                    Branding.info(sender, bot.name() + " - " + team + " - " + bot.status());
                }
            }
            default -> Branding.warning(sender, "/bots spawn <count> | /bots despawn <name|all> | /bots list");
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, String[] args) {
        List<String> options = switch (args.length) {
            case 1 -> List.of("spawn", "despawn", "list");
            case 2 -> {
                if (!args[0].equalsIgnoreCase("despawn")) yield List.<String>of();
                List<String> names = new java.util.ArrayList<>(bots.getAll().stream().map(BotService.BotRecord::name).toList());
                names.add("all");
                yield names;
            }
            default -> List.<String>of();
        };
        String current = args.length == 0 ? "" : args[args.length - 1];
        return options.stream()
                .filter(option -> StringUtil.startsWithIgnoreCase(option, current))
                .collect(Collectors.toList());
    }
}
