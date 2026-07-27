package org.flintstqne.skirmish.StatLogic;

import java.util.Map;

/** Lifetime stats, mirrors the `player_stats` row (design doc §5.1, §7.10). */
public record PlayerStats(String name, int kills, int deaths, int knifeKills, int objectivePoints,
                          int roundsPlayed, int roundsWon, Map<String, Integer> winsByMode) {

    public static PlayerStats empty(String name) {
        return new PlayerStats(name, 0, 0, 0, 0, 0, 0, Map.of());
    }
}
