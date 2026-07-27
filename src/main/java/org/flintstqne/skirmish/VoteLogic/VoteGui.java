package org.flintstqne.skirmish.VoteLogic;

import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.flintstqne.skirmish.RoundLogic.GamemodeType;

import java.util.ArrayList;
import java.util.List;

/**
 * End-of-round vote GUI (design doc §9.4). Click to vote, click again to change,
 * glint marks your current pick. Stays interactable for the whole countdown.
 */
public final class VoteGui {

    private static final int TIMER_SLOT = 8;

    private final VoteService votes;

    public VoteGui(VoteService votes) {
        this.votes = votes;
    }

    public void open(Player player, int secondsLeft) {
        ChestGui gui = new ChestGui(1, "Vote: Next Gamemode");
        gui.setOnGlobalClick(event -> event.setCancelled(true));
        StaticPane pane = new StaticPane(0, 0, 9, 1);
        gui.addPane(pane);

        List<GamemodeType> options = votes.getOptions();
        for (int i = 0; i < options.size() && i + 1 < TIMER_SLOT; i++) {
            pane.addItem(optionItem(player, options.get(i), secondsLeft), i + 1, 0);
        }
        pane.addItem(timerItem(secondsLeft), TIMER_SLOT, 0);

        gui.show(player);
    }

    private GuiItem optionItem(Player player, GamemodeType mode, int secondsLeft) {
        int count = votes.getVoteCount(mode);
        int percent = votes.getPercentage(mode);
        boolean mine = votes.getVote(player) == mode;

        List<Component> lore = new ArrayList<>();
        lore.add(text(bar(percent) + " " + percent + "%", NamedTextColor.AQUA));
        lore.add(text(count + (count == 1 ? " vote" : " votes"), NamedTextColor.GRAY));
        lore.add(text(mine ? "Your vote" : "Click to vote", mine ? NamedTextColor.GREEN : NamedTextColor.YELLOW));

        ItemStack stack = item(iconFor(mode), text(displayName(mode), NamedTextColor.WHITE), lore);
        if (mine) stack.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.UNBREAKING, 1);

        return new GuiItem(stack, click -> {
            votes.vote(player, mode);
            open(player, secondsLeft);
        });
    }

    private GuiItem timerItem(int secondsLeft) {
        return new GuiItem(item(Material.CLOCK,
                text("Next round in " + secondsLeft + "s", NamedTextColor.GOLD),
                List.of(text("You can change your vote until 0.", NamedTextColor.GRAY))));
    }

    /** Ten-segment bar, matching the §9.4 mockup's filled blocks. */
    private String bar(int percent) {
        int filled = Math.round(percent / 10f);
        return "█".repeat(filled) + "░".repeat(10 - filled);
    }

    private String displayName(GamemodeType mode) {
        return switch (mode) {
            case KOTH -> "King of the Hill";
            case DOMINATION -> "Domination";
            case TDM -> "Team Deathmatch";
            case FFA -> "Free-For-All";
            case GUN_GAME -> "Gun Game";
        };
    }

    private Material iconFor(GamemodeType mode) {
        return switch (mode) {
            case KOTH -> Material.GOLDEN_HELMET;
            case DOMINATION -> Material.BEACON;
            case TDM -> Material.IRON_SWORD;
            case FFA -> Material.SKELETON_SKULL;
            case GUN_GAME -> Material.ARROW;
        };
    }

    private ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream().map(l -> l.decoration(TextDecoration.ITALIC, false)).toList());
        });
        return stack;
    }

    private Component text(String value, NamedTextColor color) {
        return Component.text(value, color);
    }
}
