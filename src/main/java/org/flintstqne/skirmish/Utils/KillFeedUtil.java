package org.flintstqne.skirmish.Utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.flintstqne.skirmish.ConfigManager;

/** Kill feed broadcast + death recap (§11 items 3-4) — both are formatting off the same
 * killer/victim/weapon triple every kill path (CombatListener, GunGameListener) already has. */
public final class KillFeedUtil {

    private KillFeedUtil() {}

    public static void broadcastKillFeed(ConfigManager config, Player killer, Player victim, String weaponTitle) {
        if (!config.isKillFeedEnabled()) return;
        Component line = Component.text(killer.getName(), NamedTextColor.WHITE)
                .append(Component.text(" ☠ ", NamedTextColor.RED))
                .append(Component.text(weaponTitle, NamedTextColor.GRAY))
                .append(Component.text(" ☠ ", NamedTextColor.RED))
                .append(Component.text(victim.getName(), NamedTextColor.WHITE));
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(line));
    }

    public static void sendDeathRecap(ConfigManager config, Player victim, Player killer, String weaponTitle) {
        if (!config.isDeathRecapEnabled()) return;
        victim.sendMessage(Component.text(
                "Killed by " + killer.getName() + " with " + weaponTitle, NamedTextColor.RED));
    }
}
