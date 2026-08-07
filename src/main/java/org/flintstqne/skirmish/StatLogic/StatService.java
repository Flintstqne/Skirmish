package org.flintstqne.skirmish.StatLogic;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.flintstqne.skirmish.DatabaseManager;
import org.flintstqne.skirmish.RoundLogic.GamemodeType;
import org.flintstqne.skirmish.TeamLogic.Team;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Lifetime stats and round history (design doc §7.10) - the persistent, cross-round
 * counterpart to the ephemeral per-round scores {@code RoundService} already tracks. This is
 * the only class that touches {@code player_stats}/{@code round_history} (no separate
 * `StatDb`, per design doc §5); everything goes through the shared {@link DatabaseManager}.
 *
 * Writes happen synchronously on the main thread, same as every other DatabaseManager
 * caller in this plugin (loadout presets included) - the design doc calls for "async-batched"
 * writes, but kills/deaths/round-ends are low-frequency events (nothing like Domination's
 * per-second tick), so synchronous local SQLite writes here cost nothing worth avoiding, and
 * skipping the batching avoids the only genuinely new kind of complexity this would add:
 * cross-thread access to a connection nothing else in the plugin touches off the main thread.
 */
public final class StatService {

    /** Leaderboard category → column, validated here since JDBC can't parameterize a column name. */
    private static final Map<String, String> LEADERBOARD_COLUMNS = Map.of(
            "kills", "kills",
            "deaths", "deaths",
            "knife_kills", "knife_kills",
            "objective_points", "objective_points",
            "rounds_played", "rounds_played",
            "rounds_won", "rounds_won");

    private final DatabaseManager db;
    private final Logger logger;

    public StatService(DatabaseManager db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    public void recordKill(Player killer) {
        run("record kill for " + killer.getName(), () -> db.incrementKills(killer.getUniqueId(), killer.getName(), 1));
    }

    public void recordDeath(Player victim) {
        run("record death for " + victim.getName(), () -> db.incrementDeaths(victim.getUniqueId(), victim.getName(), 1));
    }

    public void recordKnifeKill(Player killer) {
        run("record knife kill for " + killer.getName(),
                () -> db.incrementKnifeKills(killer.getUniqueId(), killer.getName(), 1));
    }

    public void recordObjectivePoints(Player player, int amount) {
        if (amount <= 0) return;
        run("record objective points for " + player.getName(),
                () -> db.incrementObjectivePoints(player.getUniqueId(), player.getName(), amount));
    }

    public void recordRoundPlayed(Player player) {
        run("record round played for " + player.getName(),
                () -> db.incrementRoundsPlayed(player.getUniqueId(), player.getName()));
    }

    public void recordRoundWon(Player player, GamemodeType mode) {
        run("record round won for " + player.getName(),
                () -> db.incrementRoundsWon(player.getUniqueId(), player.getName(), mode.name()));
    }

    /**
     * @param teamWinner   the winning team, or null in an individual/teamless mode
     * @param playerWinner the winning player, or null in a team mode
     */
    public void recordRoundHistory(GamemodeType mode, Team teamWinner, Player playerWinner, long startedAtMillis,
                                   int finalScoreA, int finalScoreB) {
        String winner = teamWinner != null ? teamWinner.name()
                : playerWinner != null ? playerWinner.getUniqueId().toString() : null;
        run("record round history", () -> db.recordRoundHistory(mode.name(), winner, startedAtMillis,
                System.currentTimeMillis(), finalScoreA, finalScoreB));
    }

    public PlayerStats getStats(OfflinePlayer player) {
        try {
            return db.getPlayerStats(player.getUniqueId(), player.getName());
        } catch (SQLException e) {
            logFailure("read stats for " + player.getName(), e);
            return PlayerStats.empty(player.getName());
        }
    }

    /** @return null if {@code category} isn't a recognized leaderboard column. */
    public List<DatabaseManager.LeaderboardRow> getLeaderboard(String category, int limit) {
        String column = LEADERBOARD_COLUMNS.get(category.toLowerCase(Locale.ROOT));
        if (column == null) return null;
        try {
            return db.getLeaderboard(column, limit);
        } catch (SQLException e) {
            logFailure("read leaderboard for " + category, e);
            return List.of();
        }
    }

    public Set<String> getLeaderboardCategories() {
        return LEADERBOARD_COLUMNS.keySet();
    }

    /** Recent rounds server-wide, most recent first - {@code round_history} has no per-player
     * link (design doc §5.1), so this isn't scoped to any one player. */
    public List<DatabaseManager.RoundHistoryRow> getRecentRounds(int limit) {
        try {
            return db.getRecentRounds(limit);
        } catch (SQLException e) {
            logFailure("read recent rounds", e);
            return List.of();
        }
    }

    public void resetStats(OfflinePlayer player) {
        run("reset stats for " + player.getName(), () -> db.resetPlayerStats(player.getUniqueId()));
    }

    public void resetAllStats() {
        run("reset all stats", db::resetAllStats);
    }

    private interface DbCall {
        void run() throws SQLException;
    }

    private void run(String action, DbCall call) {
        try {
            call.run();
        } catch (SQLException e) {
            logFailure(action, e);
        }
    }

    private void logFailure(String action, SQLException e) {
        logger.warning("Stat DB failure (" + action + "): " + e.getMessage());
    }
}
