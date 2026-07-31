package org.flintstqne.skirmish.Utils;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.flintstqne.skirmish.ConfigManager;
import org.flintstqne.skirmish.RoundLogic.RoundService;
import org.flintstqne.skirmish.Skirmish;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AFK kick during warmup and between rounds only (§11 QoL pass) — never mid-round, so holding
 * an objective or a defensive position doesn't read as AFK. Activity is any movement,
 * interaction, chat, or (re)join; checked on a slow poll rather than per-event.
 *
 * {@code lastActivity} is a {@link ConcurrentHashMap} because {@link AsyncChatEvent} writes to
 * it off the main thread (same reasoning as {@code LoadoutPresetGui.pendingRename}).
 */
public final class AfkService implements Listener {

    private final Skirmish plugin;
    private final ConfigManager config;
    private final RoundService rounds;
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    private BukkitTask task;

    public AfkService(Skirmish plugin, ConfigManager config, RoundService rounds) {
        this.plugin = plugin;
        this.config = config;
        this.rounds = rounds;
    }

    public void start() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkAfk, 200L, 200L);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    private void checkAfk() {
        if (!config.isAfkKickEnabled() || rounds.isActive()) return;
        long timeoutMillis = config.getAfkTimeoutSeconds() * 1000L;
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            long last = lastActivity.getOrDefault(player.getUniqueId(), now);
            if (now - last >= timeoutMillis) {
                player.kick(Branding.message("Kicked for being AFK too long.", Branding.WARNING));
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastActivity.remove(event.getPlayer().getUniqueId());
    }

    private void touch(Player player) {
        lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
    }
}
