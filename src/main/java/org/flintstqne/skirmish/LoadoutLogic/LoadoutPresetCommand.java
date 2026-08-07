package org.flintstqne.skirmish.LoadoutLogic;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.flintstqne.skirmish.Utils.Branding;
import org.jetbrains.annotations.NotNull;

/** /loadouts - GUI-only entry point, just opens the presets GUI directly (design doc §10). */
public final class LoadoutPresetCommand implements CommandExecutor {

    private final LoadoutPresetGui gui;

    public LoadoutPresetCommand(LoadoutPresetGui gui) {
        this.gui = gui;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            Branding.error(sender, "Players only.");
            return true;
        }
        gui.open(player);
        return true;
    }
}
