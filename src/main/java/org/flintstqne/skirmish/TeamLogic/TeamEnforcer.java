package org.flintstqne.skirmish.TeamLogic;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

/**
 * Forces every player onto a team before they can play — the design doc assumes a team is
 * chosen (§7.2) but never specifies enforcement. Handles both first join and reconnecting
 * to a round that already has other players on teams.
 *
 * ponytail: always forces selection — every currently-implemented gamemode (TDM) uses
 * teams. Gate on a gamemode check once FFA/Gun Game exist and skip this entirely for them.
 */
public final class TeamEnforcer implements Listener {

    private final Plugin plugin;
    private final TeamService teams;
    private final TeamSelectGui gui;

    public TeamEnforcer(Plugin plugin, TeamService teams, TeamSelectGui gui) {
        this.plugin = plugin;
        this.teams = teams;
        this.gui = gui;
    }

    /** Teleports a player who already has a team to its spawn; otherwise prompts a pick. */
    public void promptOrRestore(Player player) {
        if (teams.getTeam(player) != null) {
            player.teleport(teams.getSpawn(player));
            return;
        }
        gui.open(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // A tick later — opening a GUI or teleporting during the login sequence itself
        // can get lost.
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) promptOrRestore(player);
        }, 1L);
    }
}
