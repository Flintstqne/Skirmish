package org.flintstqne.skirmish.Utils;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
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

/** Live sidebar scoreboard (§11 item 2). Each player keeps one {@link Objective} for the whole
 * session — an update only touches the score lines, and skips entirely when nothing changed,
 * instead of allocating a fresh {@link Scoreboard} and reassigning it every tick. */
public final class ScoreboardService implements Listener {

    private final Skirmish plugin;
    private final ConfigManager config;
    private final RoundService rounds;
    private final Map<UUID, Objective> objectives = new HashMap<>();
    private final Map<UUID, List<String>> lastLines = new HashMap<>();
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
            Objective obj = board.registerNewObjective("skirmish", "dummy", Component.text("Skirmish"));
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            player.setScoreboard(board);
            return obj;
        });

        List<String> lines = buildLines();
        List<String> previous = lastLines.getOrDefault(player.getUniqueId(), List.of());
        if (lines.equals(previous)) return;

        Scoreboard board = objective.getScoreboard();
        if (board != null) {
            for (String old : previous) {
                if (!lines.contains(old)) board.resetScores(old);
            }
        }
        int score = 15;
        for (String line : lines) {
            objective.getScore(line).setScore(score--);
        }
        lastLines.put(player.getUniqueId(), lines);
    }

    private List<String> buildLines() {
        GamemodeType mode = rounds.getGamemode();
        String timer = formatTime(rounds.getSecondsRemaining());

        return switch (rounds.getState()) {
            case WAITING -> List.of(mode.name(), "Waiting for round...");
            case ACTIVE, ENDING -> mode.usesTeams()
                    ? List.of(mode.name(), "Time: " + timer, " ",
                        "Red: " + rounds.getScore(Team.RED), "Blue: " + rounds.getScore(Team.BLUE))
                    : List.of(mode.name(), "Time: " + timer);
        };
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
