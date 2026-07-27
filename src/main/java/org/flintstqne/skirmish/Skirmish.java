package org.flintstqne.skirmish;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.flintstqne.skirmish.CombatLogic.CombatListener;
import org.flintstqne.skirmish.CombatLogic.DeathSpectatorService;
import org.flintstqne.skirmish.CombatLogic.SpawnProtectionManager;
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
import org.flintstqne.skirmish.MapLogic.WorldManager;
import org.flintstqne.skirmish.RoundLogic.EndRoundSequence;
import org.flintstqne.skirmish.RoundLogic.RoundCommand;
import org.flintstqne.skirmish.RoundLogic.RoundService;
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
        loadoutService = new LoadoutService(configManager, loadoutCatalog, new WeaponFactory(this));
        loadoutPresetService = new LoadoutPresetService(databaseManager, loadoutService, configManager, getLogger());

        deathSpectatorService = new DeathSpectatorService(this, configManager, teamService,
                spawnProtectionManager, loadoutPresetService);

        // LoadoutBuilderGui and LoadoutPresetGui navigate to each other — the setter breaks
        // the constructor cycle (see LoadoutBuilderGui#setPresetsGui).
        LoadoutBuilderGui loadoutBuilderGui = new LoadoutBuilderGui(loadoutService, loadoutPresetService);
        LoadoutPresetGui loadoutPresetGui = new LoadoutPresetGui(loadoutPresetService, loadoutService, loadoutBuilderGui);
        loadoutBuilderGui.setPresetsGui(loadoutPresetGui);

        BorderWallRenderer borderWallRenderer = new BorderWallRenderer(this, configManager, arenaConfig, worldManager);

        TeamSelectGui teamSelectGui = new TeamSelectGui(this, teamService, configManager);
        TeamEnforcer teamEnforcer = new TeamEnforcer(this, teamService, teamSelectGui);

        voteService = new VoteService(configManager.getVoteableGamemodes(), RoundService.PLAYABLE);
        roundService = new RoundService(this, configManager, teamService, loadoutService, loadoutPresetService,
                spawnProtectionManager, deathSpectatorService, worldManager, borderWallRenderer, teamEnforcer);
        roundService.setEndRoundSequence(new EndRoundSequence(this, configManager, roundService,
                deathSpectatorService, voteService, new VoteGui(voteService)));

        CombatListener combatListener = new CombatListener(configManager, teamService, loadoutService, roundService);

        getServer().getPluginManager().registerEvents(spawnProtectionManager, this);
        getServer().getPluginManager().registerEvents(deathSpectatorService, this);
        getServer().getPluginManager().registerEvents(loadoutService, this);
        getServer().getPluginManager().registerEvents(combatListener, this);
        getServer().getPluginManager().registerEvents(borderWallRenderer, this);
        getServer().getPluginManager().registerEvents(teamEnforcer, this);

        setExecutor("arena", new ArenaAdminCommand(arenaConfig));
        setExecutor("team", new TeamCommand(teamSelectGui));
        setExecutor("loadout", new LoadoutCommand(loadoutService, loadoutBuilderGui));
        setExecutor("loadouts", new LoadoutPresetCommand(loadoutPresetGui));
        setExecutor("round", new RoundCommand(roundService));
        setExecutor("admin", new AdminCommand(loadoutService));

        // Players should always be able to load straight into the current round rather
        // than waiting on an admin — the arena is ready as soon as the plugin is.
        if (!RoundService.PLAYABLE.isEmpty()) {
            roundService.startRound(RoundService.PLAYABLE.get(0));
        }

        getLogger().info("[Skirmish] Enabled");
    }

    @Override
    public void onDisable() {
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

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public ArenaConfig getArenaConfig() {
        return arenaConfig;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public TeamService getTeamService() {
        return teamService;
    }

    public SpawnProtectionManager getSpawnProtectionManager() {
        return spawnProtectionManager;
    }

    public DeathSpectatorService getDeathSpectatorService() {
        return deathSpectatorService;
    }

    public LoadoutCatalog getLoadoutCatalog() {
        return loadoutCatalog;
    }

    public LoadoutService getLoadoutService() {
        return loadoutService;
    }

    public LoadoutPresetService getLoadoutPresetService() {
        return loadoutPresetService;
    }

    public RoundService getRoundService() {
        return roundService;
    }

    public VoteService getVoteService() {
        return voteService;
    }

    public WorldManager getWorldManager() {
        return worldManager;
    }
}
