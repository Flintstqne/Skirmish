package org.flintstqne.skirmish.TeamLogic;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

public enum Team {
    RED("Red", Material.RED_BANNER, NamedTextColor.RED),
    BLUE("Blue", Material.BLUE_BANNER, NamedTextColor.BLUE);

    private final String displayName;
    private final Material banner;
    private final NamedTextColor color;

    Team(String displayName, Material banner, NamedTextColor color) {
        this.displayName = displayName;
        this.banner = banner;
        this.color = color;
    }

    public String getDisplayName() { return displayName; }
    public Material getBanner() { return banner; }
    public NamedTextColor getColor() { return color; }

    public Team opposite() {
        return this == RED ? BLUE : RED;
    }
}
