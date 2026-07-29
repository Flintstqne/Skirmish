package org.flintstqne.skirmish.LoadoutLogic;

import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.flintstqne.skirmish.Utils.Branding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loadout builder GUI (design doc §9.2). Row 0 = category tabs, rows 1-4 = the tier grid
 * for the open tab, row 5 = footer. Selecting equips immediately — the footer is a live
 * summary, not a submit button (§7.5.3).
 */
public final class LoadoutBuilderGui {

    private static final int ROWS = 6;
    private static final int TIER_ROWS = 4;
    private static final int WIDTH = 9;
    private static final int FOOTER_ROW = 5;

    private final LoadoutService loadouts;
    private final LoadoutPresetService presets;
    private final Map<UUID, LoadoutCatalog.Category> openTab = new HashMap<>();

    /** Set once, right after construction — see the Skirmish wiring for why this can't be a constructor arg. */
    private LoadoutPresetGui presetsGui;

    public LoadoutBuilderGui(LoadoutService loadouts, LoadoutPresetService presets) {
        this.loadouts = loadouts;
        this.presets = presets;
    }

    public void setPresetsGui(LoadoutPresetGui presetsGui) {
        this.presetsGui = presetsGui;
    }

    public void open(Player player) {
        open(player, openTab.getOrDefault(player.getUniqueId(), LoadoutCatalog.Category.PRIMARY));
    }

    public void open(Player player, LoadoutCatalog.Category tab) {
        openTab.put(player.getUniqueId(), tab);

        ChestGui gui = new ChestGui(ROWS, "Loadout");
        gui.setOnGlobalClick(event -> event.setCancelled(true));
        StaticPane pane = new StaticPane(0, 0, WIDTH, ROWS);
        gui.addPane(pane);

        LoadoutCatalog.Category[] categories = LoadoutCatalog.Category.values();
        for (int i = 0; i < categories.length && i < WIDTH; i++) {
            pane.addItem(tabItem(player, categories[i], tab), i, 0);
        }

        List<LoadoutCatalog.Entry> entries = loadouts.getCatalog().get(tab);
        int capacity = TIER_ROWS * WIDTH;
        for (int i = 0; i < entries.size() && i < capacity; i++) {
            pane.addItem(tierItem(player, entries.get(i)), i % WIDTH, 1 + i / WIDTH);
        }
        if (entries.size() > capacity) {
            Branding.error(player, "Category '" + tab.displayName() + "' has more tiers than the GUI can show ("
                    + entries.size() + " of " + capacity + " shown).");
        }

        pane.addItem(myLoadoutsTabItem(player), WIDTH - 1, 0);

        pane.addItem(pointsItem(player), 2, FOOTER_ROW);
        pane.addItem(summaryItem(player), 4, FOOTER_ROW);
        pane.addItem(savePresetItem(player), 6, FOOTER_ROW);
        pane.addItem(closeItem(), 8, FOOTER_ROW);

        gui.show(player);
    }

    private GuiItem myLoadoutsTabItem(Player player) {
        ItemStack stack = item(Material.CHEST, line("My Loadouts", NamedTextColor.WHITE),
                List.of(line("Click to view saved presets", NamedTextColor.YELLOW)));
        return new GuiItem(stack, click -> {
            if (presetsGui != null) presetsGui.open(player);
        });
    }

    private GuiItem savePresetItem(Player player) {
        boolean canSave = presets.canSaveMore(player);
        List<Component> lore = new ArrayList<>();
        if (canSave) {
            lore.add(line("Click to save your current", NamedTextColor.GRAY));
            lore.add(line("selection as a new preset.", NamedTextColor.GRAY));
            lore.add(line("Only free-tier picks carry over —", NamedTextColor.DARK_GRAY));
            lore.add(line("paid gear is a one-life purchase.", NamedTextColor.DARK_GRAY));
        } else {
            lore.add(line("You're at the saved-loadout limit.", NamedTextColor.RED));
            lore.add(line("Delete one in My Loadouts first.", NamedTextColor.RED));
        }

        ItemStack stack = item(canSave ? Material.EMERALD : Material.BARRIER,
                line("Save Preset", canSave ? NamedTextColor.GREEN : NamedTextColor.RED), lore);
        return new GuiItem(stack, click -> {
            if (!canSave) return;
            LoadoutPreset saved = presets.save(player, "Loadout " + (presets.list(player).size() + 1));
            if (saved == null) {
                Branding.error(player, "Couldn't save that preset — try again.");
                return;
            }
            Branding.success(player, "Saved as '" + saved.name() + "'.");
            if (presetsGui != null) presetsGui.open(player);
        });
    }

    private GuiItem tabItem(Player player, LoadoutCatalog.Category category, LoadoutCatalog.Category open) {
        LoadoutCatalog.Entry selected = loadouts.getSelected(player, category);
        List<Component> lore = new ArrayList<>();
        lore.add(line(selected == null ? "Nothing selected" : selected.name(), NamedTextColor.GRAY));
        lore.add(line(category == open ? "Open" : "Click to view", NamedTextColor.YELLOW));

        ItemStack stack = item(category == open ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                line(category.displayName(), NamedTextColor.WHITE), lore);
        return new GuiItem(stack, click -> open(player, category));
    }

    private GuiItem tierItem(Player player, LoadoutCatalog.Entry entry) {
        boolean affordable = loadouts.canAfford(player, entry);
        boolean equipped = entry.key().equals(loadouts.getSelection(player).get(entry.category()));

        List<Component> lore = new ArrayList<>();
        lore.add(line(entry.isFree() ? "Free" : entry.cost() + " pts",
                entry.isFree() ? NamedTextColor.GREEN : NamedTextColor.GOLD));
        if (equipped) {
            lore.add(line("Equipped", NamedTextColor.GREEN));
        } else if (!affordable) {
            lore.add(line("Not enough points this life", NamedTextColor.RED));
        } else {
            lore.add(line("Click to equip", NamedTextColor.YELLOW));
        }

        Material icon = !affordable && !equipped ? Material.BARRIER : iconFor(entry);
        ItemStack stack = item(icon, line(entry.name(), equipped ? NamedTextColor.GREEN : NamedTextColor.WHITE), lore);

        return new GuiItem(stack, click -> {
            if (equipped) return;
            if (!loadouts.select(player, entry)) {
                Branding.error(player, "You can't afford " + entry.name() + " this life.");
                return;
            }
            open(player, entry.category());
        });
    }

    private GuiItem pointsItem(Player player) {
        List<Component> lore = List.of(
                line("Spent: " + loadouts.getSpent(player), NamedTextColor.GRAY),
                line("Remaining: " + loadouts.getRemaining(player), NamedTextColor.GREEN),
                line("Points reset when you die.", NamedTextColor.DARK_GRAY));
        return new GuiItem(item(Material.SUNFLOWER,
                line("Points: " + loadouts.getPoints(player), NamedTextColor.GOLD), lore));
    }

    private GuiItem summaryItem(Player player) {
        List<Component> lore = new ArrayList<>();
        for (LoadoutCatalog.Category category : LoadoutCatalog.Category.values()) {
            LoadoutCatalog.Entry entry = loadouts.getSelected(player, category);
            lore.add(line(category.displayName() + ": " + (entry == null ? "-" : entry.name()), NamedTextColor.GRAY));
        }
        return new GuiItem(item(Material.PAPER, line("Equipped", NamedTextColor.WHITE), lore));
    }

    private GuiItem closeItem() {
        return new GuiItem(item(Material.RED_STAINED_GLASS_PANE, line("Close", NamedTextColor.RED), List.of()),
                click -> click.getWhoClicked().closeInventory());
    }

    private Material iconFor(LoadoutCatalog.Entry entry) {
        if (entry.isWeapon()) return Material.IRON_HORSE_ARMOR;
        Material material = Material.matchMaterial(entry.item());
        return material == null || material == Material.AIR ? Material.LIGHT_GRAY_STAINED_GLASS_PANE : material;
    }

    private ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream().map(l -> l.decoration(TextDecoration.ITALIC, false)).toList());
        });
        return stack;
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color);
    }
}
