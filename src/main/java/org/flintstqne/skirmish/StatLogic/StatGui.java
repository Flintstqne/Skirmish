package org.flintstqne.skirmish.StatLogic;

import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.flintstqne.skirmish.DatabaseManager;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** GUI front end for /leaderboard and /history (§11 QoL pass) - same data StatCommand's text
 * output already reads from StatService, just picked with a click instead of typed. The text
 * commands stay as-is for scripted/no-args-known usage; these open when no args are given. */
public final class StatGui {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** Display order + icon per leaderboard category - StatService's category set is
     * unordered, and a GUI needs a stable, readable order. */
    private static final List<String> CATEGORY_ORDER = List.of(
            "kills", "deaths", "knife_kills", "objective_points", "rounds_played", "rounds_won");
    private static final List<Material> CATEGORY_ICONS = List.of(
            Material.IRON_SWORD, Material.SKELETON_SKULL, Material.IRON_INGOT,
            Material.BEACON, Material.CLOCK, Material.GOLDEN_APPLE);

    private final StatService stats;

    public StatGui(StatService stats) {
        this.stats = stats;
    }

    public void openLeaderboardMenu(Player player) {
        ChestGui gui = new ChestGui(1, "Leaderboards");
        gui.setOnGlobalClick(event -> event.setCancelled(true));
        StaticPane pane = new StaticPane(0, 0, 9, 1);
        gui.addPane(pane);

        for (int i = 0; i < CATEGORY_ORDER.size(); i++) {
            String category = CATEGORY_ORDER.get(i);
            ItemStack icon = item(CATEGORY_ICONS.get(i), text(label(category), NamedTextColor.WHITE),
                    List.of(text("Click to view", NamedTextColor.GRAY)));
            pane.addItem(new GuiItem(icon, click -> openLeaderboard(player, category)), i, 0);
        }
        gui.show(player);
    }

    public void openLeaderboard(Player player, String category) {
        List<DatabaseManager.LeaderboardRow> rows = stats.getLeaderboard(category, 10);
        ChestGui gui = new ChestGui(2, "Top " + label(category));
        gui.setOnGlobalClick(event -> event.setCancelled(true));
        StaticPane pane = new StaticPane(0, 0, 9, 2);
        gui.addPane(pane);

        pane.addItem(backButton(this::openLeaderboardMenu), 8, 1);

        if (rows == null || rows.isEmpty()) {
            pane.addItem(new GuiItem(item(Material.BARRIER,
                    text("No stats recorded yet.", NamedTextColor.GRAY), List.of())), 4, 0);
            gui.show(player);
            return;
        }

        int rank = 1;
        for (DatabaseManager.LeaderboardRow row : rows) {
            int slot = rank - 1; // top 10 fits across row 0 (9) plus one on row 1
            ItemStack head = item(Material.PLAYER_HEAD,
                    text(rank + ". " + row.name(), NamedTextColor.GOLD),
                    List.of(text(label(category) + ": " + row.value(), NamedTextColor.WHITE)));
            head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(Bukkit.getOfflinePlayer(row.name())));
            pane.addItem(new GuiItem(head), slot % 9, slot / 9);
            rank++;
        }
        gui.show(player);
    }

    public void openHistory(Player player) {
        List<DatabaseManager.RoundHistoryRow> rounds = stats.getRecentRounds(18);
        int guiRows = Math.max(1, Math.min(6, (rounds.size() / 9) + 2));
        ChestGui gui = new ChestGui(guiRows, "Recent Rounds");
        gui.setOnGlobalClick(event -> event.setCancelled(true));
        StaticPane pane = new StaticPane(0, 0, 9, guiRows);
        gui.addPane(pane);

        if (rounds.isEmpty()) {
            pane.addItem(new GuiItem(item(Material.BARRIER,
                    text("No rounds recorded yet.", NamedTextColor.GRAY), List.of())), 4, 0);
        }

        int slot = 0;
        for (DatabaseManager.RoundHistoryRow round : rounds) {
            if (slot >= 9 * guiRows) break;
            String when = TIMESTAMP.format(Instant.ofEpochMilli(round.startedAt()));
            String winner = round.winner() == null ? "Draw" : round.winner();
            ItemStack icon = item(Material.MAP,
                    text(round.gamemode(), NamedTextColor.WHITE),
                    List.of(
                            text(when, NamedTextColor.GRAY),
                            text("Score: " + round.finalScoreA() + " - " + round.finalScoreB(), NamedTextColor.GRAY),
                            text("Winner: " + winner, NamedTextColor.GOLD)));
            pane.addItem(new GuiItem(icon), slot % 9, slot / 9);
            slot++;
        }
        gui.show(player);
    }

    private GuiItem backButton(java.util.function.Consumer<Player> reopen) {
        ItemStack item = item(Material.ARROW, text("Back", NamedTextColor.WHITE), List.of());
        return new GuiItem(item, click -> reopen.accept((Player) click.getWhoClicked()));
    }

    private String label(String category) {
        String[] parts = category.split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
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
