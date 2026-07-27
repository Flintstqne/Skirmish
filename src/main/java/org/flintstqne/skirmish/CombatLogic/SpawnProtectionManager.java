package org.flintstqne.skirmish.CombatLogic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.flintstqne.skirmish.ConfigManager;
import org.flintstqne.skirmish.TeamLogic.TeamService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Two layers of spawn protection (design doc §7.7):
 * personal timed invulnerability on every respawn, and — for fixed-spawn gamemodes only —
 * a persistent no-damage/no-break/no-build bubble around each configured team spawn.
 */
public final class SpawnProtectionManager implements Listener {

    /**
     * Spawn protection guards against combat, not gravity or an admin's own command — these
     * causes always go through even while invulnerable or inside the zone. KILL is what
     * {@code /kill} (and anything else that force-kills) fires; FALL is normal fall damage.
     */
    private static final Set<EntityDamageEvent.DamageCause> ALWAYS_APPLIES = Set.of(
            EntityDamageEvent.DamageCause.KILL, EntityDamageEvent.DamageCause.FALL);

    private final ConfigManager config;
    private final TeamService teams;
    private final Map<UUID, Long> invulnerableUntil = new HashMap<>();

    /** Random-spawn gamemodes (FFA, GUN_GAME) have no fixed point to protect — RoundService flips this. */
    private boolean zonesEnabled = true;

    public SpawnProtectionManager(ConfigManager config, TeamService teams) {
        this.config = config;
        this.teams = teams;
    }

    public void setZonesEnabled(boolean enabled) {
        this.zonesEnabled = enabled;
    }

    /** Called on every respawn (and round start). */
    public void grantInvulnerability(Player player) {
        int seconds = config.getInvulnerabilitySeconds();
        invulnerableUntil.put(player.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
        player.sendMessage(Component.text("Spawn protection: " + seconds + "s", NamedTextColor.AQUA));
    }

    public boolean isInvulnerable(Player player) {
        Long until = invulnerableUntil.get(player.getUniqueId());
        return until != null && System.currentTimeMillis() < until;
    }

    public void clearInvulnerability(Player player) {
        invulnerableUntil.remove(player.getUniqueId());
    }

    // ponytail: re-reads spawns from arena.yml per event — fine for 2 spawns, cache in
    // TeamService (invalidated on /arena setspawn) if damage-event profiling ever shows it.
    private boolean inSpawnZone(Location loc) {
        if (!zonesEnabled) return false;
        int radius = config.getSpawnZoneRadius();
        List<Location> spawns = teams.getAllTeamSpawns();
        for (Location spawn : spawns) {
            if (spawn.getWorld() == null || !spawn.getWorld().equals(loc.getWorld())) continue;
            if (spawn.distanceSquared(loc) <= (double) radius * radius) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (ALWAYS_APPLIES.contains(event.getCause())) return;
        if (isInvulnerable(victim)) {
            event.setCancelled(true);
            return;
        }
        if (config.isSpawnZoneNoDamage() && inSpawnZone(victim.getLocation())) {
            event.setCancelled(true);
        }
    }

    /** An attacker standing in a spawn zone can't shoot out of it either. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (!config.isSpawnZoneNoDamage()) return;
        if (event.getDamager() instanceof Player attacker && inSpawnZone(attacker.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (config.isSpawnZoneNoBreak() && inSpawnZone(event.getBlock().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (config.isSpawnZoneNoBuild() && inSpawnZone(event.getBlock().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearInvulnerability(event.getPlayer());
    }

    private void deny(Player player) {
        player.sendActionBar(Component.text("Protected spawn area.", NamedTextColor.RED));
    }
}
