package org.flintstqne.skirmish.TeamLogic;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.flintstqne.skirmish.RoundLogic.RoundService;

/**
 * Forces every player onto a team before they can play — the design doc assumes a team is
 * chosen (§7.2) but never specifies enforcement. Handles both first join and reconnecting
 * to a round that already has other players on teams.
 *
 * Gamemodes that don't use teams (FFA, Gun Game — {@link RoundService#getGamemode()}'s
 * {@code usesTeams()}) skip all of this and go straight into the arena via
 * {@link RoundService#preparePlayer}, since there's no team spawn to restore or GUI to force.
 */
public final class TeamEnforcer implements Listener {

    private final Plugin plugin;
    private final TeamService teams;
    private final TeamSelectGui gui;
    private RoundService rounds;

    public TeamEnforcer(Plugin plugin, TeamService teams, TeamSelectGui gui) {
        this.plugin = plugin;
        this.teams = teams;
        this.gui = gui;
    }

    /** Set post-construction — RoundService takes a TeamEnforcer, so this breaks the cycle. */
    public void setRoundService(RoundService rounds) {
        this.rounds = rounds;
    }

    /**
     * Teleports a player who already has a team to its spawn; prompts a pick if they don't;
     * or, in a teamless gamemode, just drops them straight into the arena.
     */
    public void promptOrRestore(Player player) {
        if (rounds != null && !rounds.getGamemode().usesTeams()) {
            rounds.preparePlayer(player);
            return;
        }
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
