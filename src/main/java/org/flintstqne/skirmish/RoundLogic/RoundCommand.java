package org.flintstqne.skirmish.RoundLogic;

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

/** /round - manual override of the vote-driven flow, for testing (design doc §10). */
public final class RoundCommand implements CommandExecutor, TabCompleter {

    private final RoundService rounds;

    public RoundCommand(RoundService rounds) {
        this.rounds = rounds;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (args.length == 0) {
            Branding.warning(sender, "/round start <mode> | /round end | /round status");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                if (args.length < 2) {
                    Branding.warning(sender, "Playable modes: " + RoundService.PLAYABLE);
                    return true;
                }
                GamemodeType mode;
                try {
                    mode = GamemodeType.valueOf(args[1].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    Branding.error(sender, "Unknown gamemode '" + args[1] + "'.");
                    return true;
                }
                if (!RoundService.PLAYABLE.contains(mode)) {
                    Branding.error(sender, mode + " isn't implemented yet. Playable: " + RoundService.PLAYABLE);
                    return true;
                }
                rounds.forceStart(mode);
                Branding.success(sender, "Started " + mode + ".");
            }
            case "end" -> {
                if (!rounds.isActive()) {
                    Branding.error(sender, "No round is running.");
                    return true;
                }
                rounds.endRoundNow();
                Branding.success(sender, "Ending round.");
            }
            case "status" -> Branding.info(sender, "State: " + rounds.getState() + " | Mode: " + rounds.getGamemode()
                    + " | Time left: " + rounds.getSecondsRemaining() + "s");
            default -> Branding.warning(sender, "/round start <mode> | /round end | /round status");
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, String[] args) {
        List<String> options = switch (args.length) {
            case 1 -> List.of("start", "end", "status");
            case 2 -> args[0].equalsIgnoreCase("start")
                    ? RoundService.PLAYABLE.stream().map(Enum::name).toList()
                    : List.<String>of();
            default -> List.<String>of();
        };
        String current = args.length == 0 ? "" : args[args.length - 1];
        return options.stream()
                .filter(option -> StringUtil.startsWithIgnoreCase(option, current))
                .collect(Collectors.toList());
    }
}
