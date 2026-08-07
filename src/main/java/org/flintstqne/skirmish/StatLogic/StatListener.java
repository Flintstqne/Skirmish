package org.flintstqne.skirmish.StatLogic;

import me.deecaad.weaponmechanics.weapon.weaponevents.WeaponKillEntityEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.flintstqne.skirmish.RoundLogic.RoundService;

/**
 * Lifetime kill/death tracking (design doc §7.10) - mode-agnostic, unlike
 * {@code CombatListener}'s per-life points or {@code GunGameListener}'s ladder: every
 * gamemode's kills and deaths count toward the same lifetime stats.
 *
 * Deaths come from {@link PlayerDeathEvent} (any death counts, not just weapon kills -
 * matches the usual K/D convention) rather than {@link WeaponKillEntityEvent}'s victim, so a
 * fall or environmental death still counts against you.
 *
 * Only counts during an active round - stats shouldn't move from an admin's {@code /kill}
 * test or a death while free-roaming after a round has already ended.
 */
public final class StatListener implements Listener {

    /** The one melee weapon in this project - shared by the loadout catalog's "knife" tool
     * and Gun Game's final tier, so a single title check covers knife kills in every mode. */
    private static final String KNIFE_TITLE = "Combat_Knife";

    private final StatService stats;
    private final RoundService rounds;

    public StatListener(StatService stats, RoundService rounds) {
        this.stats = stats;
        this.rounds = rounds;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWeaponKill(WeaponKillEntityEvent event) {
        if (!rounds.isActive()) return;
        if (!(event.getShooter() instanceof Player killer)) return;
        if (!(event.getVictim() instanceof Player victim)) return;
        if (killer.equals(victim)) return;

        stats.recordKill(killer);
        if (KNIFE_TITLE.equals(event.getWeaponTitle())) stats.recordKnifeKill(killer);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!rounds.isActive()) return;
        stats.recordDeath(event.getPlayer());
    }
}
