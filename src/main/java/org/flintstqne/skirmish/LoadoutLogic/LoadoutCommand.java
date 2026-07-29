package org.flintstqne.skirmish.LoadoutLogic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.flintstqne.skirmish.Utils.Branding;
import org.jetbrains.annotations.NotNull;

/** /loadout — blocked outright in gamemodes with loadouts disabled (design doc §7.5.3). */
public final class LoadoutCommand implements CommandExecutor {

    private final LoadoutService loadouts;
    private final LoadoutBuilderGui gui;

    public LoadoutCommand(LoadoutService loadouts, LoadoutBuilderGui gui) {
        this.loadouts = loadouts;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            Branding.error(sender, "Players only.");
            return true;
        }
        if (!loadouts.isLoadoutsEnabled()) {
            player.sendActionBar(Component.text("This gamemode doesn't use loadouts.", NamedTextColor.RED));
            return true;
        }
        gui.open(player);
        return true;
    }
}
