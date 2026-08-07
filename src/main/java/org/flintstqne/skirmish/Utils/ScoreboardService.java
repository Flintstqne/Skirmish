package org.flintstqne.skirmish.Utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
import org.flintstqne.skirmish.LoadoutLogic.LoadoutService;
import org.flintstqne.skirmish.ObjectiveLogic.HillObjective;
import org.flintstqne.skirmish.RoundLogic.GamemodeType;
import org.flintstqne.skirmish.RoundLogic.RoundService;
import org.flintstqne.skirmish.RoundLogic.RoundState;
import org.flintstqne.skirmish.Skirmish;
import org.flintstqne.skirmish.TeamLogic.Team;
import org.flintstqne.skirmish.TeamLogic.TeamColorService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Live sidebar scoreboard (§11 item 2), styled to the shared {@link Branding} palette. Each
 * player keeps one {@link Objective} for the whole session - an update only touches the score
 * lines, and skips entirely when nothing changed, instead of allocating a fresh
 * {@link Scoreboard} and reassigning it every tick.
 *
 * <p>The KOTH compass line is the same technique as Trenched's {@code ScoreboardUtil} (a
 * relative-angle-to-8-direction text strip on the sidebar, not a vanilla held-compass item)
 * ported onto this class's stable-entry-key design: Trenched keys each scoreboard entry by its
 * own display text, so an unchanged-looking line needs an invisible per-frame color marker to
 * force a re-render - here each line is a fixed {@code skirmish_line_N} entry with its display
 * text set via {@link Score#customName}, so changing the text alone already refreshes it and no
 * such trick is needed. */
public final class ScoreboardService implements Listener {

    private static final Component TITLE = Component.text("SKIRMISH", Branding.BRAND, TextDecoration.BOLD);
    private static final Component DIVIDER = Component.text("▪▪▪▪▪▪▪▪▪▪▪▪", Branding.MUTED);
    private static final String ENTRY_PREFIX = "skirmish_line_";

    private final Skirmish plugin;
    private final ConfigManager config;
    private final RoundService rounds;
    private final TeamColorService teamColors;
    private final LoadoutService loadouts;
    private final HillObjective hill;
    private final Map<UUID, Objective> objectives = new HashMap<>();
    private final Map<UUID, List<Component>> lastLines = new HashMap<>();
    private BukkitTask task;

    public ScoreboardService(Skirmish plugin, ConfigManager config, RoundService rounds,
                              TeamColorService teamColors, LoadoutService loadouts, HillObjective hill) {
        this.plugin = plugin;
        this.config = config;
        this.rounds = rounds;
        this.teamColors = teamColors;
        this.loadouts = loadouts;
        this.hill = hill;
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
            teamColors.syncNewBoard(board);
            return obj;
        });

        syncExpBar(player);

        List<Component> lines = buildLines(player);
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

    private List<Component> buildLines(Player player) {
        GamemodeType mode = rounds.getGamemode();
        Component modeLine = Component.text(mode.name(), Branding.HIGHLIGHT, TextDecoration.BOLD);

        if (rounds.getState() == RoundState.WAITING) {
            return List.of(modeLine, Component.text("Waiting for round...", Branding.MUTED, TextDecoration.ITALIC));
        }

        List<Component> lines = new ArrayList<>();
        lines.add(modeLine);
        lines.add(timeLine());
        if (mode.usesTeams()) {
            lines.add(DIVIDER);
            lines.add(teamLine("Red", Branding.TEAM_RED, rounds.getScore(Team.RED)));
            lines.add(teamLine("Blue", Branding.TEAM_BLUE, rounds.getScore(Team.BLUE)));
        }
        if (loadouts.isLoadoutsEnabled()) {
            lines.add(meritLine(player));
        }
        Component compass = compassLine(player, mode);
        if (compass != null) {
            lines.add(DIVIDER);
            lines.add(compass);
        }
        return lines;
    }

    private Component timeLine() {
        return Component.text("Time: ", Branding.BODY)
                .append(Component.text(formatTime(rounds.getSecondsRemaining()), Branding.HIGHLIGHT, TextDecoration.BOLD));
    }

    private Component teamLine(String name, net.kyori.adventure.text.format.TextColor color, int score) {
        return Component.text(name + ": ", color)
                .append(Component.text(score, Branding.HIGHLIGHT, TextDecoration.BOLD));
    }

    /** Shows what's actually spendable right now, not the banked round total - buying
     * something should visibly move this number, which the raw balance never does since
     * "spent" is computed live from the player's current inventory (see LoadoutService). */
    private Component meritLine(Player player) {
        return Component.text("Merit: ", Branding.BODY)
                .append(Component.text(loadouts.getRemaining(player), Branding.HIGHLIGHT, TextDecoration.BOLD));
    }

    /** Merit as the vanilla XP bar/level too - level and bar both track what's still
     * spendable, so a purchase visibly drains both. Re-asserted every tick, so anything
     * that nudges vanilla XP (an orb, a plugin) gets overwritten within one update interval. */
    private void syncExpBar(Player player) {
        if (!loadouts.isLoadoutsEnabled()) return;
        int merit = Math.max(0, loadouts.getMerit(player));
        int remaining = Math.max(0, loadouts.getRemaining(player));
        player.setLevel(remaining);
        float fraction = merit == 0 ? 0f : Math.max(0f, Math.min(1f, remaining / (float) merit));
        player.setExp(fraction);
    }

    /** Null outside KOTH, before the hill has spawned, or when the player's in a different
     * world entirely (e.g. still in the hub while a round is live). */
    private Component compassLine(Player player, GamemodeType mode) {
        if (mode != GamemodeType.KOTH) return null;
        Location target = hill.getHill();
        if (target == null || target.getWorld() == null || !target.getWorld().equals(player.getWorld())) {
            return null;
        }

        double relativeAngle = relativeAngle(player.getLocation(), target);
        int distance = (int) player.getLocation().distance(target);
        return Component.text("Hill ", Branding.MUTED)
                .append(compassStrip(relativeAngle))
                .append(Component.text(" " + distance + "m", Branding.HIGHLIGHT));
    }

    /** Angle from the player's facing direction to the target, in (-180, 180] degrees -
     * 0 is dead ahead, positive is to the right. */
    private double relativeAngle(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double angleToTarget = Math.toDegrees(Math.atan2(-dx, dz));
        double relative = angleToTarget - from.getYaw();
        while (relative > 180) relative -= 360;
        while (relative <= -180) relative += 360;
        return relative;
    }

    /** A 5-slot direction strip - "you are here" in the middle, the target's direction
     * highlighted in one of 8 slots around it (or dead ahead/behind in the center itself). */
    private Component compassStrip(double relativeAngle) {
        Component dim = Component.text("·", Branding.MUTED);
        Component you = Component.text("✦", NamedTextColor.YELLOW);
        Component left2 = dim, left1 = dim, center = you, right1 = dim, right2 = dim;

        if (relativeAngle >= -22.5 && relativeAngle < 22.5) {
            center = Component.text("▲", NamedTextColor.GREEN);
        } else if (relativeAngle >= 22.5 && relativeAngle < 67.5) {
            right1 = Component.text("↗", NamedTextColor.YELLOW);
        } else if (relativeAngle >= 67.5 && relativeAngle < 112.5) {
            right2 = Component.text("▶", NamedTextColor.YELLOW);
        } else if (relativeAngle >= 112.5 && relativeAngle < 157.5) {
            right1 = Component.text("↘", NamedTextColor.RED);
        } else if (relativeAngle >= 157.5 || relativeAngle < -157.5) {
            center = Component.text("▼", NamedTextColor.RED);
        } else if (relativeAngle >= -157.5 && relativeAngle < -112.5) {
            left1 = Component.text("↙", NamedTextColor.RED);
        } else if (relativeAngle >= -112.5 && relativeAngle < -67.5) {
            left2 = Component.text("◀", NamedTextColor.YELLOW);
        } else {
            left1 = Component.text("↖", NamedTextColor.YELLOW);
        }

        return Component.text().append(left2).append(Component.space()).append(left1).append(Component.space())
                .append(center).append(Component.space()).append(right1).append(Component.space()).append(right2)
                .build();
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
