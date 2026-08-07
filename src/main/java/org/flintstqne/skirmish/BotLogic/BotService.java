package org.flintstqne.skirmish.BotLogic;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.flintstqne.skirmish.ConfigManager;
import org.flintstqne.skirmish.RoundLogic.RoundService;
import org.flintstqne.skirmish.TeamLogic.Team;
import org.flintstqne.skirmish.TeamLogic.TeamService;
import org.flintstqne.skirmish.Utils.Branding;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tracks bots spawned into the current round and talks to the external Mineflayer bot
 * controller over the same in-game-whisper protocol TrenchedAI/TRENCHBOT already proved out.
 * All the actual combat/navigation AI lives in that external Node.js process - this class only
 * decides who to spawn, which team they join, and relays the two commands the controller
 * understands (spawn/despawn). Gearing and team placement happen server-side in
 * {@link BotJoinListener} once the bot's real client connection triggers a normal join.
 */
public final class BotService {

    /** A tracked bot's lifecycle - SPAWNING until the controller's /botack confirms it connected. */
    public enum Status { SPAWNING, LIVE }

    public record BotRecord(String name, Team team, String ackCode, Status status) {
        BotRecord withStatus(Status status) {
            return new BotRecord(name, team, ackCode, status);
        }
    }

    private final ConfigManager config;
    private final TeamService teams;
    private final RoundService rounds;
    private final Map<String, BotRecord> bots = new LinkedHashMap<>();
    private int nameCounter = 1;

    public BotService(ConfigManager config, TeamService teams, RoundService rounds) {
        this.config = config;
        this.teams = teams;
        this.rounds = rounds;
    }

    public boolean isManagedBot(String name) {
        return bots.containsKey(name);
    }

    /** Null for teamless gamemodes (FFA/Gun Game) - {@link BotJoinListener} skips team join then. */
    public Team getAssignedTeam(String name) {
        BotRecord record = bots.get(name);
        return record == null ? null : record.team();
    }

    public List<BotRecord> getAll() {
        return new ArrayList<>(bots.values());
    }

    /**
     * Marks a bot live once the controller's /botack confirms its client connected, and syncs
     * allies immediately rather than waiting for the next periodic tick (up to ~7s away,
     * {@link BotObjectiveTask}) - confirmed live as a real bug: bots start combat almost
     * immediately after joining (gear + startpvp within ~2s), well before the first periodic
     * sync could land, so every early target acquisition was a same-team bot standing at the
     * exact same spawn coordinate as its "nearest enemy."
     */
    public void markLive(String ackCode) {
        for (BotRecord record : bots.values()) {
            if (record.ackCode().equals(ackCode)) {
                bots.put(record.name(), record.withStatus(Status.LIVE));
                syncAllies();
                return;
            }
        }
        Bukkit.getLogger().warning("[BotLogic] markLive: no bot found for ack code \"" + ackCode + "\"");
    }

    public void spawnBots(CommandSender sender, int count) {
        String controllerName = config.getBotControllerName();
        if (Bukkit.getPlayerExact(controllerName) == null) {
            Branding.error(sender, "Bot controller \"" + controllerName + "\" is not connected - start SkirmishAI first.");
            return;
        }

        int max = config.getMaxBots();
        if (bots.size() + count > max) {
            Branding.error(sender, "That would exceed the configured max of " + max + " bots ("
                    + bots.size() + " already active).");
            return;
        }

        boolean teamed = rounds.getGamemode().usesTeams();
        Map<Team, Integer> projected = teamed ? new EnumMap<>(teams.counts()) : null;

        List<String> spawned = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String botName = nextBotName();
            Team team = teamed ? pickShortTeam(projected) : null;
            String ackCode = UUID.randomUUID().toString().substring(0, 8);
            bots.put(botName, new BotRecord(botName, team, ackCode, Status.SPAWNING));

            String teamArg = team == null ? "none" : team.name().toLowerCase(Locale.ROOT);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "msg " + controllerName + " spawn " + botName + " " + teamArg + " " + ackCode);
            spawned.add(botName + (team == null ? "" : "[" + team.getDisplayName() + "]"));
        }

        Branding.success(sender, "Requested " + count + " bot(s): " + String.join(", ", spawned));
    }

    public boolean despawnBot(CommandSender sender, String name) {
        BotRecord record = bots.remove(name);
        if (record == null) return false;
        String controllerName = config.getBotControllerName();
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "msg " + controllerName + " despawn " + name);
        Branding.success(sender, "Despawned " + name + ".");
        return true;
    }

    public int despawnAll(CommandSender sender) {
        List<String> names = new ArrayList<>(bots.keySet());
        for (String name : names) despawnBot(sender, name);
        return names.size();
    }

    /**
     * Pushes each live teamed bot the current full roster of its teammates (humans and other
     * bots alike) via {@code setallies} - the bot-side name-prefix ally detection doesn't work
     * for arbitrary names or recognize human teammates at all, so the plugin (ground truth for
     * team membership) is the one that has to tell it. No-op for teamless bots.
     */
    public void syncAllies() {
        for (BotRecord record : bots.values()) {
            if (record.status() != Status.LIVE || record.team() == null) continue;

            String roster = Bukkit.getOnlinePlayers().stream()
                    .filter(p -> !p.getName().equals(record.name()))
                    .filter(p -> teams.getTeam(p) == record.team())
                    .map(Player::getName)
                    .collect(Collectors.joining(","));

            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "msg " + record.name() + " setallies " + roster);
        }
    }

    private String nextBotName() {
        String name;
        do {
            name = "SkirmishBot" + nameCounter++;
        } while (bots.containsKey(name) || Bukkit.getPlayerExact(name) != null);
        return name;
    }

    /** Whichever team has fewer bodies right now, counting bots already queued this batch. */
    private Team pickShortTeam(Map<Team, Integer> projected) {
        Team pick = projected.get(Team.RED) <= projected.get(Team.BLUE) ? Team.RED : Team.BLUE;
        projected.merge(pick, 1, Integer::sum);
        return pick;
    }
}
