package org.flintstqne.skirmish;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.flintstqne.skirmish.CombatLogic.CombatListener;
import org.flintstqne.skirmish.CombatLogic.DeathSpectatorService;
import org.flintstqne.skirmish.CombatLogic.SpawnProtectionManager;
import org.flintstqne.skirmish.LoadoutLogic.LoadoutBuilderGui;
import org.flintstqne.skirmish.LoadoutLogic.LoadoutCatalog;
import org.flintstqne.skirmish.LoadoutLogic.LoadoutCommand;
import org.flintstqne.skirmish.LoadoutLogic.LoadoutService;
import org.flintstqne.skirmish.LoadoutLogic.WeaponFactory;
import org.flintstqne.skirmish.MapLogic.ArenaAdminCommand;
import org.flintstqne.skirmish.MapLogic.ArenaConfig;
import org.flintstqne.skirmish.TeamLogic.TeamCommand;
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

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);

        arenaConfig = new ArenaConfig(this);
        arenaConfig.load();

        databaseManager = new DatabaseManager(getDataFolder(), getLogger());
        try {
            databaseManager.open();
        } catch (SQLException e) {
            getLogger().severe("Could not open data.db: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        loadArenaWorld();

        teamService = new TeamService(configManager, arenaConfig);
        spawnProtectionManager = new SpawnProtectionManager(configManager, teamService);
        deathSpectatorService = new DeathSpectatorService(this, configManager, teamService, spawnProtectionManager);

        File catalogFile = new File(getDataFolder(), "loadout-catalog.yml");
        if (!catalogFile.exists()) saveResource("loadout-catalog.yml", false);
        loadoutCatalog = new LoadoutCatalog(getLogger());
        loadoutCatalog.load(catalogFile);
        loadoutService = new LoadoutService(configManager, loadoutCatalog, new WeaponFactory(this));
        LoadoutBuilderGui loadoutBuilderGui = new LoadoutBuilderGui(loadoutService);
        CombatListener combatListener = new CombatListener(configManager, teamService, loadoutService);

        getServer().getPluginManager().registerEvents(teamService, this);
        getServer().getPluginManager().registerEvents(spawnProtectionManager, this);
        getServer().getPluginManager().registerEvents(deathSpectatorService, this);
        getServer().getPluginManager().registerEvents(loadoutService, this);
        getServer().getPluginManager().registerEvents(combatListener, this);

        setExecutor("arena", new ArenaAdminCommand(arenaConfig));
        setExecutor("team", new TeamCommand(new TeamSelectGui(teamService, configManager)));
        setExecutor("loadout", new LoadoutCommand(loadoutService, loadoutBuilderGui));

        getLogger().info("[Skirmish] Enabled");
    }

    @Override
    public void onDisable() {
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
    }

    /**
     * The arena is one long-lived world kept loaded for the plugin's lifetime (design doc §7.3).
     * Never generates a missing world — a hand-built arena that isn't on disk is a setup mistake,
     * not something to paper over with a fresh empty world.
     */
    private void loadArenaWorld() {
        String name = arenaConfig.getWorldName();
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
}
