package org.flintstqne.skirmish.Utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.flintstqne.skirmish.ConfigManager;
import org.flintstqne.skirmish.RoundLogic.GamemodeType;
import org.flintstqne.skirmish.RoundLogic.RoundService;
import org.flintstqne.skirmish.Skirmish;
import org.flintstqne.skirmish.TeamLogic.Team;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Live sidebar scoreboard (§11 item 2), styled to the shared {@link Branding} palette. Each
 * player keeps one {@link Objective} for the whole session — an update only touches the score
 * lines, and skips entirely when nothing changed, instead of allocating a fresh
 * {@link Scoreboard} and reassigning it every tick. */
public final class ScoreboardService implements Listener {

    private static final Component TITLE = Component.text("SKIRMISH", Branding.BRAND, TextDecoration.BOLD);
    private static final Component DIVIDER = Component.text("▪▪▪▪▪▪▪▪▪▪▪▪", Branding.MUTED);
    private static final String ENTRY_PREFIX = "skirmish_line_";

    private final Skirmish plugin;
    private final ConfigManager config;
    private final RoundService rounds;
    private final Map<UUID, Objective> objectives = new HashMap<>();
    private final Map<UUID, List<Component>> lastLines = new HashMap<>();
    private BukkitTask task;

    public ScoreboardService(Skirmish plugin, ConfigManager config, RoundService rounds) {
        this.plugin = plugin;
        this.config = config;
        this.rounds = rounds;
    }

    public void start() {
        if (!config.isScoreboardEnabled()) return;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateAll,
                0L, config.getScoreboardUpdateIntervalTicks());
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void updateAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) update(player);
    }

    private void update(Player player) {
        Objective objective = objectives.computeIfAbsent(player.getUniqueId(), id -> {
            Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective obj = board.registerNewObjective("skirmish", "dummy", TITLE);
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            player.setScoreboard(board);
            return obj;
        });

        List<Component> lines = buildLines();
        List<Component> previous = lastLines.getOrDefault(player.getUniqueId(), List.of());
        if (lines.equals(previous)) return;

        Scoreboard board = objective.getScoreboard();
        int score = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            Score line = objective.getScore(ENTRY_PREFIX + i);
            line.customName(lines.get(i));
            line.setScore(score--);
        }
        if (board != null) {
            for (int i = lines.size(); i < previous.size(); i++) {
                board.resetScores(ENTRY_PREFIX + i);
            }
        }
        lastLines.put(player.getUniqueId(), lines);
    }

    private List<Component> buildLines() {
        GamemodeType mode = rounds.getGamemode();
        Component modeLine = Component.text(mode.name(), Branding.HIGHLIGHT, TextDecoration.BOLD);

        return switch (rounds.getState()) {
            case WAITING -> List.of(
                    modeLine,
                    Component.text("Waiting for round...", Branding.MUTED, TextDecoration.ITALIC));
            case ACTIVE, ENDING -> mode.usesTeams()
                    ? List.of(
                        modeLine,
                        timeLine(),
                        DIVIDER,
                        teamLine("Red", Branding.TEAM_RED, rounds.getScore(Team.RED)),
                        teamLine("Blue", Branding.TEAM_BLUE, rounds.getScore(Team.BLUE)))
                    : List.of(modeLine, timeLine());
        };
    }

    private Component timeLine() {
        return Component.text("Time: ", Branding.BODY)
                .append(Component.text(formatTime(rounds.getSecondsRemaining()), Branding.HIGHLIGHT, TextDecoration.BOLD));
    }

    private Component teamLine(String name, net.kyori.adventure.text.format.TextColor color, int score) {
        return Component.text(name + ": ", color)
                .append(Component.text(score, Branding.HIGHLIGHT, TextDecoration.BOLD));
    }

    private String formatTime(int seconds) {
        return "%d:%02d".formatted(seconds / 60, seconds % 60);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        objectives.remove(id);
        lastLines.remove(id);
    }
}
