package org.flintstqne.skirmish.RoundLogic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.flintstqne.skirmish.CombatLogic.DeathSpectatorService;
import org.flintstqne.skirmish.ConfigManager;
import org.flintstqne.skirmish.Skirmish;
import org.flintstqne.skirmish.TeamLogic.Team;
import org.flintstqne.skirmish.VoteLogic.VoteGui;
import org.flintstqne.skirmish.VoteLogic.VoteService;

import java.time.Duration;

/**
 * The end-of-round flow from design doc §7.9, in order:
 * free-roam spectator → winner announcement → vote GUI + countdown → block revert → next round.
 */
public final class EndRoundSequence {

    private final Skirmish plugin;
    private final ConfigManager config;
    private final RoundService rounds;
    private final DeathSpectatorService spectators;
    private final VoteService votes;
    private final VoteGui voteGui;

    public EndRoundSequence(Skirmish plugin, ConfigManager config, RoundService rounds,
                            DeathSpectatorService spectators, VoteService votes, VoteGui voteGui) {
        this.plugin = plugin;
        this.config = config;
        this.rounds = rounds;
        this.spectators = spectators;
        this.votes = votes;
        this.voteGui = voteGui;
    }

    public void run(GamemodeType mode, Team winner, int redScore, int blueScore) {
        // Step 2: everyone — alive or already dead — goes to free-roam spectator.
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            spectators.stop(player);
            if (config.isFreeRoamSpectator()) spectators.startFreeRoam(player);
        }

        announceWinner(winner, redScore, blueScore);
        votes.reset();

        int announceSeconds = config.getWinnerAnnouncementSeconds();
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> startVote(config.getNextRoundCountdownSeconds()), announceSeconds * 20L);
    }

    private void announceWinner(Team winner, int redScore, int blueScore) {
        Component title = winner == null
                ? Component.text("DRAW", NamedTextColor.YELLOW)
                : Component.text(winner.getDisplayName().toUpperCase() + " WINS!", winner.getColor());
        Component subtitle = Component.text("Final Score: " + redScore + " – " + blueScore, NamedTextColor.WHITE);

        Title card = Title.title(title, subtitle, Title.Times.times(
                Duration.ofMillis(300),
                Duration.ofSeconds(Math.max(1, config.getWinnerAnnouncementSeconds())),
                Duration.ofMillis(500)));
        plugin.getServer().getOnlinePlayers().forEach(player -> player.showTitle(card));
    }

    /** Steps 4-5: vote GUI open for everyone, ticking countdown, votes changeable throughout. */
    private void startVote(int seconds) {
        plugin.getServer().getOnlinePlayers().forEach(player -> voteGui.open(player, seconds));
        countdown(seconds);
    }

    private void countdown(int secondsLeft) {
        if (secondsLeft <= 0) {
            finish();
            return;
        }
        Title tick = Title.title(
                Component.empty(),
                Component.text("Next round in: " + secondsLeft + "s", NamedTextColor.GOLD),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO));
        plugin.getServer().getOnlinePlayers().forEach(player -> player.showTitle(tick));

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> countdown(secondsLeft - 1), 20L);
    }

    /**
     * Steps 6-7: close the vote and start the winning gamemode. There is no revert step —
     * the next round clones a fresh world and this one's copy is deleted behind it (§7.3).
     */
    private void finish() {
        GamemodeType next = votes.getWinner();

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.closeInventory();
            spectators.stop(player);
        }
        votes.reset();
        rounds.onSequenceFinished(next);
    }
}
