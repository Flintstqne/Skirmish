package org.flintstqne.skirmish.BotLogic;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.flintstqne.skirmish.Utils.Branding;
import org.jetbrains.annotations.NotNull;

/**
 * /botack &lt;code&gt; - the SkirmishAI controller runs this (as itself, a real connected
 * player) once a spawned bot's client has finished connecting, closing the loop TrenchedAI's
 * TRENCHBOT proved out. Gated on {@code skirmish.botcontroller} (plugin.yml default: op) -
 * granting it means opping the controller account, same as TrenchedAI's TRENCHBOT.
 */
public final class BotAckCommand implements CommandExecutor {

    private final BotService bots;

    public BotAckCommand(BotService bots) {
        this.bots = bots;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (args.length != 1) {
            Branding.warning(sender, "/botack <code>");
            return true;
        }
        bots.markLive(args[0]);
        return true;
    }
}
