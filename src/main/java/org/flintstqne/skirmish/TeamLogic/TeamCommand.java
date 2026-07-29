package org.flintstqne.skirmish.TeamLogic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.flintstqne.skirmish.RoundLogic.RoundService;
import org.flintstqne.skirmish.Utils.Branding;
import org.jetbrains.annotations.NotNull;

/**
 * /team — opens the select GUI whether or not the player already has a team (§7.2).
 * Blocked in teamless gamemodes (FFA, Gun Game — §7.2/§9.4/§9.5), same pattern as
 * {@code /loadout}'s block in gamemodes without a loadout shop.
 */
public final class TeamCommand implements CommandExecutor {

    private final TeamSelectGui gui;
    private final RoundService rounds;

    public TeamCommand(TeamSelectGui gui, RoundService rounds) {
        this.gui = gui;
        this.rounds = rounds;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            Branding.error(sender, "Players only.");
            return true;
        }
        if (!rounds.getGamemode().usesTeams()) {
            player.sendActionBar(Component.text("This gamemode doesn't use teams.", NamedTextColor.RED));
            return true;
        }
        gui.open(player);
        return true;
    }
}
