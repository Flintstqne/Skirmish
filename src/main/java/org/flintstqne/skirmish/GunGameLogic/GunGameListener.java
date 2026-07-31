package org.flintstqne.skirmish.GunGameLogic;

import me.deecaad.weaponmechanics.weapon.weaponevents.WeaponKillEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.flintstqne.skirmish.ConfigManager;
import org.flintstqne.skirmish.RoundLogic.GamemodeType;
import org.flintstqne.skirmish.RoundLogic.RoundService;
import org.flintstqne.skirmish.Utils.KillFeedUtil;
import org.flintstqne.skirmish.Utils.KillstreakService;

import java.util.List;

/**
 * Gun Game's ladder rules (design doc §8.5) — deliberately asymmetric:
 * <ul>
 *   <li>gun kill → promote</li>
 *   <li>knife kill → promote (same as a gun kill, unless it's the win case below)</li>
 *   <li>getting knifed → the victim demotes one tier, whether or not the killer was promoted</li>
 *   <li>dying to a gun → no tier change for either player</li>
 *   <li>a knife kill landed <em>while already on the final tier</em> wins the round instantly,
 *       bypassing the normal threshold/timer end entirely</li>
 * </ul>
 *
 * Kill attribution reuses the same {@link WeaponKillEntityEvent} CombatListener uses for
 * TDM/FFA — the weapon title on the event is enough to tell a knife kill from a gun kill
 * without a second WM event.
 */
public final class GunGameListener implements Listener {

    private final ConfigManager config;
    private final GunGameService gunGame;
    private final RoundService rounds;
    private final KillstreakService killstreaks;

    public GunGameListener(ConfigManager config, GunGameService gunGame, RoundService rounds,
                           KillstreakService killstreaks) {
        this.config = config;
        this.gunGame = gunGame;
        this.rounds = rounds;
        this.killstreaks = killstreaks;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWeaponKill(WeaponKillEntityEvent event) {
        if (rounds.getGamemode() != GamemodeType.GUN_GAME) return;
        if (!(event.getShooter() instanceof Player killer)) return;
        if (!(event.getVictim() instanceof Player victim)) return;
        if (killer.equals(victim)) return;
        // Same reasoning as CombatListener: ladder promotion/demotion and the instant-win
        // check are round rewards, not just round-score, so a warmup kill shouldn't touch them.
        if (!rounds.isActive()) return;
        rounds.recordKillForMvp(killer);
        rounds.recordKiller(victim, killer);
        if (rounds.claimFirstBlood()) KillFeedUtil.announceFirstBlood(killer);
        killstreaks.onKill(killer);
        KillFeedUtil.broadcastKillFeed(config, killer, victim, event.getWeaponTitle());
        KillFeedUtil.sendDeathRecap(config, victim, killer, event.getWeaponTitle());

        List<String> ladder = config.getWeaponLadder();
        if (ladder.isEmpty()) return;
        boolean knifeKill = ladder.get(ladder.size() - 1).equals(event.getWeaponTitle());

        if (knifeKill && gunGame.isFinalTier(killer) && config.isFinalKnifeKillInstantWin()) {
            killer.sendActionBar(Component.text("FINAL KILL!", NamedTextColor.GOLD));
            rounds.endRoundForPlayer(killer);
            return;
        }

        if (knifeKill && config.isDemoteOnKnifeDeath()) {
            if (gunGame.isFloorTier(victim)) {
                victim.sendActionBar(Component.text("Already at tier 1 — no lower to demote to.", NamedTextColor.GRAY));
            } else {
                gunGame.demote(victim);
            }
        }

        gunGame.promote(killer);
        killer.sendActionBar(Component.text(
                "Tier " + (gunGame.getTier(killer) + 1) + "/" + ladder.size(), NamedTextColor.GREEN));
    }
}
