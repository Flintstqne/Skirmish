package org.flintstqne.skirmish.LoadoutLogic;

import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "My Loadouts" GUI (design doc §9.3). Left-click sets a preset active (persists across
 * rounds and restarts — distinct from the per-life economy); right-click deletes;
 * shift-click renames via a chat-capture prompt (no AnvilGUI dependency needed for one field).
 */
public final class LoadoutPresetGui implements Listener {

    private final Plugin plugin;
    private final LoadoutPresetService presets;
    private final LoadoutService loadouts;
    private final LoadoutBuilderGui builderGui;
    /** Player awaiting a chat-typed rename → the preset id it applies to. A ConcurrentHashMap
     * because {@link AsyncChatEvent} fires off the main thread — writes happen from GUI clicks
     * and PlayerQuitEvent on the main thread, so a plain HashMap here would be a real data race. */
    private final Map<UUID, Integer> pendingRename = new ConcurrentHashMap<>();

    public LoadoutPresetGui(Plugin plugin, LoadoutPresetService presets, LoadoutService loadouts,
                            LoadoutBuilderGui builderGui) {
        this.plugin = plugin;
        this.presets = presets;
        this.loadouts = loadouts;
        this.builderGui = builderGui;
    }

    public void open(Player player) {
        ChestGui gui = new ChestGui(1, "My Loadouts");
        gui.setOnGlobalClick(event -> event.setCancelled(true));
        StaticPane pane = new StaticPane(0, 0, 9, 1);
        gui.addPane(pane);

        List<LoadoutPreset> list = presets.list(player);
        LoadoutPreset active = presets.getActive(player);
        for (int i = 0; i < list.size() && i < 7; i++) {
            pane.addItem(presetItem(player, list.get(i), active), i, 0);
        }

        pane.addItem(newLoadoutItem(player), 7, 0);
        pane.addItem(backItem(player), 8, 0);

        gui.show(player);
    }

    private GuiItem presetItem(Player player, LoadoutPreset preset, LoadoutPreset active) {
        boolean isActive = active != null && active.id() == preset.id();

        List<Component> lore = new ArrayList<>();
        LoadoutCatalog.Entry primary = loadouts.getCatalog().get(preset.selection().get(LoadoutCatalog.Category.PRIMARY));
        if (primary != null) lore.add(text(primary.name(), NamedTextColor.GRAY));
        lore.add(isActive ? text("★ Active", NamedTextColor.GREEN) : text("Click to set active", NamedTextColor.YELLOW));
        lore.add(text("Right-click to delete", NamedTextColor.DARK_GRAY));
        lore.add(text("Shift-click to rename", NamedTextColor.DARK_GRAY));

        ItemStack stack = item(Material.WRITTEN_BOOK, text(preset.name(), NamedTextColor.WHITE), lore);
        if (isActive) stack.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);

        return new GuiItem(stack, click -> {
            if (click.isShiftClick()) {
                pendingRename.put(player.getUniqueId(), preset.id());
                player.closeInventory();
                player.sendMessage(text("Type the new name for '" + preset.name()
                        + "' in chat (or 'cancel').", NamedTextColor.YELLOW));
            } else if (click.isRightClick()) {
                presets.delete(preset.id());
                player.sendMessage(text("Deleted '" + preset.name() + "'.", NamedTextColor.RED));
                open(player);
            } else {
                presets.setActive(player, preset.id());
                player.sendMessage(text("'" + preset.name() + "' is now your active loadout.", NamedTextColor.GREEN));
                open(player);
            }
        });
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Integer presetId = pendingRename.get(player.getUniqueId());
        if (presetId == null) return;
        event.setCancelled(true);

        String newName = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            pendingRename.remove(player.getUniqueId());
            if (newName.isEmpty() || newName.equalsIgnoreCase("cancel")) {
                player.sendMessage(text("Rename cancelled.", NamedTextColor.GRAY));
                return;
            }
            presets.rename(presetId, newName);
            player.sendMessage(text("Renamed to '" + newName + "'.", NamedTextColor.GREEN));
            open(player);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pendingRename.remove(event.getPlayer().getUniqueId());
    }

    private GuiItem newLoadoutItem(Player player) {
        ItemStack stack = item(Material.EMERALD, text("+ New Loadout", NamedTextColor.GREEN),
                List.of(text("Build a loadout, then click", NamedTextColor.GRAY),
                        text("Save Preset to keep it.", NamedTextColor.GRAY)));
        return new GuiItem(stack, click -> builderGui.open(player));
    }

    private GuiItem backItem(Player player) {
        return new GuiItem(item(Material.ARROW, text("Back", NamedTextColor.YELLOW), List.of()),
                click -> builderGui.open(player));
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
