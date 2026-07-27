package org.flintstqne.skirmish.RoundLogic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/** /round — manual override of the vote-driven flow, for testing (design doc §10). */
public final class RoundCommand implements CommandExecutor {

    private final RoundService rounds;

    public RoundCommand(RoundService rounds) {
        this.rounds = rounds;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("/round start <mode> | /round end | /round status",
                    NamedTextColor.YELLOW));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Playable modes: " + RoundService.PLAYABLE,
                            NamedTextColor.YELLOW));
                    return true;
                }
                GamemodeType mode;
                try {
                    mode = GamemodeType.valueOf(args[1].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    sender.sendMessage(Component.text("Unknown gamemode '" + args[1] + "'.", NamedTextColor.RED));
                    return true;
                }
                if (!RoundService.PLAYABLE.contains(mode)) {
                    sender.sendMessage(Component.text(mode + " isn't implemented yet. Playable: "
                            + RoundService.PLAYABLE, NamedTextColor.RED));
                    return true;
                }
                rounds.startRound(mode);
                sender.sendMessage(Component.text("Started " + mode + ".", NamedTextColor.GREEN));
            }
            case "end" -> {
                if (!rounds.isActive()) {
                    sender.sendMessage(Component.text("No round is running.", NamedTextColor.RED));
                    return true;
                }
                rounds.endRound(null);
                sender.sendMessage(Component.text("Ending round.", NamedTextColor.GREEN));
            }
            case "status" -> sender.sendMessage(Component.text(
                    "State: " + rounds.getState() + " | Mode: " + rounds.getGamemode()
                            + " | Time left: " + rounds.getSecondsRemaining() + "s",
                    NamedTextColor.AQUA));
            default -> sender.sendMessage(Component.text("/round start <mode> | /round end | /round status",
                    NamedTextColor.YELLOW));
        }
        return true;
    }
}
