package org.flintstqne.skirmish.CombatLogic;

import me.deecaad.weaponmechanics.weapon.weaponevents.WeaponDamageEntityEvent;
import me.deecaad.weaponmechanics.weapon.weaponevents.WeaponKillEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.flintstqne.skirmish.ConfigManager;
import org.flintstqne.skirmish.LoadoutLogic.LoadoutService;
import org.flintstqne.skirmish.RoundLogic.RoundService;
import org.flintstqne.skirmish.TeamLogic.TeamService;

/**
 * Friendly fire and kill points (design doc §7.7).
 *
 * Friendly fire is cancelled as a hard rule, not a config toggle — there is deliberately
 * no switch for it. Both damage paths are covered: WeaponMechanics' own
 * {@link WeaponDamageEntityEvent} for guns, and vanilla {@link EntityDamageByEntityEvent}
 * for melee and anything WM doesn't route.
 *
 * Kill attribution comes from {@link WeaponKillEntityEvent} (shooter + victim + weapon
 * title in one event), which resolves the design doc's §13 open question.
 */
public final class CombatListener implements Listener {

    private final ConfigManager config;
    private final TeamService teams;
    private final LoadoutService loadouts;
    private final RoundService rounds;

    public CombatListener(ConfigManager config, TeamService teams, LoadoutService loadouts, RoundService rounds) {
        this.config = config;
        this.teams = teams;
        this.loadouts = loadouts;
        this.rounds = rounds;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWeaponDamage(WeaponDamageEntityEvent event) {
        if (isFriendlyFire(event.getShooter(), event.getVictim())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVanillaDamage(EntityDamageByEntityEvent event) {
        if (isFriendlyFire(event.getDamager(), event.getEntity())) event.setCancelled(true);
    }

    private boolean isFriendlyFire(Object attacker, Object victim) {
        if (!(attacker instanceof Player shooter) || !(victim instanceof Player hit)) return false;
        if (shooter.equals(hit)) return false;
        return teams.isSameTeam(shooter, hit);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWeaponKill(WeaponKillEntityEvent event) {
        if (!(event.getShooter() instanceof Player killer)) return;
        if (!(event.getVictim() instanceof Player victim)) return;
        if (killer.equals(victim)) return;

        int points = config.getKillPoints();
        loadouts.addPoints(killer, points);
        rounds.recordKill(killer);
        killer.sendActionBar(Component.text(
                "Killed " + victim.getName() + "  +" + points + " pts", NamedTextColor.GREEN));
    }

    /** Per-life economy reset and re-gear (§7.4, §7.6). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        loadouts.onRespawn(event.getPlayer());
    }
}
