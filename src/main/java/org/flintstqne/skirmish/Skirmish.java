package org.flintstqne.skirmish;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.flintstqne.skirmish.CombatLogic.CombatListener;
import org.flintstqne.skirmish.CombatLogic.DeathSpectatorService;
import org.flintstqne.skirmish.CombatLogic.SpawnProtectionManager;
import org.flintstqne.skirmish.GunGameLogic.GunGameListener;
import org.flintstqne.skirmish.GunGameLogic.GunGameService;
import org.flintstqne.skirmish.LoadoutLogic.AdminCommand;
import org.flintstqne.skirmish.LoadoutLogic.LoadoutBuilderGui;
import org.flintstqne.skirmish.LoadoutLogic.LoadoutCatalog;
import org.flintstqne.skirmish.LoadoutLogic.LoadoutCommand;
import org.flintstqne.skirmish.LoadoutLogic.LoadoutPresetCommand;
import org.flintstqne.skirmish.LoadoutLogic.LoadoutPresetGui;
import org.flintstqne.skirmish.LoadoutLogic.LoadoutPresetService;
import org.flintstqne.skirmish.LoadoutLogic.LoadoutService;
import org.flintstqne.skirmish.LoadoutLogic.WeaponFactory;
import org.flintstqne.skirmish.MapLogic.ArenaAdminCommand;
import org.flintstqne.skirmish.MapLogic.ArenaConfig;
import org.flintstqne.skirmish.MapLogic.BorderWallRenderer;
import org.flintstqne.skirmish.MapLogic.RandomSpawnSelector;
import org.flintstqne.skirmish.MapLogic.WorldManager;
import org.flintstqne.skirmish.ObjectiveLogic.DominationObjective;
import org.flintstqne.skirmish.ObjectiveLogic.HillObjective;
import org.flintstqne.skirmish.ObjectiveLogic.ObjectiveUIManager;
import org.flintstqne.skirmish.RoundLogic.EndRoundSequence;
import org.flintstqne.skirmish.RoundLogic.RoundCommand;
import org.flintstqne.skirmish.RoundLogic.RoundService;
import org.flintstqne.skirmish.StatLogic.StatCommand;
import org.flintstqne.skirmish.StatLogic.StatGui;
import org.flintstqne.skirmish.StatLogic.StatListener;
import org.flintstqne.skirmish.StatLogic.StatService;
import org.flintstqne.skirmish.Utils.AfkService;
import org.flintstqne.skirmish.Utils.KillstreakService;
import org.flintstqne.skirmish.Utils.ScoreboardService;
import org.flintstqne.skirmish.VoteLogic.VoteGui;
import org.flintstqne.skirmish.VoteLogic.VoteService;
import org.flintstqne.skirmish.TeamLogic.TeamCommand;
import org.flintstqne.skirmish.TeamLogic.TeamEnforcer;
import org.flintstqne.skirmish.TeamLogic.TeamSelectGui;
import org.flintstqne.skirmish.TeamLogic.TeamService;

import java.io.File;
import java.sql.SQLException;

public final class Skirmish extends JavaPlugin {

    private ConfigManager configManager;
    private ArenaConfig arenaConfig;
    private DatabaseManager databaseManager;
    private TeamService teamService;
    private SpawnProtectionManager spawnProtectionManager;
    private DeathSpectatorService deathSpectatorService;
    private LoadoutCatalog loadoutCatalog;
    private LoadoutService loadoutService;
    private LoadoutPresetService loadoutPresetService;
    private WorldManager worldManager;
    private VoteService voteService;
    private RoundService roundService;
    private StatService statService;
    private ScoreboardService scoreboardService;
    private AfkService afkService;

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);

        arenaConfig = new ArenaConfig(this);
        // Load the arena world BEFORE the full arena.yml parse — see ArenaConfig#peekWorldName.
        loadArenaWorld(arenaConfig.peekWorldName());
        arenaConfig.load();

        databaseManager = new DatabaseManager(getDataFolder(), getLogger());
        try {
            databaseManager.open();
        } catch (SQLException e) {
            getLogger().severe("Could not open data.db: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        worldManager = new WorldManager(this, arenaConfig);
        int swept = worldManager.sweepOrphanWorlds();
        if (swept > 0) {
            getLogger().info("Swept " + swept + " round world(s) left over from a crashed run.");
        }

        teamService = new TeamService(configManager, arenaConfig, worldManager);
        spawnProtectionManager = new SpawnProtectionManager(configManager, teamService);

        File catalogFile = new File(getDataFolder(), "loadout-catalog.yml");
        if (!catalogFile.exists()) saveResource("loadout-catalog.yml", false);
        loadoutCatalog = new LoadoutCatalog(getLogger());
        loadoutCatalog.load(catalogFile);
        WeaponFactory weaponFactory = new WeaponFactory(this);
        loadoutService = new LoadoutService(configManager, loadoutCatalog, weaponFactory);
        loadoutPresetService = new LoadoutPresetService(databaseManager, loadoutService, configManager, getLogger());
        GunGameService gunGameService = new GunGameService(configManager, weaponFactory);
        statService = new StatService(databaseManager, getLogger());

        deathSpectatorService = new DeathSpectatorService(this, configManager);

        // LoadoutBuilderGui and LoadoutPresetGui navigate to each other — the setter breaks
        // the constructor cycle (see LoadoutBuilderGui#setPresetsGui).
        LoadoutBuilderGui loadoutBuilderGui = new LoadoutBuilderGui(loadoutService, loadoutPresetService);
        LoadoutPresetGui loadoutPresetGui = new LoadoutPresetGui(this, loadoutPresetService, loadoutService, loadoutBuilderGui);
        loadoutBuilderGui.setPresetsGui(loadoutPresetGui);

        BorderWallRenderer borderWallRenderer = new BorderWallRenderer(this, configManager, arenaConfig, worldManager);

        TeamSelectGui teamSelectGui = new TeamSelectGui(this, teamService, configManager, loadoutService);
        TeamEnforcer teamEnforcer = new TeamEnforcer(this, teamService, teamSelectGui);

        RandomSpawnSelector randomSpawns = new RandomSpawnSelector(arenaConfig, worldManager);

        voteService = new VoteService(configManager.getVoteableGamemodes(), RoundService.PLAYABLE);
        roundService = new RoundService(this, configManager, teamService, loadoutService, loadoutPresetService,
                spawnProtectionManager, deathSpectatorService, worldManager, borderWallRenderer, teamEnforcer,
                randomSpawns, gunGameService, statService);
        roundService.setEndRoundSequence(new EndRoundSequence(this, configManager, roundService,
                deathSpectatorService, voteService, new VoteGui(voteService)));
        // Breaks the TeamEnforcer/DeathSpectatorService <-> RoundService construction cycles —
        // both are built before RoundService since RoundService's constructor takes them.
        teamEnforcer.setRoundService(roundService);
        deathSpectatorService.setRoundService(roundService);

        // Both objectives need a live RoundService reference to credit their score, so they're
        // wired in after RoundService exists rather than through its constructor.
        ObjectiveUIManager hillUi = new ObjectiveUIManager(this);
        HillObjective hillObjective = new HillObjective(this, configManager, arenaConfig, worldManager,
                teamService, deathSpectatorService, roundService, hillUi, statService);
        roundService.setHillObjective(hillObjective);

        ObjectiveUIManager dominationUi = new ObjectiveUIManager(this);
        DominationObjective dominationObjective = new DominationObjective(this, configManager, arenaConfig,
                worldManager, teamService, deathSpectatorService, roundService, dominationUi, statService);
        roundService.setDominationObjective(dominationObjective);

        KillstreakService killstreakService = new KillstreakService(configManager, loadoutService, roundService);
        CombatListener combatListener = new CombatListener(configManager, teamService, loadoutService, roundService,
                killstreakService);
        GunGameListener gunGameListener = new GunGameListener(configManager, gunGameService, roundService,
                killstreakService);
        StatListener statListener = new StatListener(statService, roundService);
        scoreboardService = new ScoreboardService(this, configManager, roundService);
        afkService = new AfkService(this, configManager, roundService);

        getServer().getPluginManager().registerEvents(spawnProtectionManager, this);
        getServer().getPluginManager().registerEvents(deathSpectatorService, this);
        getServer().getPluginManager().registerEvents(loadoutService, this);
        getServer().getPluginManager().registerEvents(hillUi, this);
        getServer().getPluginManager().registerEvents(dominationUi, this);
        getServer().getPluginManager().registerEvents(loadoutPresetGui, this);
        getServer().getPluginManager().registerEvents(combatListener, this);
        getServer().getPluginManager().registerEvents(gunGameListener, this);
        getServer().getPluginManager().registerEvents(statListener, this);
        getServer().getPluginManager().registerEvents(borderWallRenderer, this);
        getServer().getPluginManager().registerEvents(teamEnforcer, this);
        getServer().getPluginManager().registerEvents(killstreakService, this);
        getServer().getPluginManager().registerEvents(scoreboardService, this);
        getServer().getPluginManager().registerEvents(afkService, this);
        scoreboardService.start();
        afkService.start();

        setExecutor("arena", new ArenaAdminCommand(arenaConfig));
        setExecutor("team", new TeamCommand(teamSelectGui, roundService));
        setExecutor("loadout", new LoadoutCommand(loadoutService, loadoutBuilderGui));
        setExecutor("loadouts", new LoadoutPresetCommand(loadoutPresetGui));
        setExecutor("round", new RoundCommand(roundService));
        setExecutor("admin", new AdminCommand(loadoutService, configManager, roundService, statService));
        StatCommand statCommand = new StatCommand(statService, new StatGui(statService));
        setExecutor("stats", statCommand);
        setExecutor("leaderboard", statCommand);
        setExecutor("history", statCommand);

        // Players should always be able to load straight into the current round rather
        // than waiting on an admin — the arena is ready as soon as the plugin is.
        if (!RoundService.PLAYABLE.isEmpty()) {
            roundService.startRound(RoundService.PLAYABLE.get(0));
        }

        getLogger().info("[Skirmish] Enabled");
    }

    @Override
    public void onDisable() {
        if (scoreboardService != null) scoreboardService.stop();
        if (afkService != null) afkService.stop();
        // Dispose the round world copy — the template is never touched (design doc §7.3).
        if (roundService != null) roundService.shutdown();
        if (deathSpectatorService != null) deathSpectatorService.stopAll();
        if (databaseManager != null) databaseManager.close();
        getLogger().info("[Skirmish] Disabled");
    }

    private void setExecutor(String name, org.bukkit.command.CommandExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("Command /" + name + " missing from plugin.yml");
            return;
        }
        command.setExecutor(executor);
        if (executor instanceof org.bukkit.command.TabCompleter completer) command.setTabCompleter(completer);
    }

    /**
     * Loads the arena <em>template</em> — the pristine world admins build in and point
     * {@code /arena} at. Rounds never run here; each one plays in a throwaway copy made by
     * {@link WorldManager} (design doc §7.3).
     *
     * Never generates a missing world — a hand-built arena that isn't on disk is a setup
     * mistake, not something to paper over with a fresh empty world.
     */
    private void loadArenaWorld(String name) {
        if (name == null || name.isBlank()) {
            getLogger().warning("No arena world set in arena.yml — run /arena setworld in the arena.");
            return;
        }
        World world = Bukkit.getWorld(name);
        if (world != null) {
            world.setKeepSpawnInMemory(true);
            return;
        }
        if (!new File(Bukkit.getWorldContainer(), name).isDirectory()) {
            getLogger().severe("Arena world '" + name + "' does not exist on disk.");
            return;
        }
        world = Bukkit.createWorld(new WorldCreator(name));
        if (world == null) {
            getLogger().severe("Failed to load arena world '" + name + "'.");
            return;
        }
        world.setKeepSpawnInMemory(true);
    }
}
