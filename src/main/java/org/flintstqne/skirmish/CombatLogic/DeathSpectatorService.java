package org.flintstqne.skirmish.CombatLogic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.flintstqne.skirmish.ConfigManager;
import org.flintstqne.skirmish.LoadoutLogic.LoadoutPresetService;
import org.flintstqne.skirmish.Skirmish;
import org.flintstqne.skirmish.TeamLogic.TeamService;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Spectator state in two variants (design doc §7.8): the in-round death spectator,
 * locked to a sphere around the death location for {@code death.respawn-seconds},
 * and the free-roam end-of-round variant with no lock.
 *
 * Deliberately not vanilla GameMode.SPECTATOR — spectators phase through blocks, and
 * the ported Trenched behaviour blocks that.
 */
public final class DeathSpectatorService implements Listener {

    private record State(Location anchor, Integer lockRadius, GameMode previousMode) {}

    private final Skirmish plugin;
    private final ConfigManager config;
    private final TeamService teams;
    private final SpawnProtectionManager spawnProtection;
    private final LoadoutPresetService loadoutPresets;
    private final Map<UUID, State> spectating = new HashMap<>();

    public DeathSpectatorService(Skirmish plugin, ConfigManager config, TeamService teams,
                                 SpawnProtectionManager spawnProtection, LoadoutPresetService loadoutPresets) {
        this.plugin = plugin;
        this.config = config;
        this.teams = teams;
        this.spawnProtection = spawnProtection;
        this.loadoutPresets = loadoutPresets;
    }

    public boolean isSpectating(Player player) {
        return spectating.containsKey(player.getUniqueId());
    }

    /** Free-roam variant — whole arena, no radius lock, no auto-exit (§7.8). */
    public void startFreeRoam(Player player) {
        apply(player, player.getLocation(), null);
    }

    /** In-round death variant — locked sphere, auto-respawns after the configured delay. */
    public void startDeathSpectator(Player player, Location deathLocation) {
        apply(player, deathLocation, config.getSpectatorLockRadius());
        int seconds = config.getRespawnSeconds();
        player.sendMessage(Component.text("Respawning in " + seconds + "s", NamedTextColor.GRAY));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !isSpectating(player)) return;
            stop(player);
            player.teleport(teams.getSpawn(player));
            // Gear goes back on here, not at the fake respawn above — while spectating,
            // apply() strips the inventory so a dead player visibly has nothing (see below).
            loadoutPresets.onRespawn(player);
            spawnProtection.grantInvulnerability(player);
        }, seconds * 20L);
    }

    public void stop(Player player) {
        State state = spectating.remove(player.getUniqueId());
        if (state == null) return;
        player.setGameMode(state.previousMode());
        player.setFlying(false);
        // CREATIVE grants flight on its own — forcing it off here would strip it and
        // Bukkit won't hand it back automatically once the gamemode is already set.
        player.setAllowFlight(state.previousMode() == GameMode.CREATIVE);
        player.setCollidable(true);
        for (Player other : plugin.getServer().getOnlinePlayers()) other.showPlayer(plugin, player);
    }

    public void stopAll() {
        for (UUID id : Map.copyOf(spectating).keySet()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) stop(player);
        }
    }

    private void apply(Player player, Location anchor, Integer lockRadius) {
        if (!isSpectating(player)) {
            spectating.put(player.getUniqueId(), new State(anchor, lockRadius, player.getGameMode()));
        } else {
            State old = spectating.get(player.getUniqueId());
            spectating.put(player.getUniqueId(), new State(anchor, lockRadius, old.previousMode()));
        }
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setCollidable(false);
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));

        // A dead/spectating player has nothing to fight with — inventory should show it.
        player.getInventory().clear();
        // clear() skips armor slots (same Bukkit gotcha as LoadoutService#applyToInventory).
        player.getInventory().setChestplate(null);

        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (!other.equals(player)) other.hidePlayer(plugin, player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        Location deathLocation = player.getLocation();
        // Skip the vanilla respawn screen, then take over the body as a spectator.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            if (player.isDead()) player.spigot().respawn();
            player.teleport(deathLocation);
            startDeathSpectator(player, deathLocation);
        }, 1L);
    }

    /** Radius lock + no phasing through blocks. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        State state = spectating.get(player.getUniqueId());
        if (state == null) return;
        Location to = event.getTo();

        if (to.getBlock().getType().isSolid()) {
            event.setCancelled(true);
            return;
        }
        if (state.lockRadius() == null) return;
        Location anchor = state.anchor();
        if (anchor.getWorld() == null || !anchor.getWorld().equals(to.getWorld())) return;
        double radius = state.lockRadius();
        if (anchor.distanceSquared(to) > radius * radius) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("You can't leave your death area.", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isSpectating(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDealDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && isSpectating(player)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isSpectating(player)) event.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (isSpectating(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (isSpectating(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (isSpectating(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        spectating.remove(event.getPlayer().getUniqueId());
    }
}
