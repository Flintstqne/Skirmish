package org.flintstqne.skirmish.Utils;

import net.kyori.adventure.text.Component;
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

    /** Multi-kill callout by kills-within-the-window count (§11 QoL pass) — distinct from the
     * per-life {@code streaks} below: this counts kill *speed*, not kills-without-dying, so
     * dying between two fast kills doesn't reset it. Capped at "Frenzy" past 5 — "Rampage" is
     * already a config.yml threshold name below and the two would otherwise collide. */
    private static final Map<Integer, String> MULTIKILL_NAMES =
            Map.of(2, "Double Kill", 3, "Triple Kill", 4, "Quad Kill", 5, "Penta Kill");

    private final ConfigManager config;
    private final LoadoutService loadouts;
    private final RoundService rounds;
    private final Map<UUID, Integer> streaks = new HashMap<>();
    private final Map<UUID, Long> lastKillAtMillis = new HashMap<>();
    private final Map<UUID, Integer> multiKillCounts = new HashMap<>();

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
        if (callout != null) {
            if (config.isKillstreakBonusPointsEnabled()) {
                loadouts.addPoints(killer, config.getKillstreakBonusPointsPerThreshold());
            }
            Component message = Branding.message(
                    killer.getName() + " is on a " + callout + "! (" + streak + " kills)", Branding.BRAND);
            Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(message));
        }
        announceMultiKill(killer);
    }

    private void announceMultiKill(Player killer) {
        UUID id = killer.getUniqueId();
        long now = System.currentTimeMillis();
        long windowMillis = config.getMultiKillWindowSeconds() * 1000L;
        Long lastKillAt = lastKillAtMillis.put(id, now);
        int count = (lastKillAt != null && now - lastKillAt <= windowMillis)
                ? multiKillCounts.getOrDefault(id, 1) + 1 : 1;
        multiKillCounts.put(id, count);

        String name = count >= 6 ? "Frenzy" : MULTIKILL_NAMES.get(count);
        if (name == null) return;
        Component message = Branding.message(killer.getName() + ": " + name + "!", Branding.BRAND);
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(message));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        streaks.remove(event.getPlayer().getUniqueId());
    }
}
