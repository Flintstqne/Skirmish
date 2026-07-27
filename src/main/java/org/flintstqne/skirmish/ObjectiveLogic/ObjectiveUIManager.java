package org.flintstqne.skirmish.ObjectiveLogic;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.flintstqne.skirmish.Skirmish;
import org.flintstqne.skirmish.TeamLogic.Team;

/**
 * Objective HUD shared across capture-point gamemodes (design doc §8.1/§8.2 — built for
 * KOTH first, meant to carry over to Domination without change): a compass pointing at the
 * objective, and a boss bar showing who holds it and how close the round is to ending.
 *
 * Uses {@link Player#setCompassTarget} rather than a custom waypoint renderer — the design
 * doc left the exact HUD mechanism unspecified pending API research; the vanilla compass
 * needs no extra rendering code and works on every Paper version.
 *
 * Implements {@link Listener} so a mid-round joiner gets caught up immediately rather than
 * waiting for the next round (§11 QoL) — register the instance in {@code onEnable} alongside
 * every other listener.
 */
public final class ObjectiveUIManager implements Listener {

    private final Skirmish plugin;
    private BossBar bossBar;
    private Location objective;

    public ObjectiveUIManager(Skirmish plugin) {
        this.plugin = plugin;
    }

    public void start(Location objective, String label) {
        stop();
        this.objective = objective;
        bossBar = Bukkit.createBossBar(label, BarColor.WHITE, BarStyle.SOLID);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            bossBar.addPlayer(player);
            player.setCompassTarget(objective);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (bossBar == null) return;
        bossBar.addPlayer(event.getPlayer());
        event.getPlayer().setCompassTarget(objective);
    }

    /** @param progress 0.0-1.0 toward the score threshold; clamped defensively. */
    public void update(String status, Team holder, double progress) {
        if (bossBar == null) return;
        bossBar.setTitle(status);
        bossBar.setColor(holder == null ? BarColor.WHITE : holder == Team.RED ? BarColor.RED : BarColor.BLUE);
        bossBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
    }

    public void stop() {
        if (bossBar == null) return;
        bossBar.removeAll();
        bossBar = null;
        objective = null;
    }
}
