package org.flintstqne.skirmish.TeamLogic;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.scoreboard.Scoreboard;

import java.util.List;

/**
 * Colors a player's name by team wherever the client renders it: above their head and in the
 * tab list (both governed by a vanilla scoreboard {@link org.bukkit.scoreboard.Team}), and in
 * chat (which vanilla team color does *not* reach - Paper's default chat renderer uses the
 * plain player name, so that needs its own {@link AsyncChatEvent} renderer here).
 *
 * Team color is evaluated from each *viewer's* own scoreboard, not the entity being viewed -
 * and {@link org.flintstqne.skirmish.Utils.ScoreboardService} gives every player their own
 * personal {@link Scoreboard} instance for the sidebar (design doc Tier 2 perf pass), not the
 * shared main one. So a red/blue team registered only on the main scoreboard is invisible to
 * every player, since none of them are actually looking at it. Every method here therefore
 * operates across every currently-online player's own scoreboard, and
 * {@link #syncNewBoard(Scoreboard)} backfills a freshly-assigned board with the round's current
 * team rosters - call it right after {@code player.setScoreboard(board)}.
 */
public final class TeamColorService implements Listener {

    private static final String RED_TEAM = "skirmish_red";
    private static final String BLUE_TEAM = "skirmish_blue";

    private final TeamService teams;

    public TeamColorService(TeamService teams) {
        this.teams = teams;
    }

    private org.bukkit.scoreboard.Team teamOn(Scoreboard board, Team team) {
        String name = team == Team.RED ? RED_TEAM : BLUE_TEAM;
        org.bukkit.scoreboard.Team scoreboardTeam = board.getTeam(name);
        if (scoreboardTeam == null) {
            scoreboardTeam = board.registerNewTeam(name);
            scoreboardTeam.setColor(team == Team.RED ? ChatColor.RED : ChatColor.BLUE);
        }
        return scoreboardTeam;
    }

    /** Puts the player on the vanilla team matching their Skirmish team, on every viewer's
     * board - colors nametag + tab for everyone currently online. */
    public void apply(Player player, Team team) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            applyOn(viewer.getScoreboard(), player, team);
        }
    }

    private void applyOn(Scoreboard board, Player player, Team team) {
        teamOn(board, team.opposite()).removeEntry(player.getName());
        teamOn(board, team).addEntry(player.getName());
    }

    /** Backfills a just-assigned personal scoreboard with the round's current team rosters -
     * without this, a player who joins mid-round only sees everyone else's names uncolored. */
    public void syncNewBoard(Scoreboard board) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Team team = teams.getTeam(player);
            if (team != null) applyOn(board, player, team);
        }
    }

    /** Round reset / teamless gamemodes - no lingering color from a prior round's team. */
    public void clearAll() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Scoreboard board = viewer.getScoreboard();
            clearTeam(teamOn(board, Team.RED));
            clearTeam(teamOn(board, Team.BLUE));
        }
    }

    private void clearTeam(org.bukkit.scoreboard.Team team) {
        for (String entry : List.copyOf(team.getEntries())) team.removeEntry(entry);
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Team team = teams.getTeam(event.getPlayer());
        if (team == null) return;
        NamedTextColor color = team.getColor();
        event.renderer((source, sourceDisplayName, message, viewer) -> Component.text()
                .append(Component.text(source.getName(), color))
                .append(Component.text(": ", NamedTextColor.WHITE))
                .append(message)
                .build());
    }
}
