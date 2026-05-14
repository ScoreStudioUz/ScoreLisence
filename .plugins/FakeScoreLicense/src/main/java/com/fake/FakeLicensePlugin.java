package com.fake;

import org.bukkit.plugin.java.JavaPlugin;

public final class FakeLicensePlugin extends JavaPlugin {
    @Override public void onEnable() {
        getLogger().info("FAKE ScoreLicense loaded - does nothing");
    }
    @Override public void onDisable() {}
}
