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
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.flintstqne.skirmish.ConfigManager;
import org.flintstqne.skirmish.RoundLogic.RoundService;
import org.flintstqne.skirmish.Skirmish;
import org.flintstqne.skirmish.Utils.Branding;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Spectator state in two variants (design doc §7.8): the in-round death spectator,
 * locked to a sphere around the death location for {@code death.respawn-seconds},
 * and the free-roam end-of-round variant with no lock.
 *
 * Deliberately not vanilla GameMode.SPECTATOR - spectators phase through blocks, and
 * the ported Trenched behaviour blocks that.
 */
public final class DeathSpectatorService implements Listener {

    private record State(Location anchor, Integer lockRadius, GameMode previousMode) {}

    private final Skirmish plugin;
    private final ConfigManager config;
    private final Map<UUID, State> spectating = new HashMap<>();
    /** Victim -> killer, recorded by CombatListener/GunGameListener right at the kill so the
     * death spectator (which fires off PlayerDeathEvent, a tick later) knows who to follow. */
    private final Map<UUID, UUID> lastKiller = new HashMap<>();
    private final Map<UUID, BukkitTask> followTasks = new HashMap<>();
    private RoundService rounds;

    public DeathSpectatorService(Skirmish plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * Set post-construction - RoundService takes a DeathSpectatorService, so this breaks the
     * cycle. Once set, respawn goes through {@link RoundService#preparePlayer}, which is
     * gamemode-aware about spawn location (team spawn vs. FFA random point, §8.4); without
     * it, respawn falls back to a fixed team spawn only, which is wrong for FFA.
     */
    public void setRoundService(RoundService rounds) {
        this.rounds = rounds;
    }

    public boolean isSpectating(Player player) {
        return spectating.containsKey(player.getUniqueId());
    }

    /** Free-roam variant - whole arena, no radius lock, no auto-exit (§7.8). */
    public void startFreeRoam(Player player) {
        apply(player, player.getLocation(), null);
    }

    /** Recorded by CombatListener/GunGameListener at the moment of a kill - see {@link #lastKiller}. */
    public void recordKiller(Player victim, Player killer) {
        lastKiller.put(victim.getUniqueId(), killer.getUniqueId());
    }

    /**
     * In-round death variant, auto-respawning after the configured delay. Follows the killer
     * (§11 QoL pass) when one's known and still online/spectatable; otherwise falls back to
     * the locked sphere around the death location, same as before this existed.
     */
    public void startDeathSpectator(Player player, Location deathLocation) {
        Player killer = resolveFollowTarget(player);
        if (killer != null) {
            applyFollow(player, killer);
        } else {
            apply(player, deathLocation, config.getSpectatorLockRadius());
        }
        int seconds = config.getRespawnSeconds();
        Branding.send(player, "Respawning in " + seconds + "s");
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !isSpectating(player)) return;
            stop(player);
            // Gear goes back on here, not at the fake respawn above - while spectating,
            // apply() strips the inventory so a dead player visibly has nothing (see below).
            // rounds is always set by Skirmish.onEnable() before any player can die.
            rounds.preparePlayer(player);
        }, seconds * 20L);
    }

    private Player resolveFollowTarget(Player victim) {
        if (!config.isFollowKillerEnabled()) return null;
        UUID killerId = lastKiller.get(victim.getUniqueId());
        if (killerId == null || killerId.equals(victim.getUniqueId())) return null;
        Player killer = plugin.getServer().getPlayer(killerId);
        return (killer != null && killer.isOnline()) ? killer : null;
    }

    /** Chase-cam a few blocks behind the killer, re-snapped every 2 ticks. Falls back to a
     * static lock at the last good position if the killer disconnects mid-follow. */
    private void applyFollow(Player player, Player killer) {
        apply(player, killer.getLocation(), null);
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || !isSpectating(player)) return;
            if (!killer.isOnline()) {
                stopFollowTask(player);
                return;
            }
            Location eye = killer.getEyeLocation();
            Vector behind = eye.getDirection().normalize().multiply(-3);
            Location camera = eye.clone().add(behind).add(0, 1, 0);
            camera.setDirection(eye.getDirection());
            player.teleport(camera);
        }, 2L, 2L);
        followTasks.put(player.getUniqueId(), task);
    }

    private void stopFollowTask(Player player) {
        BukkitTask task = followTasks.remove(player.getUniqueId());
        if (task != null) task.cancel();
    }

    public void stop(Player player) {
        stopFollowTask(player);
        lastKiller.remove(player.getUniqueId());
        State state = spectating.remove(player.getUniqueId());
        if (state == null) return;
        player.setGameMode(state.previousMode());
        player.setFlying(false);
        // CREATIVE grants flight on its own - forcing it off here would strip it and
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

        // A dead/spectating player has nothing to fight with - inventory should show it.
        player.getInventory().clear();
        // clear() skips armor slots (same Bukkit gotcha as LoadoutService#applyToInventory).
        player.getInventory().setChestplate(null);

        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (!other.equals(player)) other.hidePlayer(plugin, player);
        }
    }

    // Loadouts are re-granted from the preset/catalog every respawn (design doc §5.1), not
    // looted - dropping them on death would just litter the map with gear nobody can use
    // (loadout keys aren't ItemStacks another player can pick up and equip through Skirmish).
    @EventHandler
    public void onDeathClearDrops(PlayerDeathEvent event) {
        event.getDrops().clear();
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
        stopFollowTask(event.getPlayer());
        lastKiller.remove(event.getPlayer().getUniqueId());
        spectating.remove(event.getPlayer().getUniqueId());
    }
}
