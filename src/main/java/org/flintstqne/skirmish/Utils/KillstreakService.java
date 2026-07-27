package org.flintstqne.skirmish.Utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.flintstqne.skirmish.ConfigManager;
import org.flintstqne.skirmish.LoadoutLogic.LoadoutService;
import org.flintstqne.skirmish.RoundLogic.GamemodeType;
import org.flintstqne.skirmish.RoundLogic.RoundService;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Killstreak/multikill callouts (§11 item 5) — a per-life counter, reset on death, broadcast
 * to everyone whenever it crosses a configured threshold. Optional bonus points, off by default
 * since per-life points are already the game's snowball risk (see config.yml).
 *
 * Gated off entirely for Gun Game (design doc §8.5's win condition already *is* consecutive
 * kills up a ladder — `GunGameListener`'s tier progress already communicates that, and a
 * second streak counter on top would be redundant, differently-paced noise). */
public final class KillstreakService implements Listener {

    private final ConfigManager config;
    private final LoadoutService loadouts;
    private final RoundService rounds;
    private final Map<UUID, Integer> streaks = new HashMap<>();

    public KillstreakService(ConfigManager config, LoadoutService loadouts, RoundService rounds) {
        this.config = config;
        this.loadouts = loadouts;
        this.rounds = rounds;
    }

    public void onKill(Player killer) {
        if (!config.isKillstreaksEnabled()) return;
        if (rounds.getGamemode() == GamemodeType.GUN_GAME) return;
        int streak = streaks.merge(killer.getUniqueId(), 1, Integer::sum);
        String callout = config.getKillstreakThresholds().get(streak);
        if (callout == null) return;

        if (config.isKillstreakBonusPointsEnabled()) {
            loadouts.addPoints(killer, config.getKillstreakBonusPointsPerThreshold());
        }
        Component message = Component.text(
                killer.getName() + " is on a " + callout + "! (" + streak + " kills)", NamedTextColor.GOLD);
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(message));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        streaks.remove(event.getPlayer().getUniqueId());
    }
}
