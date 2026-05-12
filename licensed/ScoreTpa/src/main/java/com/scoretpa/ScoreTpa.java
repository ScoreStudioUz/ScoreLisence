package com.scoretpa;

import com.scoretpa.command.*;
import com.scoretpa.listener.CombatListener;
import com.scoretpa.listener.MoveListener;
import com.scoretpa.manager.*;
import com.scoretpa.placeholder.TpaPlaceholderExpansion;
import org.bukkit.plugin.java.JavaPlugin;

public final class ScoreTpa extends JavaPlugin {

    private static ScoreTpa instance;

    private ConfigManager configManager;
    private RequestManager requestManager;
    private CooldownManager cooldownManager;
    private CombatManager combatManager;
    private TeleportManager teleportManager;

    @Override
    public void onEnable() {
        instance = this;

        configManager = new ConfigManager(this);
        cooldownManager = new CooldownManager(this);
        combatManager = new CombatManager();
        requestManager = new RequestManager(this);
        teleportManager = new TeleportManager(this);

        registerCommands();
        registerListeners();
        registerPlaceholders();

        getLogger().info("ScoreTpa v" + getDescription().getVersion() + " enabled!");
    }

    @Override
    public void onDisable() {
        if (teleportManager != null) teleportManager.cancelAll();
        getLogger().info("ScoreTpa disabled!");
    }

    private void registerCommands() {
        getCommand("tpa").setExecutor(new TpaCommand(this));
        getCommand("tpaccept").setExecutor(new TpAcceptCommand(this));
        getCommand("tpdeny").setExecutor(new TpDenyCommand(this));
        getCommand("tpcancel").setExecutor(new TpCancelCommand(this));
        getCommand("tptoggle").setExecutor(new TpToggleCommand(this));
        getCommand("scoretpa").setExecutor(new ScoreTpaCommand(this));
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new CombatListener(this), this);
        getServer().getPluginManager().registerEvents(new MoveListener(this), this);
    }

    private void registerPlaceholders() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new TpaPlaceholderExpansion(this).register();
            getLogger().info("PlaceholderAPI hook registered.");
        }
    }

    public void reload() {
        configManager.reload();
    }

    public static ScoreTpa getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public RequestManager getRequestManager() {
        return requestManager;
    }

    public CooldownManager getCooldownManager() {
        return cooldownManager;
    }

    public CombatManager getCombatManager() {
        return combatManager;
    }

    public TeleportManager getTeleportManager() {
        return teleportManager;
    }
}
