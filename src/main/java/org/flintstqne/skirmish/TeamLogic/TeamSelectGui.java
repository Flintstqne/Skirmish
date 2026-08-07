package org.flintstqne.skirmish.TeamLogic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.flintstqne.skirmish.ConfigManager;
import org.flintstqne.skirmish.LoadoutLogic.LoadoutService;
import org.flintstqne.skirmish.Utils.Branding;

import java.util.List;

/**
 * Team select GUI (design doc §9.1). One row: red banner, info book, blue banner,
 * plus a swap-incentive slot beside the short team's banner while an imbalance lock is up.
 *
 * Team selection is mandatory: closing this GUI without a team reopens it next tick (see
 * {@link TeamEnforcer}). Once a player has a team - including via `/team` on an existing
 * one, which is purely informational - closing is left alone.
 *
 * Plain Bukkit inventory + a direct click listener, not the InventoryFramework library the
 * rest of Skirmish's GUIs use - IF's click routing (which matches a clicked item back to its
 * handler via a hidden NBT tag rather than slot index) was silently swallowing every click on
 * this GUI specifically for one tester, with no exception and no way to reproduce or debug it
 * further. A raw slot-index dispatch has nothing to mismatch.
 */
public final class TeamSelectGui implements Listener {

    private static final int SLOT_SWAP_LEFT = 2;
    private static final int SLOT_RED = 3;
    private static final int SLOT_INFO = 4;
    private static final int SLOT_BLUE = 5;
    private static final int SLOT_SWAP_RIGHT = 6;
    private static final Component TITLE = Component.text("Select Team");

    private record Holder(Player player) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            throw new UnsupportedOperationException();
        }
    }

    private final Plugin plugin;
    private final TeamService teams;
    private final ConfigManager config;
    private final LoadoutService loadouts;
    private final TeamColorService teamColors;

    public TeamSelectGui(Plugin plugin, TeamService teams, ConfigManager config, LoadoutService loadouts,
                          TeamColorService teamColors) {
        this.plugin = plugin;
        this.teams = teams;
        this.config = config;
        this.loadouts = loadouts;
        this.teamColors = teamColors;
    }

    public void open(Player player) {
        Inventory inventory = Bukkit.createInventory(new Holder(player), 9, TITLE);

        inventory.setItem(SLOT_RED, bannerItem(player, Team.RED));
        inventory.setItem(SLOT_BLUE, bannerItem(player, Team.BLUE));
        inventory.setItem(SLOT_INFO, infoItem(player));

        Team shortTeam = teams.getShortTeam();
        if (shortTeam != null && teams.getTeam(player) == shortTeam.opposite()) {
            // Offer the switch beside the short team's banner - the side that needs bodies.
            int slot = shortTeam == Team.RED ? SLOT_SWAP_LEFT : SLOT_SWAP_RIGHT;
            inventory.setItem(slot, swapItem(shortTeam));
        }

        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player) || !player.equals(holder.player())) return;

        switch (event.getRawSlot()) {
            case SLOT_RED -> handleJoinClick(player, Team.RED);
            case SLOT_BLUE -> handleJoinClick(player, Team.BLUE);
            case SLOT_SWAP_LEFT -> handleSwapClick(player, Team.RED);
            case SLOT_SWAP_RIGHT -> handleSwapClick(player, Team.BLUE);
            default -> { /* info book / empty slots - no action */ }
        }
    }

    private void handleJoinClick(Player player, Team team) {
        if (teams.getTeam(player) == team) return;
        if (!teams.join(player, team)) {
            Branding.error(player, team.getDisplayName() + " team is locked - it's too full.");
            return;
        }
        teamColors.apply(player, team);
        player.sendMessage(Branding.message("Joined " + team.getDisplayName() + " team.", team.getColor()));
        player.teleport(teams.getSpawn(player));
        player.closeInventory();
    }

    private void handleSwapClick(Player player, Team target) {
        // The swap slot is only populated when `target` is genuinely the short team the
        // viewer is opposite of, but a stale click after teams shift shouldn't silently swap.
        if (teams.getShortTeam() != target || teams.getTeam(player) != target.opposite()) return;
        if (!teams.swapToShortTeam(player)) return;

        int bonus = config.getSwapIncentiveMerit();
        teamColors.apply(player, target);
        loadouts.addMerit(player, bonus);
        player.sendMessage(Branding.message("Switched to " + target.getDisplayName()
                + " team. +" + bonus + " Merit.", target.getColor()));
        player.teleport(teams.getSpawn(player));
        player.closeInventory();
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        Player player = holder.player();
        if (teams.getTeam(player) != null) return;

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && teams.getTeam(player) == null) open(player);
        }, 1L);
    }

    private ItemStack bannerItem(Player player, Team team) {
        Team current = teams.getTeam(player);
        boolean locked = teams.isLocked(team);
        boolean mine = current == team;

        java.util.List<Component> lore = new java.util.ArrayList<>();
        lore.add(text(teams.count(team) + " players", NamedTextColor.GRAY));
        if (mine) {
            lore.add(text("Your team", NamedTextColor.GREEN));
        } else if (locked) {
            lore.add(text("Locked - team is too full", NamedTextColor.RED));
        } else {
            lore.add(text("Click to join", NamedTextColor.YELLOW));
        }

        return item(locked && !mine ? Material.BARRIER : team.getBanner(),
                text(team.getDisplayName() + " Team", team.getColor()), lore);
    }

    private ItemStack infoItem(Player player) {
        Team current = teams.getTeam(player);
        List<Component> lore = List.of(
                text("Red: " + teams.count(Team.RED), NamedTextColor.RED),
                text("Blue: " + teams.count(Team.BLUE), NamedTextColor.BLUE),
                text("You: " + (current == null ? "unassigned" : current.getDisplayName()), NamedTextColor.GRAY));
        return item(Material.BOOK, text("Teams", NamedTextColor.WHITE), lore);
    }

    private ItemStack swapItem(Team target) {
        int bonus = config.getSwapIncentiveMerit();
        return item(Material.NETHER_STAR,
                text("Switch to " + target.getDisplayName(), target.getColor()),
                List.of(text("+" + bonus + " Merit", NamedTextColor.GOLD),
                        text("Teams are uneven - help even them out.", NamedTextColor.GRAY)));
    }

    private ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream().map(l -> l.decoration(TextDecoration.ITALIC, false)).toList());
        });
        return stack;
    }

    private Component text(String s, NamedTextColor color) {
        return Component.text(s, color);
    }
}
