package org.flintstqne.skirmish.TeamLogic;

import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.flintstqne.skirmish.ConfigManager;
import org.flintstqne.skirmish.LoadoutLogic.LoadoutService;

import java.util.ArrayList;
import java.util.List;

/**
 * Team select GUI (design doc §9.1). One row: red banner, info book, blue banner,
 * plus a swap-incentive slot beside the short team's banner while an imbalance lock is up.
 *
 * Team selection is mandatory: closing this GUI without a team reopens it next tick (see
 * {@link TeamEnforcer}). Once a player has a team — including via `/team` on an existing
 * one, which is purely informational — closing is left alone.
 */
public final class TeamSelectGui {

    private static final int SLOT_RED = 3;
    private static final int SLOT_INFO = 4;
    private static final int SLOT_BLUE = 5;

    private final Plugin plugin;
    private final TeamService teams;
    private final ConfigManager config;
    private final LoadoutService loadouts;

    public TeamSelectGui(Plugin plugin, TeamService teams, ConfigManager config, LoadoutService loadouts) {
        this.plugin = plugin;
        this.teams = teams;
        this.config = config;
        this.loadouts = loadouts;
    }

    public void open(Player player) {
        ChestGui gui = new ChestGui(1, "Select Team");
        gui.setOnGlobalClick(event -> event.setCancelled(true));
        gui.setOnClose(event -> {
            if (teams.getTeam(player) == null) {
                plugin.getServer().getScheduler().runTask(plugin, () -> open(player));
            }
        });

        StaticPane pane = new StaticPane(0, 0, 9, 1);
        gui.addPane(pane);

        pane.addItem(bannerItem(player, Team.RED), SLOT_RED, 0);
        pane.addItem(bannerItem(player, Team.BLUE), SLOT_BLUE, 0);
        pane.addItem(infoItem(player), SLOT_INFO, 0);

        Team shortTeam = teams.getShortTeam();
        if (shortTeam != null && teams.getTeam(player) == shortTeam.opposite()) {
            // Offer the switch beside the short team's banner — the side that needs bodies.
            int slot = shortTeam == Team.RED ? SLOT_RED - 1 : SLOT_BLUE + 1;
            pane.addItem(swapItem(player, shortTeam), slot, 0);
        }

        gui.show(player);
    }

    private GuiItem bannerItem(Player player, Team team) {
        Team current = teams.getTeam(player);
        boolean locked = teams.isLocked(team);
        boolean mine = current == team;

        List<Component> lore = new ArrayList<>();
        lore.add(text(teams.count(team) + " players", NamedTextColor.GRAY));
        if (mine) {
            lore.add(text("Your team", NamedTextColor.GREEN));
        } else if (locked) {
            lore.add(text("Locked — team is too full", NamedTextColor.RED));
        } else {
            lore.add(text("Click to join", NamedTextColor.YELLOW));
        }

        ItemStack item = item(locked && !mine ? Material.BARRIER : team.getBanner(),
                text(team.getDisplayName() + " Team", team.getColor()), lore);

        return new GuiItem(item, click -> {
            if (mine) return;
            if (!teams.join(player, team)) {
                player.sendMessage(text(team.getDisplayName() + " team is locked — it's too full.", NamedTextColor.RED));
                return;
            }
            player.sendMessage(text("Joined " + team.getDisplayName() + " team.", team.getColor()));
            player.teleport(teams.getSpawn(player));
            click.getWhoClicked().closeInventory();
        });
    }

    private GuiItem infoItem(Player player) {
        Team current = teams.getTeam(player);
        List<Component> lore = List.of(
                text("Red: " + teams.count(Team.RED), NamedTextColor.RED),
                text("Blue: " + teams.count(Team.BLUE), NamedTextColor.BLUE),
                text("You: " + (current == null ? "unassigned" : current.getDisplayName()), NamedTextColor.GRAY));
        return new GuiItem(item(Material.BOOK, text("Teams", NamedTextColor.WHITE), lore));
    }

    private GuiItem swapItem(Player player, Team target) {
        int bonus = config.getSwapIncentivePoints();
        ItemStack item = item(Material.NETHER_STAR,
                text("Switch to " + target.getDisplayName(), target.getColor()),
                List.of(text("+" + bonus + " points", NamedTextColor.GOLD),
                        text("Teams are uneven — help even them out.", NamedTextColor.GRAY)));
        return new GuiItem(item, click -> {
            if (teams.swapToShortTeam(player)) {
                loadouts.addPoints(player, bonus);
                player.sendMessage(text("Switched to " + target.getDisplayName()
                        + " team. +" + bonus + " points.", target.getColor()));
                player.teleport(teams.getSpawn(player));
                click.getWhoClicked().closeInventory();
            }
        });
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
