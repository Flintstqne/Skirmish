package org.flintstqne.skirmish.TeamLogic;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.flintstqne.skirmish.RoundLogic.RoundService;

import java.util.Set;

/**
 * Forces every player onto a team before they can play - the design doc assumes a team is
 * chosen (§7.2) but never specifies enforcement. Handles both first join and reconnecting
 * to a round that already has other players on teams.
 *
 * Gamemodes that don't use teams (FFA, Gun Game - {@link RoundService#getGamemode()}'s
 * {@code usesTeams()}) skip all of this and go straight into the arena via
 * {@link RoundService#preparePlayer}, since there's no team spawn to restore or GUI to force.
 */
public final class TeamEnforcer implements Listener {

    private final Plugin plugin;
    private final TeamService teams;
    private final TeamSelectGui gui;
    private final TeamColorService teamColors;
    private RoundService rounds;
    private Set<String> exemptNames = Set.of();

    public TeamEnforcer(Plugin plugin, TeamService teams, TeamSelectGui gui, TeamColorService teamColors) {
        this.plugin = plugin;
        this.teams = teams;
        this.gui = gui;
        this.teamColors = teamColors;
    }

    /** Set post-construction - RoundService takes a TeamEnforcer, so this breaks the cycle. */
    public void setRoundService(RoundService rounds) {
        this.rounds = rounds;
    }

    /**
     * Player names that never get the forced team pick - for accounts that connect but never
     * actually play (e.g. the SkirmishAI bot controller, {@code bots.controller-name}), so they
     * don't get stuck reopening {@link TeamSelectGui} forever with nothing to click it closed.
     * Deliberately NOT given a real team assignment either - that would skew
     * {@link TeamService#counts()}-based team balancing for anything that spawns real bots.
     */
    public void setExemptNames(Set<String> exemptNames) {
        this.exemptNames = exemptNames;
    }

    /**
     * Teleports a player who already has a team to its spawn; prompts a pick if they don't;
     * or, in a teamless gamemode, just drops them straight into the arena.
     */
    public void promptOrRestore(Player player) {
        if (exemptNames.contains(player.getName())) return;
        if (rounds != null && !rounds.getGamemode().usesTeams()) {
            rounds.preparePlayer(player);
            return;
        }
        Team team = teams.getTeam(player);
        if (team != null) {
            teamColors.apply(player, team);
            player.teleport(teams.getSpawn(player));
            return;
        }
        gui.open(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // A tick later - opening a GUI or teleporting during the login sequence itself
        // can get lost.
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            // The server emptied out between rounds and onSequenceFinished skipped starting
            // a fresh one rather than clone a world for nobody - resume it now that someone's
            // back. beginRound() places every online player once the new world is ready, so
            // this join doesn't also need promptOrRestore.
            if (rounds != null && rounds.isIdle()) {
                rounds.startRound(rounds.getGamemode());
                return;
            }
            promptOrRestore(player);
        }, 1L);
    }
}
