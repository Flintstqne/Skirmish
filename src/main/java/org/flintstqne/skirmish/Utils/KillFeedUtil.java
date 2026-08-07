package org.flintstqne.skirmish.Utils;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.flintstqne.skirmish.ConfigManager;

/** Kill feed broadcast + death recap (§11 items 3-4) - both are formatting off the same
 * killer/victim/weapon triple every kill path (CombatListener, GunGameListener) already has. */
public final class KillFeedUtil {

    private KillFeedUtil() {}

    public static void broadcastKillFeed(ConfigManager config, Player killer, Player victim, String weaponTitle) {
        if (!config.isKillFeedEnabled()) return;
        Component line = Branding.prefix(Component.text(killer.getName(), Branding.HIGHLIGHT)
                .append(Component.text(" ☠ ", Branding.ERROR))
                .append(Component.text(weaponTitle, Branding.BODY))
                .append(Component.text(" ☠ ", Branding.ERROR))
                .append(Component.text(victim.getName(), Branding.HIGHLIGHT)));
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(line));
    }

    public static void sendDeathRecap(ConfigManager config, Player victim, Player killer, String weaponTitle) {
        if (!config.isDeathRecapEnabled()) return;
        Branding.error(victim, "Killed by " + killer.getName() + " with " + weaponTitle);
    }

    /** Broadcasts once per round - call site decides "once" via {@code RoundService#claimFirstBlood}. */
    public static void announceFirstBlood(Player killer) {
        Component message = Branding.message(
                "First blood: " + killer.getName() + "!", Branding.WARNING);
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(message));
    }
}
