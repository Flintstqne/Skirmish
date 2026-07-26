package org.flintstqne.skirmish;

import org.bukkit.plugin.java.JavaPlugin;

public final class Skirmish extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("[Skirmish] Enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("[Skirmish] Disabled");
    }
}
