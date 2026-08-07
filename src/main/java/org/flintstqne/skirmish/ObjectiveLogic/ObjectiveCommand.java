package org.flintstqne.skirmish.ObjectiveLogic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.flintstqne.skirmish.RoundLogic.RoundService;
import org.flintstqne.skirmish.Utils.Branding;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** /obj - shows the coordinates of whatever objective is live right now (the KOTH hill, or
 * every active Domination capture point). Gamemodes without an objective (TDM/FFA/Gun Game)
 * just get told there isn't one. */
public final class ObjectiveCommand implements CommandExecutor {

    private final RoundService rounds;
    private final HillObjective hill;
    private final DominationObjective domination;

    public ObjectiveCommand(RoundService rounds, HillObjective hill, DominationObjective domination) {
        this.rounds = rounds;
        this.hill = hill;
        this.domination = domination;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            Branding.error(sender, "Players only.");
            return true;
        }
        if (!rounds.isActive()) {
            Branding.warning(player, "No round is active right now.");
            return true;
        }

        switch (rounds.getGamemode()) {
            case KOTH -> {
                Location loc = hill.getHill();
                if (loc == null) {
                    Branding.warning(player, "The hill hasn't spawned yet.");
                } else {
                    Branding.info(player, "Hill: " + coords(loc));
                }
            }
            case DOMINATION -> {
                List<CapturePoint> points = domination.getPoints();
                if (points.isEmpty()) {
                    Branding.warning(player, "No capture points are configured for this round.");
                } else {
                    player.sendMessage(heading("Capture points"));
                    for (CapturePoint point : points) {
                        player.sendMessage(Branding.message(
                                point.getName() + ": " + coords(point.getLocation()), Branding.HIGHLIGHT));
                    }
                }
            }
            default -> Branding.warning(player, rounds.getGamemode().name() + " has no objective.");
        }
        return true;
    }

    private Component heading(String title) {
        return Branding.prefix(Component.text(title, Branding.BRAND, TextDecoration.BOLD));
    }

    private String coords(Location loc) {
        return loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
    }
}
