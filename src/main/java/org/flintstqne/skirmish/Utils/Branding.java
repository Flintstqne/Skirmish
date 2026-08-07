package org.flintstqne.skirmish.Utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;

/** Single source of truth for Skirmish's visual identity - the message prefix and the color
 * palette every command, listener, GUI, scoreboard, and kill feed line draws from. Nothing in
 * the plugin should pick its own ad hoc {@link NamedTextColor} for chat/UI text anymore; add a
 * constant here instead so the brand stays one thing instead of drifting per-class. */
public final class Branding {

    public static final NamedTextColor BRAND = NamedTextColor.GOLD;
    public static final NamedTextColor BODY = NamedTextColor.GRAY;
    public static final NamedTextColor HIGHLIGHT = NamedTextColor.WHITE;
    public static final NamedTextColor SUCCESS = NamedTextColor.GREEN;
    public static final NamedTextColor ERROR = NamedTextColor.RED;
    public static final NamedTextColor WARNING = NamedTextColor.YELLOW;
    public static final NamedTextColor INFO = NamedTextColor.AQUA;
    public static final NamedTextColor MUTED = NamedTextColor.DARK_GRAY;
    public static final NamedTextColor TEAM_RED = NamedTextColor.RED;
    public static final NamedTextColor TEAM_BLUE = NamedTextColor.BLUE;

    /** {@code [Skirmish] } - every player-facing message is built with this in front. */
    public static final Component PREFIX = Component.text()
            .append(Component.text("[", MUTED))
            .append(Component.text("Skirmish", BRAND, TextDecoration.BOLD))
            .append(Component.text("]", MUTED))
            .append(Component.space())
            .build();

    private Branding() {}

    /** Wraps an already-built message with the brand prefix. Use this when a call site needs to
     * keep its existing color choice (e.g. a GUI item lore line reused as a chat message). */
    public static Component prefix(Component body) {
        return Component.text().append(PREFIX).append(body).build();
    }

    public static Component message(String body, NamedTextColor color) {
        return prefix(Component.text(body, color));
    }

    public static void send(CommandSender to, String body) {
        to.sendMessage(message(body, BODY));
    }

    public static void success(CommandSender to, String body) {
        to.sendMessage(message(body, SUCCESS));
    }

    public static void error(CommandSender to, String body) {
        to.sendMessage(message(body, ERROR));
    }

    public static void warning(CommandSender to, String body) {
        to.sendMessage(message(body, WARNING));
    }

    public static void info(CommandSender to, String body) {
        to.sendMessage(message(body, INFO));
    }
}
