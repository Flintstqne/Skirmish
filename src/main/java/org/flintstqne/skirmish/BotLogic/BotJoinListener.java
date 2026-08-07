package org.flintstqne.skirmish.BotLogic;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.flintstqne.skirmish.RoundLogic.RoundService;
import org.flintstqne.skirmish.TeamLogic.Team;
import org.flintstqne.skirmish.TeamLogic.TeamColorService;
import org.flintstqne.skirmish.TeamLogic.TeamService;

/**
 * Places and gears a bot the moment its real client connection joins - no chat-command dance,
 * no kit tables. A bot is a genuine {@link Player} from Bukkit's perspective, so the exact same
 * {@link RoundService#preparePlayer} call that gears a human on round start gears a bot
 * correctly for whatever mode is live (loadout preset, Gun Game ladder, etc.).
 *
 * Runs at {@link EventPriority#LOW}, synchronously, deliberately ahead of
 * {@link org.flintstqne.skirmish.TeamLogic.TeamEnforcer}'s own join handling (which reacts a
 * tick later) - by the time that runs, the bot already has a team, so it never sees a
 * teamless player and never opens the team-select GUI a bot could never click through.
 */
public final class BotJoinListener implements Listener {

    private final JavaPlugin plugin;
    private final BotService bots;
    private final TeamService teams;
    private final RoundService rounds;
    private final TeamColorService teamColors;

    public BotJoinListener(JavaPlugin plugin, BotService bots, TeamService teams, RoundService rounds,
                            TeamColorService teamColors) {
        this.plugin = plugin;
        this.bots = bots;
        this.teams = teams;
        this.rounds = rounds;
        this.teamColors = teamColors;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!bots.isManagedBot(player.getName())) return;

        // WeaponMechanics gates firing behind "weaponmechanics.use.*", which it registers with
        // PermissionDefault.OP - a non-op bot can equip a gun and click all it wants, but WM's
        // ShootHandler silently no-ops every shot. Grant just this node rather than opping the
        // bot outright, which would also hand it command access it has no business holding.
        player.addAttachment(plugin, "weaponmechanics.use.*", true);

        Team team = bots.getAssignedTeam(player.getName());
        if (team != null) {
            teams.join(player, team);
            teamColors.apply(player, team);
        }
        rounds.preparePlayer(player);
        // Earliest possible point to sync - lets any already-live teammate learn about this
        // bot right away, rather than waiting for this bot's own /botack round-trip or the
        // next periodic BotObjectiveTask tick.
        bots.syncAllies();
    }
}
